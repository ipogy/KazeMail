package com.kazemail.app.ai

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.kazemail.app.data.AppPreferences
import com.kazemail.app.data.MessageRepository
import com.kazemail.app.data.db.AiProviderProfileEntity
import com.kazemail.app.data.db.AiWritingPresetEntity
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.sync.Rfc2047HeaderDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coordinates BYOK AI profiles, Keystore-encrypted API keys, privacy guardrails,
 * writing prompt presets, connection verification, sanitized email context extraction,
 * and optional user-selected reference emails, images, and files.
 */
class AiPreparationService(
    private val db: KazeMailDatabase,
    private val appPreferences: AppPreferences,
    private val secretVault: AiSecretVault,
    private val aiClient: AiClient,
    private val messageRepository: MessageRepository,
    private val contextBuilder: AiEmailContextBuilder = AiEmailContextBuilder { compressed ->
        messageRepository.decompressHtmlInMemory(compressed)
    },
    private val binaryEncoder: AiBinaryMediaEncoder = AndroidAiBinaryMediaEncoder(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    fun observeProfiles(): Flow<List<AiProviderProfileSummary>> =
        db.aiProviderProfileDao().observeAll().map { entities ->
            entities.map { it.toSummary() }
        }

    fun observePresets(): Flow<List<AiWritingPresetEntity>> =
        db.aiWritingPresetDao().observeAll()

    fun observeReadinessState(): Flow<AiReadinessState> =
        combine(
            appPreferences.aiFeaturesEnabled,
            appPreferences.aiPrivacyConsentAccepted,
            db.aiProviderProfileDao().observeDefaultProfile(),
            db.aiProviderProfileDao().observeAll(),
        ) { enabled, consentAccepted, defaultProfile, allProfiles ->
            evaluateReadiness(
                featuresEnabled = enabled,
                privacyConsentAccepted = consentAccepted,
                activeProfile = defaultProfile ?: allProfiles.firstOrNull(),
            )
        }

    suspend fun getReadinessState(): AiReadinessState {
        val active = db.aiProviderProfileDao().getDefaultProfile()
            ?: db.aiProviderProfileDao().getAll().firstOrNull()
        return evaluateReadiness(
            featuresEnabled = appPreferences.aiFeaturesEnabled.value,
            privacyConsentAccepted = appPreferences.aiPrivacyConsentAccepted.value,
            activeProfile = active,
        )
    }

    suspend fun ensureDefaultPresetsSeeded() {
        if (db.aiWritingPresetDao().count() > 0) return
        val now = clockMs()
        val defaults = listOf(
            AiWritingPresetEntity(
                title = "前向きに承諾・日程確定",
                goal = AiWritingGoal.REPLY.storageValue,
                tone = AiReplyTone.FORMAL_BUSINESS.storageValue,
                instructionPrompt = "相手の提案・依頼に感謝を伝えた上で、承諾する返信を作成する。具体的な確認事項があれば簡潔に添える。",
                isBuiltIn = true,
                sortOrder = 1,
                createdAt = now,
                updatedAt = now,
            ),
            AiWritingPresetEntity(
                title = "確認中・いったん保留の返信",
                goal = AiWritingGoal.REPLY.storageValue,
                tone = AiReplyTone.POLITE.storageValue,
                instructionPrompt = "受領したお礼と現在確認中である旨を伝え、回答予定時期の目安を書く返信を作成する。",
                isBuiltIn = true,
                sortOrder = 2,
                createdAt = now,
                updatedAt = now,
            ),
            AiWritingPresetEntity(
                title = "角を立てずに丁寧なお断り",
                goal = AiWritingGoal.REPLY.storageValue,
                tone = AiReplyTone.APOLOGY.storageValue,
                instructionPrompt = "ご提案へのお礼を述べた上で、今回は対応・参加が難しいことを配慮ある表現で丁寧にお断りする。",
                isBuiltIn = true,
                sortOrder = 3,
                createdAt = now,
                updatedAt = now,
            ),
            AiWritingPresetEntity(
                title = "箇条書きメモからビジネスメール作成",
                goal = AiWritingGoal.EXPAND_BULLETS.storageValue,
                tone = AiReplyTone.FORMAL_BUSINESS.storageValue,
                instructionPrompt = "入力された箇条書きメモをもとに、挨拶・要旨・次のアクション・結びが揃った自然な日本語メールへ整える。",
                isBuiltIn = true,
                sortOrder = 4,
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.aiWritingPresetDao().insertAll(defaults)
    }

    suspend fun saveProfile(
        profileId: Long? = null,
        displayName: String,
        providerType: AiProviderType,
        baseUrl: String,
        modelId: String,
        rawApiKeyInput: String?,
        keepExistingKeyIfBlank: Boolean = true,
        setAsDefault: Boolean = true,
        isEnabled: Boolean = true,
    ): Long {
        val sanitizedUrl = AiSecretVault.sanitizeEndpointUrl(providerType, baseUrl)
        val normalizedModel = modelId.trim().let { raw ->
            if (providerType == AiProviderType.GEMINI && raw.startsWith("models/")) {
                raw.removePrefix("models/")
            } else {
                raw
            }
        }
        if (normalizedModel.isBlank()) {
            throw AiClientException(
                kind = AiClientException.Kind.INVALID_CONFIG,
                message = "モデル ID を入力してください",
            )
        }

        val existing = profileId?.takeIf { it > 0L }?.let { db.aiProviderProfileDao().getById(it) }
        val trimmedNewKey = rawApiKeyInput?.trim()?.takeIf { it.isNotEmpty() }

        val encryptedCipherB64: String?
        val encryptedIvB64: String?
        val hint: String?
        if (trimmedNewKey != null) {
            val blob = secretVault.encryptApiKey(trimmedNewKey)!!
            encryptedCipherB64 = blob.ciphertextB64
            encryptedIvB64 = blob.ivB64
            hint = AiSecretVault.maskApiKey(trimmedNewKey)
        } else if (existing != null && keepExistingKeyIfBlank) {
            encryptedCipherB64 = existing.encryptedApiKeyB64
            encryptedIvB64 = existing.apiKeyIvB64
            hint = existing.apiKeyHint
        } else {
            encryptedCipherB64 = null
            encryptedIvB64 = null
            hint = null
        }

        if (providerType.requiresApiKey && encryptedCipherB64.isNullOrBlank()) {
            throw AiClientException(
                kind = AiClientException.Kind.MISSING_API_KEY,
                message = "${providerType.displayLabel} の API キーを入力してください",
            )
        }

        val now = clockMs()
        val resolvedName = displayName.trim().ifBlank {
            "${providerType.displayLabel} ($normalizedModel)"
        }
        val shouldBeDefault = setAsDefault || existing?.isDefault == true || db.aiProviderProfileDao().count() == 0

        val configChanged = existing == null ||
            existing.providerType != providerType.storageValue ||
            existing.baseUrl != sanitizedUrl ||
            existing.modelId != normalizedModel ||
            trimmedNewKey != null

        var savedId = 0L
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (shouldBeDefault) {
                    db.aiProviderProfileDao().clearDefaultFlags()
                }
                val entity = AiProviderProfileEntity(
                    profileId = existing?.profileId ?: 0L,
                    displayName = resolvedName,
                    providerType = providerType.storageValue,
                    baseUrl = sanitizedUrl,
                    modelId = normalizedModel,
                    encryptedApiKeyB64 = encryptedCipherB64,
                    apiKeyIvB64 = encryptedIvB64,
                    apiKeyHint = hint,
                    isDefault = shouldBeDefault,
                    isEnabled = isEnabled,
                    lastTestStatus = if (configChanged) {
                        AiConnectionTestStatus.UNTESTED.storageValue
                    } else {
                        existing.lastTestStatus
                    },
                    lastTestMessage = if (configChanged) null else existing.lastTestMessage,
                    lastTestedAt = if (configChanged) null else existing.lastTestedAt,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
                savedId = db.aiProviderProfileDao().upsert(entity)
            }
        }
        return savedId
    }

    suspend fun setDefaultProfile(profileId: Long) {
        val now = clockMs()
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                db.aiProviderProfileDao().clearDefaultFlags()
                db.aiProviderProfileDao().markAsDefault(profileId, now)
            }
        }
    }

    suspend fun deleteProfile(profileId: Long) {
        val target = db.aiProviderProfileDao().getById(profileId) ?: return
        db.aiProviderProfileDao().deleteById(profileId)
        if (appPreferences.aiSubjectProfileId.value == profileId) {
            appPreferences.setAiSubjectProfileId(null)
        }
        if (target.isDefault) {
            val replacement = db.aiProviderProfileDao().getAll().firstOrNull()
            if (replacement != null) {
                db.aiProviderProfileDao().markAsDefault(replacement.profileId, clockMs())
            }
        }
    }

    suspend fun testSavedProfileConnection(profileId: Long): AiConnectionTestResult {
        val entity = db.aiProviderProfileDao().getById(profileId)
            ?: throw AiClientException(
                kind = AiClientException.Kind.INVALID_CONFIG,
                message = "対象の AI プロファイルが見つかりません",
            )
        val provider = AiProviderType.fromStorage(entity.providerType)
        val rawKey = secretVault.decryptApiKey(entity.encryptedApiKeyB64, entity.apiKeyIvB64)
        val result = aiClient.verifyConnection(
            AiProviderConnectionConfig(
                providerType = provider,
                baseUrl = entity.baseUrl,
                modelId = entity.modelId,
                apiKey = rawKey,
            ),
        )
        db.aiProviderProfileDao().updateTestResult(
            profileId = profileId,
            status = if (result.success) {
                AiConnectionTestStatus.SUCCESS.storageValue
            } else {
                AiConnectionTestStatus.FAILED.storageValue
            },
            message = result.message,
            testedAtMs = clockMs(),
        )
        return result
    }

    suspend fun testDraftConnection(
        providerType: AiProviderType,
        baseUrl: String,
        modelId: String,
        rawApiKeyInput: String?,
        existingProfileId: Long? = null,
    ): AiConnectionTestResult {
        val effectiveKey = rawApiKeyInput?.trim()?.takeIf { it.isNotEmpty() }
            ?: existingProfileId?.let { id ->
                db.aiProviderProfileDao().getById(id)?.let { entity ->
                    secretVault.decryptApiKey(entity.encryptedApiKeyB64, entity.apiKeyIvB64)
                }
            }
        return aiClient.verifyConnection(
            AiProviderConnectionConfig(
                providerType = providerType,
                baseUrl = baseUrl,
                modelId = modelId,
                apiKey = effectiveKey,
            ),
        )
    }

    suspend fun savePreset(
        presetId: Long? = null,
        title: String,
        goal: AiWritingGoal,
        tone: AiReplyTone,
        instructionPrompt: String,
    ): Long {
        val cleanTitle = title.trim()
        val cleanInstruction = instructionPrompt.trim()
        if (cleanTitle.isEmpty() || cleanInstruction.isEmpty()) {
            throw AiClientException(
                kind = AiClientException.Kind.INVALID_CONFIG,
                message = "テンプレート名とプロンプト指示を入力してください",
            )
        }
        val existing = presetId?.takeIf { it > 0L }?.let { db.aiWritingPresetDao().getById(it) }
        val now = clockMs()
        val nextSortOrder = existing?.sortOrder ?: ((db.aiWritingPresetDao().getAll().maxOfOrNull { it.sortOrder } ?: 0) + 1)
        return db.aiWritingPresetDao().upsert(
            AiWritingPresetEntity(
                presetId = existing?.presetId ?: 0L,
                title = cleanTitle,
                goal = goal.storageValue,
                tone = tone.storageValue,
                instructionPrompt = cleanInstruction,
                isBuiltIn = existing?.isBuiltIn ?: false,
                sortOrder = nextSortOrder,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun deletePreset(presetId: Long) {
        db.aiWritingPresetDao().deleteById(presetId)
    }

    suspend fun prepareEmailContext(
        accountId: Long,
        metaId: Long,
        threadId: String?,
        loadFullThread: Boolean,
    ): AiPreparedEmailContext {
        val account = db.accountDao().getById(accountId)
        val conversation = messageRepository.loadConversation(
            accountId = accountId,
            metaId = metaId,
            threadId = threadId,
            loadFullThread = loadFullThread,
        )
        return contextBuilder.buildThreadContext(
            conversation = conversation,
            selfEmail = account?.emailAddress.orEmpty(),
            stripQuotedReplies = appPreferences.aiStripQuotedReplies.value,
            redactSensitive = appPreferences.aiRedactSensitivePatterns.value,
            maxChars = appPreferences.aiMaxContextChars.value,
        )
    }

    fun buildPromptPreview(
        goal: AiWritingGoal,
        tone: AiReplyTone,
        context: AiPreparedEmailContext?,
        userInstruction: String,
        currentSubject: String,
        currentBodyDraft: String,
        extraReferenceEmails: List<AiPreparedEmailContext> = emptyList(),
        extraTextDocuments: List<AiPreparedTextDocument> = emptyList(),
        binaryAttachmentDescriptors: List<String> = emptyList(),
        clarificationAnswers: List<AiClarificationAnswer> = emptyList(),
        preferHtmlOutput: Boolean = false,
    ): AiCompletionRequest =
        AiPromptBuilder.buildDraftRequest(
            goal = goal,
            tone = tone,
            context = context,
            userInstruction = userInstruction,
            currentSubject = currentSubject,
            currentBodyDraft = currentBodyDraft,
            customSystemInstruction = appPreferences.aiCustomSystemInstruction.value,
            includeSignatureGuidance = appPreferences.aiIncludeSignatureGuidance.value,
            extraReferenceEmails = extraReferenceEmails,
            extraTextDocuments = extraTextDocuments,
            binaryAttachmentDescriptors = binaryAttachmentDescriptors,
            clarificationAnswers = clarificationAnswers,
            preferHtmlOutput = preferHtmlOutput,
        )

    fun buildClarificationPrompt(
        targetGoal: AiWritingGoal,
        tone: AiReplyTone,
        context: AiPreparedEmailContext?,
        userInstruction: String,
        currentSubject: String,
        currentBodyDraft: String,
        extraReferenceEmails: List<AiPreparedEmailContext> = emptyList(),
        extraTextDocuments: List<AiPreparedTextDocument> = emptyList(),
        binaryAttachmentDescriptors: List<String> = emptyList(),
        previousAnswers: List<AiClarificationAnswer> = emptyList(),
    ): AiCompletionRequest =
        AiPromptBuilder.buildClarificationQuestionsRequest(
            targetGoal = targetGoal,
            tone = tone,
            context = context,
            userInstruction = userInstruction,
            currentSubject = currentSubject,
            currentBodyDraft = currentBodyDraft,
            customSystemInstruction = appPreferences.aiCustomSystemInstruction.value,
            extraReferenceEmails = extraReferenceEmails,
            extraTextDocuments = extraTextDocuments,
            binaryAttachmentDescriptors = binaryAttachmentDescriptors,
            previousAnswers = previousAnswers,
        )

    suspend fun generateClarificationQuestions(
        request: AiCompletionRequest,
        referenceFiles: List<AiReferenceFileSpec> = emptyList(),
    ): AiClarificationPlan {
        val result = generateCompletion(request = request, referenceFiles = referenceFiles)
        return AiPromptBuilder.parseClarificationPlanResponse(result.text, resultMeta = result)
    }

    fun buildSubjectFromBodyPrompt(
        bodyDraft: String,
        currentSubject: String,
        tone: AiReplyTone,
        isReplyMode: Boolean,
        context: AiPreparedEmailContext?,
        extraReferenceEmails: List<AiPreparedEmailContext> = emptyList(),
        extraTextDocuments: List<AiPreparedTextDocument> = emptyList(),
        binaryAttachmentDescriptors: List<String> = emptyList(),
    ): AiCompletionRequest =
        AiPromptBuilder.buildSubjectFromBodyRequest(
            bodyDraft = bodyDraft,
            currentSubject = currentSubject,
            tone = tone,
            isReplyMode = isReplyMode,
            context = context,
            customSystemInstruction = appPreferences.aiCustomSystemInstruction.value,
            extraReferenceEmails = extraReferenceEmails,
            extraTextDocuments = extraTextDocuments,
            binaryAttachmentDescriptors = binaryAttachmentDescriptors,
            subjectCustomInstruction = appPreferences.aiSubjectCustomInstruction.value,
        )

    suspend fun generateSubjectSuggestions(
        request: AiCompletionRequest,
        referenceFiles: List<AiReferenceFileSpec> = emptyList(),
    ): AiSubjectSuggestionsResult {
        val result = generateCompletion(request = request, referenceFiles = referenceFiles)
        val subjects = AiPromptBuilder.parseSubjectSuggestionsResponse(result.text)
        if (subjects.isEmpty()) {
            throw AiClientException(
                kind = AiClientException.Kind.PARSE_ERROR,
                message = "AI から件名案を生成できませんでした。本文をもう少し書いてお試しください",
            )
        }
        return AiSubjectSuggestionsResult(
            subjects = subjects,
            providerType = result.providerType,
            modelId = result.modelId,
            latencyMs = result.latencyMs,
        )
    }

    fun buildSummaryPrompt(
        context: AiPreparedEmailContext?,
        subjectFallback: String,
        userInstruction: String = "",
        extraReferenceEmails: List<AiPreparedEmailContext> = emptyList(),
        extraTextDocuments: List<AiPreparedTextDocument> = emptyList(),
        binaryAttachmentDescriptors: List<String> = emptyList(),
    ): AiCompletionRequest =
        AiPromptBuilder.buildSummaryRequest(
            context = context,
            subjectFallback = subjectFallback,
            userInstruction = userInstruction,
            customSystemInstruction = appPreferences.aiCustomSystemInstruction.value,
            extraReferenceEmails = extraReferenceEmails,
            extraTextDocuments = extraTextDocuments,
            binaryAttachmentDescriptors = binaryAttachmentDescriptors,
        )

    suspend fun searchReferenceEmails(
        accountId: Long,
        query: String,
        excludeMetaIds: Set<Long> = emptySet(),
        limit: Int = 40,
    ): List<AiReferenceEmailSummary> {
        val cleanQuery = query.trim()
        val rawMatches: List<MessageMetaEntity> = if (cleanQuery.isEmpty()) {
            db.messageMetaDao().getRecentMessagesForPicker(
                accountId = accountId,
                limit = (limit + excludeMetaIds.size).coerceAtLeast(limit),
            )
        } else {
            val scopeAccountId = accountId.takeIf { it > 0L }
            val ftsResults = runCatching {
                messageRepository.searchLocal(cleanQuery, scopeAccountId, limit)
            }.getOrDefault(emptyList())
            val likeResults = db.messageMetaDao().searchMessagesByMetadataLike(
                accountId = accountId,
                likeQuery = cleanQuery,
                limit = limit,
            )
            (ftsResults + likeResults).distinctBy { it.id }
        }
        return rawMatches
            .asSequence()
            .filter { it.id !in excludeMetaIds }
            .take(limit)
            .map { it.toReferenceSummary() }
            .toList()
    }

    suspend fun loadReferenceEmailSummaries(metaIds: List<Long>): List<AiReferenceEmailSummary> {
        if (metaIds.isEmpty()) return emptyList()
        val distinctIds = metaIds.distinct()
        val byId = distinctIds.chunked(200)
            .flatMap { chunk -> db.messageMetaDao().getByIds(chunk) }
            .associateBy { it.id }
        return distinctIds.mapNotNull { id -> byId[id]?.toReferenceSummary() }
    }

    suspend fun prepareExtraEmailContexts(
        metaIds: List<Long>,
        fallbackSelfEmail: String,
    ): List<AiPreparedEmailContext> {
        val distinctIds = metaIds.distinct().take(MAX_EXTRA_REFERENCE_EMAILS)
        if (distinctIds.isEmpty()) return emptyList()

        val perEmailChars = (appPreferences.aiMaxContextChars.value / (distinctIds.size + 1))
            .coerceIn(1200, 3200)
        val redactSensitive = appPreferences.aiRedactSensitivePatterns.value
        val stripQuotes = appPreferences.aiStripQuotedReplies.value

        val out = mutableListOf<AiPreparedEmailContext>()
        for (id in distinctIds) {
            val meta = db.messageMetaDao().getById(id) ?: continue
            val account = db.accountDao().getById(meta.accountId)
            val conv = messageRepository.loadConversation(
                accountId = meta.accountId,
                metaId = id,
                threadId = null,
                loadFullThread = false,
            )
            if (conv.isEmpty()) continue
            out += contextBuilder.buildThreadContext(
                conversation = conv,
                selfEmail = account?.emailAddress ?: fallbackSelfEmail,
                stripQuotedReplies = stripQuotes,
                redactSensitive = redactSensitive,
                maxChars = perEmailChars,
                maxMessages = 1,
            )
        }
        return out
    }

    suspend fun prepareFileReferenceBundle(
        files: List<AiReferenceFileSpec>,
    ): AiPreparedReferenceBundle {
        if (files.isEmpty()) return AiPreparedReferenceBundle()
        return withContext(ioDispatcher) {

        val redactSensitive = appPreferences.aiRedactSensitivePatterns.value
        val textDocs = mutableListOf<AiPreparedTextDocument>()
        val binaryDescs = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var redactions = 0

        files.forEach { spec ->
            when (spec.kind) {
                AiReferenceFileKind.TEXT_DOCUMENT,
                AiReferenceFileKind.OFFICE_DOCUMENT,
                -> {
                    val doc = AiReferenceDocumentExtractor.extractTextDocument(
                        file = File(spec.localPath),
                        displayName = spec.displayName,
                        mimeType = spec.mimeType,
                        kind = spec.kind,
                        redactSensitive = redactSensitive,
                    )
                    if (doc != null) {
                        textDocs += doc
                        redactions += doc.redactionCount
                    } else {
                        skipped += "${spec.displayName}: テキスト抽出不可"
                    }
                }
                AiReferenceFileKind.IMAGE,
                AiReferenceFileKind.PDF,
                -> {
                    binaryDescs += "${spec.displayName} (${spec.mimeType}, ${formatReadableBytes(spec.sizeBytes)})"
                }
                AiReferenceFileKind.UNSUPPORTED -> {
                    skipped += "${spec.displayName}: 非対応形式"
                }
            }
        }

            AiPreparedReferenceBundle(
                inlineTextDocuments = textDocs,
                binaryDescriptorLabels = binaryDescs,
                skippedNotes = skipped,
                totalRedactions = redactions,
            )
        }
    }

    suspend fun generateCompletion(
        request: AiCompletionRequest,
        referenceFiles: List<AiReferenceFileSpec> = emptyList(),
    ): AiCompletionResult {
        if (!appPreferences.aiFeaturesEnabled.value) {
            throw AiClientException(
                kind = AiClientException.Kind.POLICY_BLOCKED,
                message = "AI 機能が無効です。AI 設定から有効にしてください",
            )
        }
        if (!appPreferences.aiPrivacyConsentAccepted.value) {
            throw AiClientException(
                kind = AiClientException.Kind.POLICY_BLOCKED,
                message = "外部 AI API への送信ポリシーへの同意が必要です。AI 設定を確認してください",
            )
        }
        val (profile, resolvedModelId) = resolveProfileAndModelForGoal(request.goal)
        if (!profile.isEnabled) {
            throw AiClientException(
                kind = AiClientException.Kind.INVALID_CONFIG,
                message = "使用する AI プロバイダ (${profile.displayName}) が無効になっています",
            )
        }
        val provider = AiProviderType.fromStorage(profile.providerType)
        val rawKey = secretVault.decryptApiKey(profile.encryptedApiKeyB64, profile.apiKeyIvB64)
        val sanitizedUrl = AiSecretVault.sanitizeEndpointUrl(provider, profile.baseUrl)

        val binarySpecs = referenceFiles.filter { it.kind.isBinarySupported }
        if (binarySpecs.size > AndroidAiBinaryMediaEncoder.MAX_BINARY_FILES) {
            throw AiClientException(
                kind = AiClientException.Kind.INVALID_CONFIG,
                message = "画像・PDF は 1 回につき最大 ${AndroidAiBinaryMediaEncoder.MAX_BINARY_FILES} 件まで添付できます",
            )
        }

        val encodedBinaries = if (binarySpecs.isNotEmpty()) {
            withContext(ioDispatcher) {
                var remainingBudget = AndroidAiBinaryMediaEncoder.MAX_TOTAL_BINARY_BYTES
                val collected = mutableListOf<AiBinaryAttachment>()
                for (spec in binarySpecs) {
                    val encodedList = binaryEncoder.encodeFile(
                        spec = spec,
                        providerType = provider,
                        baseUrl = sanitizedUrl,
                        remainingBudgetBytes = remainingBudget,
                    )
                    encodedList.forEach { item ->
                        remainingBudget -= item.byteSize
                        collected += item
                    }
                }
                collected
            }
        } else {
            emptyList()
        }

        val finalRequest = if (encodedBinaries.isNotEmpty()) {
            request.copy(attachments = request.attachments + encodedBinaries)
        } else {
            request
        }

        val config = AiProviderConnectionConfig(
            providerType = provider,
            baseUrl = sanitizedUrl,
            modelId = resolvedModelId,
            apiKey = rawKey,
        )
        val rawResult = aiClient.complete(config, finalRequest)
        val cleanedText = AiPromptBuilder.sanitizeGeneratedOutput(rawResult.text, finalRequest.goal)
        if (cleanedText.isBlank()) {
            val finishReason = rawResult.finishReason.orEmpty()
            val detailMsg = if (finishReason.equals("MAX_TOKENS", ignoreCase = true) ||
                finishReason.equals("length", ignoreCase = true)
            ) {
                "AI の内部推論トークンが上限 (${finalRequest.maxOutputTokens}) に達し応答が空でした。別モデルを指定するか再度お試しください (${provider.displayLabel} / $resolvedModelId)"
            } else {
                "AI から空の応答が返されました。指示を変えてもう一度お試しください"
            }
            throw AiClientException(
                kind = AiClientException.Kind.PARSE_ERROR,
                message = detailMsg,
            )
        }
        return rawResult.copy(text = cleanedText)
    }

    suspend fun testSubjectConfigurationConnection(): AiConnectionTestResult {
        val (profile, resolvedModelId) = resolveProfileAndModelForGoal(AiWritingGoal.GENERATE_SUBJECT)
        val provider = AiProviderType.fromStorage(profile.providerType)
        val rawKey = secretVault.decryptApiKey(profile.encryptedApiKeyB64, profile.apiKeyIvB64)
        return aiClient.verifyConnection(
            AiProviderConnectionConfig(
                providerType = provider,
                baseUrl = profile.baseUrl,
                modelId = resolvedModelId,
                apiKey = rawKey,
            ),
        )
    }

    private suspend fun resolveProfileAndModelForGoal(goal: AiWritingGoal): Pair<AiProviderProfileEntity, String> {
        val defaultProfile = db.aiProviderProfileDao().getDefaultProfile()
            ?: db.aiProviderProfileDao().getAll().firstOrNull()
        if (goal == AiWritingGoal.GENERATE_SUBJECT) {
            val subjectProfileId = appPreferences.aiSubjectProfileId.value
            val selectedProfile = if (subjectProfileId != null && subjectProfileId > 0L) {
                db.aiProviderProfileDao().getById(subjectProfileId) ?: defaultProfile
            } else {
                defaultProfile
            } ?: throw AiClientException(
                kind = AiClientException.Kind.INVALID_CONFIG,
                message = "AI プロバイダが登録されていません。AI 設定から登録してください",
            )
            val modelOverride = appPreferences.aiSubjectModelOverride.value.trim()
                .takeIf { it.isNotEmpty() }
            return selectedProfile to (modelOverride ?: selectedProfile.modelId)
        }
        val profile = defaultProfile ?: throw AiClientException(
            kind = AiClientException.Kind.INVALID_CONFIG,
            message = "AI プロバイダが登録されていません。AI 設定から登録してください",
        )
        return profile to profile.modelId
    }

    private fun MessageMetaEntity.toReferenceSummary(): AiReferenceEmailSummary {
        val decodedSenderName = senderName?.let { Rfc2047HeaderDecoder.decodeIfNeeded(it) }
        val senderLabel = decodedSenderName?.takeIf { it.isNotBlank() }?.let { "$it <$senderAddress>" }
            ?: senderAddress
        val decodedSubject = subject?.let { Rfc2047HeaderDecoder.decodeIfNeeded(it) }?.takeIf { it.isNotBlank() }
            ?: "(件名なし)"
        return AiReferenceEmailSummary(
            metaId = id,
            accountId = accountId,
            senderLabel = senderLabel,
            subject = decodedSubject,
            snippet = snippet?.trim().orEmpty(),
            receivedAt = receivedAt,
            hasAttachments = hasAttachments,
        )
    }

    private fun formatReadableBytes(bytes: Long): String =
        when {
            bytes < 1024L -> "${bytes}B"
            bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
            else -> String.format(java.util.Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
        }

    companion object {
        const val MAX_EXTRA_REFERENCE_EMAILS = 5
    }

    private fun evaluateReadiness(
        featuresEnabled: Boolean,
        privacyConsentAccepted: Boolean,
        activeProfile: AiProviderProfileEntity?,
    ): AiReadinessState {
        if (!featuresEnabled) {
            return AiReadinessState(
                status = AiReadinessStatus.FEATURE_DISABLED,
                activeProfileId = activeProfile?.profileId,
                activeProfileName = activeProfile?.displayName,
                providerType = activeProfile?.let { AiProviderType.fromStorage(it.providerType) },
                modelId = activeProfile?.modelId,
                maskedKeyHint = activeProfile?.apiKeyHint,
                lastTestStatus = activeProfile?.let { AiConnectionTestStatus.fromStorage(it.lastTestStatus) },
                summaryLabel = "AI機能はオフです",
                actionHint = "AI設定から「AI機能を有効にする」をオンにしてください",
            )
        }
        if (!privacyConsentAccepted) {
            return AiReadinessState(
                status = AiReadinessStatus.CONSENT_REQUIRED,
                activeProfileId = activeProfile?.profileId,
                activeProfileName = activeProfile?.displayName,
                providerType = activeProfile?.let { AiProviderType.fromStorage(it.providerType) },
                modelId = activeProfile?.modelId,
                maskedKeyHint = activeProfile?.apiKeyHint,
                lastTestStatus = activeProfile?.let { AiConnectionTestStatus.fromStorage(it.lastTestStatus) },
                summaryLabel = "外部送信ポリシーへの同意が必要です",
                actionHint = "AI設定画面でメール文脈の外部API送信ポリシーを確認・同意してください",
            )
        }
        if (activeProfile == null) {
            return AiReadinessState(
                status = AiReadinessStatus.NO_PROFILE,
                summaryLabel = "AIプロバイダ未登録",
                actionHint = "Gemini / OpenAI / Claude などの API キーとモデルを登録してください",
            )
        }

        val provider = AiProviderType.fromStorage(activeProfile.providerType)
        val lastTest = AiConnectionTestStatus.fromStorage(activeProfile.lastTestStatus)
        if (!activeProfile.isEnabled) {
            return AiReadinessState(
                status = AiReadinessStatus.PROFILE_DISABLED,
                activeProfileId = activeProfile.profileId,
                activeProfileName = activeProfile.displayName,
                providerType = provider,
                modelId = activeProfile.modelId,
                maskedKeyHint = activeProfile.apiKeyHint,
                lastTestStatus = lastTest,
                summaryLabel = "標準プロバイダが無効化されています",
                actionHint = "AI設定で有効な標準プロバイダを選択してください",
            )
        }
        if (provider.requiresApiKey && activeProfile.encryptedApiKeyB64.isNullOrBlank()) {
            return AiReadinessState(
                status = AiReadinessStatus.MISSING_API_KEY,
                activeProfileId = activeProfile.profileId,
                activeProfileName = activeProfile.displayName,
                providerType = provider,
                modelId = activeProfile.modelId,
                maskedKeyHint = activeProfile.apiKeyHint,
                lastTestStatus = lastTest,
                summaryLabel = "${provider.displayLabel} の API キー未設定",
                actionHint = "AI設定で Keystore 暗号化保存用の API キーを設定してください",
            )
        }
        if (!AiSecretVault.isEndpointValid(provider, activeProfile.baseUrl)) {
            return AiReadinessState(
                status = AiReadinessStatus.INVALID_ENDPOINT,
                activeProfileId = activeProfile.profileId,
                activeProfileName = activeProfile.displayName,
                providerType = provider,
                modelId = activeProfile.modelId,
                maskedKeyHint = activeProfile.apiKeyHint,
                lastTestStatus = lastTest,
                summaryLabel = "エンドポイントURL要確認",
                actionHint = "AI設定で有効な https:// エンドポイントを指定してください",
            )
        }

        val testSuffix = when (lastTest) {
            AiConnectionTestStatus.SUCCESS -> "（接続確認済み）"
            AiConnectionTestStatus.FAILED -> "（前回接続テスト失敗）"
            AiConnectionTestStatus.UNTESTED -> "（接続未テスト）"
        }
        return AiReadinessState(
            status = AiReadinessStatus.READY,
            activeProfileId = activeProfile.profileId,
            activeProfileName = activeProfile.displayName,
            providerType = provider,
            modelId = activeProfile.modelId,
            maskedKeyHint = activeProfile.apiKeyHint,
            lastTestStatus = lastTest,
            summaryLabel = "${provider.displayLabel} / ${activeProfile.modelId} $testSuffix",
            actionHint = if (lastTest == AiConnectionTestStatus.SUCCESS) {
                "AI での返信・下書き生成およびメール要約が利用できます"
            } else {
                "このまま生成も試せますが、先に AI 設定で「接続テスト」を行うと安心です"
            },
        )
    }

    private fun AiProviderProfileEntity.toSummary(): AiProviderProfileSummary =
        AiProviderProfileSummary(
            profileId = profileId,
            displayName = displayName,
            providerType = AiProviderType.fromStorage(providerType),
            baseUrl = baseUrl,
            modelId = modelId,
            hasStoredApiKey = !encryptedApiKeyB64.isNullOrBlank() && !apiKeyIvB64.isNullOrBlank(),
            apiKeyHint = apiKeyHint,
            isDefault = isDefault,
            isEnabled = isEnabled,
            lastTestStatus = AiConnectionTestStatus.fromStorage(lastTestStatus),
            lastTestMessage = lastTestMessage,
            lastTestedAt = lastTestedAt,
            updatedAt = updatedAt,
        )
}
