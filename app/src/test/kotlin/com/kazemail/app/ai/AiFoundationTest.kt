package com.kazemail.app.ai

import android.content.SharedPreferences
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kazemail.app.data.AppPreferences
import com.kazemail.app.data.MessageRepository
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.db.AttachmentEntity
import com.kazemail.app.data.db.FolderEntity
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.KazeMailDatabase_Impl
import com.kazemail.app.data.db.MessageBodyEntity
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.security.AccountCredentialStore
import com.kazemail.app.security.EncryptedBlob
import com.kazemail.app.security.SecretCipher
import com.kazemail.app.sync.CoreImapConfig
import com.kazemail.app.sync.ImapSessionHandle
import com.kazemail.app.sync.MailCoreEngine
import com.kazemail.app.sync.NonDestructiveSyncEngine
import com.kazemail.app.sync.OAuth2TokenRefresher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class AiFoundationTest {

    private lateinit var db: KazeMailDatabase
    private lateinit var messageRepository: MessageRepository
    private lateinit var appPreferences: AppPreferences
    private lateinit var secretVault: AiSecretVault

    private val fakeCipher = object : SecretCipher {
        override fun encrypt(plaintext: ByteArray): EncryptedBlob {
            // Simple reversible XOR+Base64 obfuscation for JVM unit testing without AndroidKeyStore
            val xored = ByteArray(plaintext.size) { idx -> (plaintext[idx].toInt() xor 0x5A).toByte() }
            return EncryptedBlob(
                ciphertextB64 = Base64.getEncoder().encodeToString(xored),
                ivB64 = "TEST_IV_01",
            )
        }

        override fun decrypt(blob: EncryptedBlob): ByteArray {
            val decoded = Base64.getDecoder().decode(blob.ciphertextB64)
            return ByteArray(decoded.size) { idx -> (decoded[idx].toInt() xor 0x5A).toByte() }
        }
    }

    @Before
    fun setUp() {
        val builder = Room.inMemoryDatabaseBuilder<KazeMailDatabase>(
            factory = { KazeMailDatabase_Impl() },
        )
        db = KazeMailDatabase.buildWithDriver(builder, BundledSQLiteDriver())
        val mailCore = object : MailCoreEngine {
            override suspend fun connect(config: CoreImapConfig): ImapSessionHandle = error("unused")
            override suspend fun decompressHtmlZstd(compressed: ByteArray, maxOutputBytes: Long): String =
                compressed.decodeToString()
        }
        val syncEngine = NonDestructiveSyncEngine(
            db = db,
            credentialStore = AccountCredentialStore(db.accountSecretDao(), fakeCipher),
            mailCore = mailCore,
            tokenRefresher = OAuth2TokenRefresher { _, _ -> error("unused") },
        )
        messageRepository = MessageRepository(db, mailCore, syncEngine)
        appPreferences = AppPreferences(InMemorySharedPreferences())
        secretVault = AiSecretVault(fakeCipher)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun secretVaultEncryptsMasksAndValidatesEndpointsSafely() {
        val raw = "AIzaSyTestSecretKey987654"
        val encrypted = secretVault.encryptApiKey("  $raw  ")
        assertNotNull(encrypted)
        assertFalse(encrypted!!.ciphertextB64.contains(raw))
        assertEquals(raw, secretVault.decryptApiKey(encrypted.ciphertextB64, encrypted.ivB64))
        assertEquals("AIza••••7654", AiSecretVault.maskApiKey(raw))
        assertEquals("••••", AiSecretVault.maskApiKey("abcd"))
        assertEquals("••••ef", AiSecretVault.maskApiKey("abcdef"))

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            AiSecretVault.sanitizeEndpointUrl(AiProviderType.GEMINI, " https://generativelanguage.googleapis.com/v1beta/ "),
        )
        assertEquals(
            "http://127.0.0.1:11434/v1",
            AiSecretVault.sanitizeEndpointUrl(AiProviderType.OPENAI_COMPATIBLE, "http://127.0.0.1:11434/v1"),
        )

        val remoteHttpBlocked = runCatching {
            AiSecretVault.sanitizeEndpointUrl(AiProviderType.OPENAI, "http://api.openai.com/v1")
        }.exceptionOrNull() as? AiClientException
        assertEquals(AiClientException.Kind.INVALID_CONFIG, remoteHttpBlocked?.kind)

        val queryRejected = runCatching {
            AiSecretVault.sanitizeEndpointUrl(
                AiProviderType.GEMINI,
                "https://generativelanguage.googleapis.com/v1beta?key=leak",
            )
        }.exceptionOrNull() as? AiClientException
        assertEquals(AiClientException.Kind.INVALID_CONFIG, queryRejected?.kind)
    }

    @Test
    fun multiProviderClientFormatsHeadersEndpointsAndParsesAllProvidersAndErrors() = runTest {
        val recordedRequests = mutableListOf<AiHttpRequest>()
        var nextStatus = 200
        var nextBody = ""

        val client = MultiProviderAiClient(
            transport = object : AiHttpTransport {
                override suspend fun execute(request: AiHttpRequest): AiHttpResponse {
                    recordedRequests += request
                    return AiHttpResponse(statusCode = nextStatus, body = nextBody, latencyMs = 42L)
                }
            },
        )

        // 1. Gemini 4 uses header auth, strips models/ prefix, omits deprecated temperature,
        // uses low thinkingLevel on probes, and ignores thought parts in response
        nextBody = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    {"thought": true, "text": "internal thought summary"},
                    {"text": "OK"}
                  ]
                },
                "finishReason": "STOP"
              }],
              "usageMetadata": {"promptTokenCount": 12, "candidatesTokenCount": 4}
            }
        """.trimIndent()
        val geminiRes = client.verifyConnection(
            AiProviderConnectionConfig(
                providerType = AiProviderType.GEMINI,
                baseUrl = "https://generativelanguage.googleapis.com",
                modelId = "models/gemini-4-hydrogen",
                apiKey = "AIzaSecret1234567890",
            ),
        )
        assertTrue(geminiRes.success)
        assertEquals("OK", geminiRes.sampleReply)
        val geminiHttp = recordedRequests.last()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-4-hydrogen:generateContent",
            geminiHttp.url,
        )
        assertEquals("AIzaSecret1234567890", geminiHttp.headers["x-goog-api-key"])
        assertFalse(geminiHttp.url.contains("AIzaSecret"))
        assertFalse(geminiHttp.bodyJson.orEmpty().contains("\"temperature\""))
        assertTrue(geminiHttp.bodyJson.orEmpty().contains("\"thinkingLevel\":\"low\""))

        // 2. OpenAI & compatible endpoint support optional key cleanly
        nextBody = """
            {
              "model": "qwen2.5:14b",
              "choices": [{"message": {"content": "承知いたしました。"}, "finish_reason": "stop"}],
              "usage": {"prompt_tokens": 30, "completion_tokens": 11}
            }
        """.trimIndent()
        val compatResult = client.complete(
            AiProviderConnectionConfig(
                providerType = AiProviderType.OPENAI_COMPATIBLE,
                baseUrl = "http://localhost:11434/v1",
                modelId = "qwen2.5:14b",
                apiKey = null,
            ),
            AiCompletionRequest(
                goal = AiWritingGoal.REPLY,
                systemPrompt = "system",
                userPrompt = "user",
            ),
        )
        assertEquals("承知いたしました。", compatResult.text)
        assertEquals(30, compatResult.inputTokens)
        val compatHttp = recordedRequests.last()
        assertEquals("http://localhost:11434/v1/chat/completions", compatHttp.url)
        assertNull(compatHttp.headers["Authorization"])
        assertTrue(compatHttp.bodyJson.orEmpty().contains("\"max_tokens\":"))
        assertFalse(compatHttp.bodyJson.orEmpty().contains("\"max_completion_tokens\""))

        // 3. Anthropic 2026-10-21 headers, response blocks, and no forbidden temperature
        nextBody = """
            {
              "model": "claude-sonnet-5-5",
              "stop_reason": "end_turn",
              "content": [
                {"type": "thinking", "thinking": "reasoning"},
                {"type": "text", "text": "ご確認ありがとうございます。"}
              ],
              "usage": {"input_tokens": 25, "output_tokens": 9}
            }
        """.trimIndent()
        val claudeResult = client.complete(
            AiProviderConnectionConfig(
                providerType = AiProviderType.ANTHROPIC,
                baseUrl = "https://api.anthropic.com",
                modelId = "claude-sonnet-5-5",
                apiKey = "sk-ant-test-999999",
            ),
            AiCompletionRequest(
                goal = AiWritingGoal.REPLY,
                systemPrompt = "sys",
                userPrompt = "usr",
            ),
        )
        assertEquals("ご確認ありがとうございます。", claudeResult.text)
        val claudeHttp = recordedRequests.last()
        assertEquals("https://api.anthropic.com/v1/messages", claudeHttp.url)
        assertEquals("sk-ant-test-999999", claudeHttp.headers["x-api-key"])
        assertEquals("2026-10-21", claudeHttp.headers["anthropic-version"])
        assertFalse(claudeHttp.bodyJson.orEmpty().contains("\"temperature\""))

        // 4. OpenAI official GPT-6 uses max_completion_tokens + reasoning_effort=low (no temperature),
        // and maps 401 -> AUTH_FAILED
        nextStatus = 401
        nextBody = """{"error":{"message":"Incorrect API key provided"}}"""
        val failCheck = client.verifyConnection(
            AiProviderConnectionConfig(
                providerType = AiProviderType.OPENAI,
                baseUrl = "https://api.openai.com/v1",
                modelId = "gpt-6-luna",
                apiKey = "sk-bad-key",
            ),
        )
        assertFalse(failCheck.success)
        assertEquals(401, failCheck.httpStatus)
        assertTrue(failCheck.message.contains("Incorrect API key provided"))
        val openaiHttp = recordedRequests.last()
        assertTrue(openaiHttp.bodyJson.orEmpty().contains("\"max_completion_tokens\":512"))
        assertTrue(openaiHttp.bodyJson.orEmpty().contains("\"reasoning_effort\":\"low\""))
        assertFalse(openaiHttp.bodyJson.orEmpty().contains("\"max_tokens\""))
        assertFalse(openaiHttp.bodyJson.orEmpty().contains("\"temperature\""))

        // 5. Legacy Gemini 2.5 & OpenAI GPT-4 still preserve sampling temperature cleanly
        nextStatus = 200
        nextBody = """{"candidates":[{"content":{"parts":[{"text":"了解です"}]},"finishReason":"STOP"}]}"""
        client.complete(
            AiProviderConnectionConfig(
                providerType = AiProviderType.GEMINI,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                modelId = "gemini-2.5-flash",
                apiKey = "AIzaLegacy1234567890",
            ),
            AiCompletionRequest(
                goal = AiWritingGoal.REPLY,
                systemPrompt = "sys",
                userPrompt = "usr",
                temperature = 0.25,
            ),
        )
        assertTrue(recordedRequests.last().bodyJson.orEmpty().contains("\"temperature\":0.25"))

        nextBody = """{"choices":[{"message":{"content":"Legacy OK"},"finish_reason":"stop"}]}"""
        client.complete(
            AiProviderConnectionConfig(
                providerType = AiProviderType.OPENAI,
                baseUrl = "https://api.openai.com/v1",
                modelId = "gpt-4o-mini",
                apiKey = "sk-legacy-1234567890",
            ),
            AiCompletionRequest(
                goal = AiWritingGoal.REPLY,
                systemPrompt = "sys",
                userPrompt = "usr",
                temperature = 0.25,
            ),
        )
        val legacyOpenAiJson = recordedRequests.last().bodyJson.orEmpty()
        assertTrue(legacyOpenAiJson.contains("\"max_completion_tokens\":"))
        assertTrue(legacyOpenAiJson.contains("\"temperature\":0.25"))
    }

    @Test
    fun contextBuilderAndRedactorSanitizeThreadsAndBuildPrompt() = runTest {
        val rawText = """
            お世話になっております、佐藤です。
            認証コード: 849201 を入力して、
            https://example.jp/login?token=super_secret_token&next=home から確認をお願いします。
            カード番号は 4111-1111-1111-1234 ではありません。
            送信鍵 sk-proj-abcdefghijklmnop1234 は共有禁止です。

            On 2026/11/20, Tanaka wrote:
            > 以前の長い引用文1
            > 以前の長い引用文2
        """.trimIndent()

        val redacted = AiSensitiveDataRedactor.redact(rawText)
        assertTrue(redacted.redactionCount >= 4)
        assertFalse(redacted.text.contains("849201"))
        assertFalse(redacted.text.contains("super_secret_token"))
        assertFalse(redacted.text.contains("4111-1111-1111-1234"))
        assertFalse(redacted.text.contains("sk-proj-abcdefghijklmnop1234"))
        assertTrue(redacted.text.contains("https://example.jp/login?token=[REDACTED]"))

        val accId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "me@kazemail.app",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                authType = "PASSWORD",
            ),
        )
        val folderId = db.folderDao().insert(
            FolderEntity(
                accountId = accId,
                folderName = "INBOX",
                fullPath = "INBOX",
                uidValidity = 1L,
            ),
        )
        val m1 = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accId,
                folderId = folderId,
                uid = 1L,
                threadId = "thr-ai-1",
                senderName = "佐藤",
                senderAddress = "sato@example.jp",
                subject = "打ち合わせ候補について",
                receivedAt = 10_000L,
                hasAttachments = true,
            ),
        )
        db.messageBodyDao().upsert(
            MessageBodyEntity(
                metaId = m1,
                plainText = rawText,
            ),
        )
        db.attachmentDao().insertAll(
            listOf(
                AttachmentEntity(
                    metaId = m1,
                    partId = "2",
                    filename = "proposal.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 120_000L,
                ),
            ),
        )

        appPreferences.setAiFeaturesEnabled(true)
        appPreferences.setAiPrivacyConsentAccepted(true)

        val service = AiPreparationService(
            db = db,
            appPreferences = appPreferences,
            secretVault = secretVault,
            aiClient = MultiProviderAiClient(transport = object : AiHttpTransport {
                override suspend fun execute(request: AiHttpRequest) = AiHttpResponse(200, "{}", 1L)
            }),
            messageRepository = messageRepository,
        )

        val prepared = service.prepareEmailContext(
            accountId = accId,
            metaId = m1,
            threadId = "thr-ai-1",
            loadFullThread = true,
        )
        assertEquals(1, prepared.includedMessageCount)
        assertEquals(1, prepared.strippedQuoteBlocks)
        assertTrue(prepared.redactionCount >= 4)
        assertTrue(prepared.formattedContext.contains("proposal.pdf (application/pdf)"))
        assertFalse(prepared.formattedContext.contains("以前の長い引用文1"))
        assertFalse(prepared.formattedContext.contains("super_secret_token"))

        val promptReq = service.buildPromptPreview(
            goal = AiWritingGoal.REPLY,
            tone = AiReplyTone.FORMAL_BUSINESS,
            context = prepared,
            userInstruction = "来週火曜14時で承諾する",
            currentSubject = "Re: 打ち合わせ候補について",
            currentBodyDraft = "",
        )
        assertTrue(promptReq.systemPrompt.contains("署名ブロック"))
        assertFalse(appPreferences.aiDefaultPreferHtml.value)
        assertTrue(promptReq.systemPrompt.contains("プレーンテキスト形式のみで出力"))
        assertFalse(promptReq.systemPrompt.contains("HTML メール用本文フラグメント"))
        assertTrue(promptReq.userPrompt.contains("来週火曜14時で承諾する"))
        assertTrue(promptReq.userPrompt.contains("打ち合わせ候補について"))

        val htmlPromptReq = service.buildPromptPreview(
            goal = AiWritingGoal.REPLY,
            tone = AiReplyTone.FORMAL_BUSINESS,
            context = prepared,
            userInstruction = "来週火曜14時で承諾する",
            currentSubject = "Re: 打ち合わせ候補について",
            currentBodyDraft = "",
            preferHtmlOutput = true,
        )
        assertTrue(htmlPromptReq.systemPrompt.contains("HTML メール用本文フラグメント"))
        assertFalse(htmlPromptReq.systemPrompt.contains("プレーンテキスト形式のみで出力"))

        appPreferences.setAiDefaultPreferHtml(true)
        assertTrue(appPreferences.aiDefaultPreferHtml.value)
        appPreferences.setAiDefaultPreferHtml(false)
        assertFalse(appPreferences.aiDefaultPreferHtml.value)
    }

    @Test
    fun preparationServiceManagesReadinessEncryptedKeysTestsAndPresets() = runTest {
        val fakeClient = MultiProviderAiClient(
            transport = object : AiHttpTransport {
                override suspend fun execute(request: AiHttpRequest) = AiHttpResponse(
                    statusCode = 200,
                    body = """{"candidates":[{"content":{"parts":[{"text":"OK"}]},"finishReason":"STOP"}]}""",
                    latencyMs = 28L,
                )
            },
        )
        val service = AiPreparationService(
            db = db,
            appPreferences = appPreferences,
            secretVault = secretVault,
            aiClient = fakeClient,
            messageRepository = messageRepository,
            clockMs = { 1_750_000_000_000L },
        )

        // Initially disabled
        assertEquals(AiReadinessStatus.FEATURE_DISABLED, service.getReadinessState().status)

        // Enabling consent without profile -> NO_PROFILE
        appPreferences.setAiPrivacyConsentAccepted(true)
        appPreferences.setAiFeaturesEnabled(true)
        assertEquals(AiReadinessStatus.NO_PROFILE, service.getReadinessState().status)

        // Seed built-in presets idempotently
        service.ensureDefaultPresetsSeeded()
        service.ensureDefaultPresetsSeeded()
        val presets = service.observePresets().first()
        assertEquals(4, presets.size)

        // Save Gemini profile with raw key -> stored encrypted in DB, masked in summary
        val profileId = service.saveProfile(
            displayName = "メイン Gemini",
            providerType = AiProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelId = "gemini-4-hydrogen",
            rawApiKeyInput = "AIzaRealKeySample998877",
            setAsDefault = true,
        )
        val entity = db.aiProviderProfileDao().getById(profileId)!!
        assertNotEquals("AIzaRealKeySample998877", entity.encryptedApiKeyB64)
        assertEquals("AIza••••8877", entity.apiKeyHint)
        assertTrue(entity.isDefault)

        // Edit without re-entering API key preserves stored key & resets test status only when model changes
        service.saveProfile(
            profileId = profileId,
            displayName = "メイン Gemini 更新",
            providerType = AiProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelId = "gemini-4-neon",
            rawApiKeyInput = "",
            keepExistingKeyIfBlank = true,
            setAsDefault = true,
        )
        val updatedEntity = db.aiProviderProfileDao().getById(profileId)!!
        assertEquals("AIzaRealKeySample998877", secretVault.decryptApiKey(updatedEntity.encryptedApiKeyB64, updatedEntity.apiKeyIvB64))
        assertEquals(AiConnectionTestStatus.UNTESTED.storageValue, updatedEntity.lastTestStatus)

        // Test connection persists SUCCESS in DB and reflects in READY readiness state
        val testRes = service.testSavedProfileConnection(profileId)
        assertTrue(testRes.success)
        val readiness = service.observeReadinessState().first()
        assertEquals(AiReadinessStatus.READY, readiness.status)
        assertEquals(AiConnectionTestStatus.SUCCESS, readiness.lastTestStatus)
        assertTrue(readiness.summaryLabel.contains("gemini-4-neon"))

        // Add second profile as non-default, then delete default -> second promotes cleanly
        val secondId = service.saveProfile(
            displayName = "OpenAI サブ",
            providerType = AiProviderType.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            modelId = "gpt-6-luna",
            rawApiKeyInput = "sk-test-1234567890",
            setAsDefault = false,
        )
        service.deleteProfile(profileId)
        val promoted = db.aiProviderProfileDao().getById(secondId)!!
        assertTrue(promoted.isDefault)
    }

    @Test
    fun preparationServiceEnforcesConsentDecryptsKeySanitizesOutputAndBuildsSummary() = runTest {
        var nextCompletionText = """
            ```text
            お世話になっております。
            来週火曜14時で問題ございません。よろしくお願いいたします。
            ```
        """.trimIndent()
        var lastConfig: AiProviderConnectionConfig? = null
        var lastRequest: AiCompletionRequest? = null

        val mockClient = object : AiClient {
            override suspend fun verifyConnection(config: AiProviderConnectionConfig) =
                AiConnectionTestResult(true, config.providerType, config.modelId, 50L, 200, "OK", "OK")

            override suspend fun complete(
                config: AiProviderConnectionConfig,
                request: AiCompletionRequest,
            ): AiCompletionResult {
                lastConfig = config
                lastRequest = request
                return AiCompletionResult(
                    text = nextCompletionText,
                    providerType = config.providerType,
                    modelId = config.modelId,
                    latencyMs = 120L,
                    inputTokens = 100,
                    outputTokens = 40,
                    finishReason = "STOP",
                )
            }
        }

        val service = AiPreparationService(
            db = db,
            appPreferences = appPreferences,
            secretVault = secretVault,
            aiClient = mockClient,
            messageRepository = messageRepository,
        )

        val summaryReq = service.buildSummaryPrompt(
            context = AiPreparedEmailContext(
                threadSubject = "スケジュール確定のご連絡",
                selfEmail = "me@example.jp",
                replyToAddress = "sato@example.jp",
                includedMessageCount = 2,
                totalMessageCount = 2,
                strippedQuoteBlocks = 1,
                redactionCount = 1,
                wasTruncated = false,
                formattedContext = "佐藤より: 来週火曜14時に打合せをお願いします。",
            ),
            subjectFallback = "件名フォールバック",
            userInstruction = "期限を明記",
        )
        assertEquals(AiWritingGoal.SUMMARIZE, summaryReq.goal)
        assertTrue(summaryReq.userPrompt.contains("スケジュール確定のご連絡"))
        assertTrue(summaryReq.userPrompt.contains("期限を明記"))

        // Disabled feature gate throws POLICY_BLOCKED before any client call
        val disabledErr = runCatching { service.generateCompletion(summaryReq) }.exceptionOrNull() as? AiClientException
        assertEquals(AiClientException.Kind.POLICY_BLOCKED, disabledErr?.kind)
        assertNull(lastConfig)

        appPreferences.setAiFeaturesEnabled(true)
        val consentErr = runCatching { service.generateCompletion(summaryReq) }.exceptionOrNull() as? AiClientException
        assertEquals(AiClientException.Kind.POLICY_BLOCKED, consentErr?.kind)
        assertNull(lastConfig)

        appPreferences.setAiPrivacyConsentAccepted(true)
        service.saveProfile(
            displayName = "Gemini Main",
            providerType = AiProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelId = "gemini-4-hydrogen",
            rawApiKeyInput = "AIzaSecretLiveKey12345",
            setAsDefault = true,
        )

        val draftReq = service.buildPromptPreview(
            goal = AiWritingGoal.REPLY,
            tone = AiReplyTone.FORMAL_BUSINESS,
            context = null,
            userInstruction = "承諾の返信",
            currentSubject = "Re: 打合せ",
            currentBodyDraft = "",
        )
        val generated = service.generateCompletion(draftReq)
        assertEquals("AIzaSecretLiveKey12345", lastConfig?.apiKey)
        assertEquals("gemini-4-hydrogen", lastConfig?.modelId)
        assertEquals(AiWritingGoal.REPLY, lastRequest?.goal)
        // Markdown code fence stripped cleanly
        assertFalse(generated.text.contains("```"))
        assertEquals("お世話になっております。\n来週火曜14時で問題ございません。よろしくお願いいたします。", generated.text)

        // Blank output after trimming fails with PARSE_ERROR rather than inserting empty body
        nextCompletionText = "   \n  "
        val blankErr = runCatching { service.generateCompletion(draftReq) }.exceptionOrNull() as? AiClientException
        assertEquals(AiClientException.Kind.PARSE_ERROR, blankErr?.kind)
    }

    @Test
    fun multiProviderClientUsesLowThinkingEffortForSubjectGeneration() = runTest {
        val recordedRequests = mutableListOf<AiHttpRequest>()
        var nextBody = """{"candidates":[{"content":{"parts":[{"text":"{\"subjects\":[\"打合せ日程のご確認\"]}"}]},"finishReason":"STOP"}]}"""
        val client = MultiProviderAiClient(
            transport = object : AiHttpTransport {
                override suspend fun execute(request: AiHttpRequest): AiHttpResponse {
                    recordedRequests += request
                    return AiHttpResponse(statusCode = 200, body = nextBody, latencyMs = 18L)
                }
            },
        )

        val geminiResult = client.complete(
            AiProviderConnectionConfig(
                providerType = AiProviderType.GEMINI,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                modelId = "gemini-4-neon",
                apiKey = "AIzaSubjectTest12345",
            ),
            AiCompletionRequest(
                goal = AiWritingGoal.GENERATE_SUBJECT,
                systemPrompt = "sys",
                userPrompt = "body",
                maxOutputTokens = 2048,
            ),
        )
        assertTrue(geminiResult.text.contains("打合せ日程のご確認"))
        val geminiJson = recordedRequests.last().bodyJson.orEmpty()
        assertTrue(geminiJson.contains("\"thinkingLevel\":\"low\""))
        assertTrue(geminiJson.contains("\"maxOutputTokens\":2048"))
        assertFalse(geminiJson.contains("\"temperature\""))

        nextBody = """{"model":"gpt-6-luna","choices":[{"message":{"content":"{\"subjects\":[\"日程調整のお願い\"]}"},"finish_reason":"stop"}]}"""
        client.complete(
            AiProviderConnectionConfig(
                providerType = AiProviderType.OPENAI,
                baseUrl = "https://api.openai.com/v1",
                modelId = "gpt-6-luna",
                apiKey = "sk-subject-12345",
            ),
            AiCompletionRequest(
                goal = AiWritingGoal.GENERATE_SUBJECT,
                systemPrompt = "sys",
                userPrompt = "body",
                maxOutputTokens = 2048,
            ),
        )
        val openAiJson = recordedRequests.last().bodyJson.orEmpty()
        assertTrue(openAiJson.contains("\"reasoning_effort\":\"low\""))
        assertTrue(openAiJson.contains("\"max_completion_tokens\":2048"))
        assertFalse(openAiJson.contains("\"temperature\""))
    }

    @Test
    fun preparationServiceUsesSeparateSubjectProfileModelAndPromptAndExplainsMaxTokens() = runTest {
        var nextCompletionText = """{"subjects":["【重要】契約更新について"]}"""
        var nextFinishReason: String? = "STOP"
        var lastCompleteConfig: AiProviderConnectionConfig? = null
        var lastVerifiedConfig: AiProviderConnectionConfig? = null

        val mockClient = object : AiClient {
            override suspend fun verifyConnection(config: AiProviderConnectionConfig): AiConnectionTestResult {
                lastVerifiedConfig = config
                return AiConnectionTestResult(true, config.providerType, config.modelId, 25L, 200, "OK", "OK")
            }

            override suspend fun complete(
                config: AiProviderConnectionConfig,
                request: AiCompletionRequest,
            ): AiCompletionResult {
                lastCompleteConfig = config
                return AiCompletionResult(
                    text = nextCompletionText,
                    providerType = config.providerType,
                    modelId = config.modelId,
                    latencyMs = 45L,
                    inputTokens = 90,
                    outputTokens = 16,
                    finishReason = nextFinishReason,
                )
            }
        }

        val service = AiPreparationService(
            db = db,
            appPreferences = appPreferences,
            secretVault = secretVault,
            aiClient = mockClient,
            messageRepository = messageRepository,
        )
        appPreferences.setAiFeaturesEnabled(true)
        appPreferences.setAiPrivacyConsentAccepted(true)

        service.saveProfile(
            displayName = "標準 Gemini",
            providerType = AiProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelId = "gemini-4-neon",
            rawApiKeyInput = "AIzaMainKey123456",
            setAsDefault = true,
        )
        val subjectProfileId = service.saveProfile(
            displayName = "件名専用 OpenAI",
            providerType = AiProviderType.OPENAI,
            baseUrl = "https://api.openai.com/v1",
            modelId = "gpt-6-luna",
            rawApiKeyInput = "sk-subj-key-9999",
            setAsDefault = false,
        )
        appPreferences.setAiSubjectProfileId(subjectProfileId)
        appPreferences.setAiSubjectModelOverride("gpt-6-fast")
        appPreferences.setAiSubjectCustomInstruction("20文字以内で【要確認】を先頭に付ける")

        val subjectReq = service.buildSubjectFromBodyPrompt(
            bodyDraft = "契約更新の書類をご確認のうえ今週金曜までにご返信ください。",
            currentSubject = "",
            tone = AiReplyTone.FORMAL_BUSINESS,
            isReplyMode = false,
            context = null,
        )
        assertEquals(2048, subjectReq.maxOutputTokens)
        assertTrue(subjectReq.systemPrompt.contains("【件名作成専用の指示】"))
        assertTrue(subjectReq.systemPrompt.contains("20文字以内で【要確認】を先頭に付ける"))

        val subjectResult = service.generateCompletion(subjectReq)
        assertEquals(AiProviderType.OPENAI, lastCompleteConfig?.providerType)
        assertEquals("gpt-6-fast", lastCompleteConfig?.modelId)
        assertEquals("sk-subj-key-9999", lastCompleteConfig?.apiKey)
        assertEquals(AiProviderType.OPENAI, subjectResult.providerType)
        assertEquals("gpt-6-fast", subjectResult.modelId)

        // Regular draft generation remains completely isolated on the default profile & model
        val draftReq = service.buildPromptPreview(
            goal = AiWritingGoal.NEW_DRAFT,
            tone = AiReplyTone.FORMAL_BUSINESS,
            context = null,
            userInstruction = "案内メールの下書き",
            currentSubject = "",
            currentBodyDraft = "",
        )
        service.generateCompletion(draftReq)
        assertEquals(AiProviderType.GEMINI, lastCompleteConfig?.providerType)
        assertEquals("gemini-4-neon", lastCompleteConfig?.modelId)
        assertEquals("AIzaMainKey123456", lastCompleteConfig?.apiKey)

        // Subject-specific connection test targets the separated subject profile + overridden model
        val testRes = service.testSubjectConfigurationConnection()
        assertTrue(testRes.success)
        assertEquals(AiProviderType.OPENAI, lastVerifiedConfig?.providerType)
        assertEquals("gpt-6-fast", lastVerifiedConfig?.modelId)

        // Blank text caused by MAX_TOKENS / length explains token limit clearly instead of generic prompt error
        nextCompletionText = "  "
        nextFinishReason = "MAX_TOKENS"
        val maxTokensErr = runCatching { service.generateCompletion(subjectReq) }.exceptionOrNull() as? AiClientException
        assertEquals(AiClientException.Kind.PARSE_ERROR, maxTokensErr?.kind)
        assertTrue(maxTokensErr?.message.orEmpty().contains("内部推論トークン"))
        assertTrue(maxTokensErr?.message.orEmpty().contains("gpt-6-fast"))
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = InMemoryEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class InMemoryEditor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
                if (key != null) pending[key] = values?.toSet()
            }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) map.clear()
                pending.forEach { (k, v) ->
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
    }
}
