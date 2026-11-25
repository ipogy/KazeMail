package com.kazemail.app.ui.compose

import android.content.SharedPreferences
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kazemail.app.ai.AiClient
import com.kazemail.app.ai.AiCompletionRequest
import com.kazemail.app.ai.AiCompletionResult
import com.kazemail.app.ai.AiConnectionTestResult
import com.kazemail.app.ai.AiPreparationService
import com.kazemail.app.ai.AiProviderConnectionConfig
import com.kazemail.app.ai.AiProviderType
import com.kazemail.app.ai.AiReplyTone
import com.kazemail.app.ai.AiSecretVault
import com.kazemail.app.ai.AiWritingGoal
import com.kazemail.app.ai.AiReferenceFileKind
import com.kazemail.app.ai.AiReferenceFileSpec
import com.kazemail.app.auth.OAuth2SessionResult
import com.kazemail.app.data.AppPreferences
import com.kazemail.app.data.MessageRepository
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.db.FolderEntity
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.KazeMailDatabase_Impl
import com.kazemail.app.data.db.MessageBodyEntity
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.data.db.OutboxMessageEntity
import com.kazemail.app.data.model.AccountSignatureResolver
import com.kazemail.app.security.AccountCredentialStore
import com.kazemail.app.security.EncryptedBlob
import com.kazemail.app.security.SecretCipher
import com.kazemail.app.send.OutboxManager
import com.kazemail.app.sync.CoreImapConfig
import com.kazemail.app.sync.ImapSessionHandle
import com.kazemail.app.sync.MailCoreEngine
import com.kazemail.app.sync.NonDestructiveSyncEngine
import com.kazemail.app.sync.OAuth2TokenRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeViewModelAndHelperTest {

    private lateinit var db: KazeMailDatabase
    private lateinit var outboxManager: OutboxManager
    private lateinit var cipher: SecretCipher
    private lateinit var mailCore: MailCoreEngine
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val builder = Room.inMemoryDatabaseBuilder<KazeMailDatabase>(
            factory = { KazeMailDatabase_Impl() },
        )
        db = KazeMailDatabase.buildWithDriver(builder, BundledSQLiteDriver(), testDispatcher)
        cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = EncryptedBlob(
                Base64.getEncoder().encodeToString(plaintext),
                "AAAA",
            )
            override fun decrypt(blob: EncryptedBlob): ByteArray =
                Base64.getDecoder().decode(blob.ciphertextB64)
        }
        mailCore = object : MailCoreEngine {
            override suspend fun connect(config: CoreImapConfig): ImapSessionHandle = error("unused")
            override suspend fun decompressHtmlZstd(compressed: ByteArray, maxOutputBytes: Long) = ""
        }
        outboxManager = OutboxManager(
            db = db,
            credentialStore = AccountCredentialStore(db.accountSecretDao(), cipher),
            mailCore = mailCore,
            tokenRefresher = OAuth2TokenRefresher { _, email ->
                OAuth2SessionResult(email, "tok", "{}")
            },
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun parsesJapaneseMixedRecipientsAndBuildsReplyAllWithoutSelfLoop() {
        val parsed = ComposeHelper.parseAddressInput(
            "山田 <yamada@example.jp>、佐藤 <Sato@Example.JP>; invalid_addr, yamada@example.jp",
        )
        assertEquals(listOf("yamada@example.jp", "Sato@Example.JP"), parsed.valid)
        assertEquals(listOf("invalid_addr"), parsed.invalid)

        val meta = MessageMetaEntity(
            id = 5L,
            accountId = 1L,
            folderId = 1L,
            uid = 10L,
            messageIdHeader = "msg-10@example.jp",
            inReplyTo = "msg-9@example.jp",
            senderName = "田中",
            senderAddress = "tanaka@example.jp",
            recipientTo = """[{"name":"Me","address":"me@example.jp"},{"name":"鈴木","address":"suzuki@example.jp"}]""",
            subject = "設計打合せ",
            receivedAt = 1_750_000_000_000L,
        )
        val body = MessageBodyEntity(
            metaId = 5L,
            plainText = "ご確認をお願いします。",
            rawHeaders = "Cc: cto@example.jp, ME@example.jp\r\nReferences: <msg-1@example.jp> <msg-9@example.jp>\r\n",
        )

        val replyAll = ComposeHelper.buildQuotedReplyData(
            mode = ComposeUiState.MODE_REPLY_ALL,
            selfEmail = "me@example.jp",
            meta = meta,
            body = body,
        )
        assertEquals(listOf("tanaka@example.jp", "suzuki@example.jp"), replyAll.to)
        assertEquals(listOf("cto@example.jp"), replyAll.cc)
        assertEquals("Re: 設計打合せ", replyAll.subject)
        assertEquals("<msg-10@example.jp>", replyAll.inReplyTo)
        assertEquals(
            listOf("<msg-1@example.jp>", "<msg-9@example.jp>", "<msg-10@example.jp>"),
            replyAll.references,
        )
        assertTrue(replyAll.bodyText.contains("> ご確認をお願いします。"))

        val fwd = ComposeHelper.buildQuotedReplyData(
            mode = ComposeUiState.MODE_FORWARD,
            selfEmail = "me@example.jp",
            meta = meta,
            body = body,
        )
        assertTrue(fwd.to.isEmpty())
        assertEquals("Fwd: 設計打合せ", fwd.subject)
        assertTrue(fwd.bodyText.contains("転送メッセージ"))
    }

    @Test
    fun autoDraftSavesLocallyAfter500msDebounceAndSendMovesToOutbox() = runTest(testDispatcher) {
        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "writer@example.jp",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                authType = "PASSWORD",
            ),
        )
        db.folderDao().insert(
            FolderEntity(
                accountId = accountId,
                folderName = "INBOX",
                fullPath = "INBOX",
                uidValidity = 1L,
            ),
        )

        var workerEnqueued = 0
        val vm = ComposeViewModel(
            db = db,
            outboxManager = outboxManager,
            onEnqueueOutboxWorker = { workerEnqueued += 1 },
            accountId = accountId,
            initialDraftId = null,
            replyToMetaId = null,
            initialMode = ComposeUiState.MODE_NEW,
            autoSaveDebounceMs = 500L,
        )
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)

        vm.updateToInput("dest@example.jp")
        vm.updateSubject("500ms 自動保存テスト")
        vm.updateBodyText("途中入力")
        advanceTimeBy(300L)
        // Still within debounce window -> not persisted yet
        assertNull(vm.uiState.value.draftId)

        vm.updateBodyText("確定した下書き本文")
        advanceTimeBy(550L)
        advanceUntilIdle()

        val savedDraftId = vm.uiState.value.draftId
        assertNotNull(savedDraftId)
        val draftInDb = db.draftDao().getById(savedDraftId!!)!!
        assertEquals("確定した下書き本文", draftInDb.bodyText)

        vm.queueSendToOutbox()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.queuedAndShouldClose)
        assertEquals(1, workerEnqueued)
        assertNull(db.draftDao().getById(savedDraftId))
        val queued = db.outboxDao().getAllQueued()
        assertEquals(1, queued.size)
        assertEquals(OutboxMessageEntity.STATUS_QUEUED, queued.single().status)
        assertEquals("500ms 自動保存テスト", queued.single().subject)
    }

    @Test
    fun composeInsertsConfiguredSignatureAndMailPresetsMatchDomains() = runTest(testDispatcher) {
        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "sig@example.jp",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                authType = "PASSWORD",
            ),
        )
        val vm = ComposeViewModel(
            db = db,
            outboxManager = outboxManager,
            onEnqueueOutboxWorker = {},
            accountId = accountId,
            initialDraftId = null,
            replyToMetaId = null,
            initialMode = ComposeUiState.MODE_NEW,
            autoSaveDebounceMs = 500L,
            signatureProvider = { "山田 太郎\nKazeMail Inc." },
        )
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.bodyText)
        assertTrue(vm.uiState.value.includeSignature)
        assertEquals("山田 太郎\nKazeMail Inc.", vm.uiState.value.effectiveSignatureRaw)
        assertEquals("-- \n山田 太郎\nKazeMail Inc.", vm.buildCombinedPlainBody())

        assertEquals(
            "imap.gmail.com",
            com.kazemail.app.ui.account.MailServerPresets.suggestForEmail("taro@gmail.com")?.imapHost,
        )
        assertEquals(
            "imap.mail.yahoo.co.jp",
            com.kazemail.app.ui.account.MailServerPresets.suggestForEmail("hanako@yahoo.co.jp")?.imapHost,
        )
        assertEquals(
            "imap.mail.me.com",
            com.kazemail.app.ui.account.MailServerPresets.suggestForEmail("ken@icloud.com")?.imapHost,
        )
        assertEquals(
            null,
            com.kazemail.app.ui.account.MailServerPresets.suggestForEmail("custom@my-company.co.jp"),
        )
    }

    @Test
    fun composeAiBodyMergerPreservesSignatureAndQuotedHistoryAndSupportsUndo() = runTest(testDispatcher) {
        val originalReply = """
            書きかけの挨拶

            -- 
            佐藤 花子

            2026/11/25 10:00 田中 <tanaka@example.jp>:
            > ご都合いかがでしょうか？
        """.trimIndent()

        assertEquals("書きかけの挨拶", ComposeAiBodyMerger.extractEditableDraftSection(originalReply))

        val replaced = ComposeAiBodyMerger.mergeGeneratedBody(
            currentBody = originalReply,
            generatedBody = "お世話になっております。\n来週火曜14時で問題ございません。",
            appendToExisting = false,
        )
        assertTrue(replaced.startsWith("お世話になっております。\n来週火曜14時で問題ございません。"))
        assertTrue(replaced.contains("-- \n佐藤 花子"))
        assertTrue(replaced.contains("> ご都合いかがでしょうか？"))
        assertFalse(replaced.contains("書きかけの挨拶"))

        val appended = ComposeAiBodyMerger.mergeGeneratedBody(
            currentBody = originalReply,
            generatedBody = "追記段落です。",
            appendToExisting = true,
        )
        assertTrue(appended.startsWith("書きかけの挨拶\n\n追記段落です。"))
        assertTrue(appended.contains("-- \n佐藤 花子"))

        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "sato@example.jp",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                authType = "PASSWORD",
            ),
        )
        val folderId = db.folderDao().insert(
            FolderEntity(
                accountId = accountId,
                folderName = "INBOX",
                fullPath = "INBOX",
                uidValidity = 1L,
            ),
        )
        val metaId = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accountId,
                folderId = folderId,
                uid = 100L,
                senderName = "田中",
                senderAddress = "tanaka@example.jp",
                subject = "日程の件",
                receivedAt = 1_760_000_000_000L,
            ),
        )
        db.messageBodyDao().upsert(
            MessageBodyEntity(
                metaId = metaId,
                plainText = "来週火曜14時はいかがでしょうか。",
            ),
        )

        val prefs = AppPreferences(localMemoryPrefs())
        prefs.setAiFeaturesEnabled(true)
        prefs.setAiPrivacyConsentAccepted(true)

        var capturedReq: AiCompletionRequest? = null
        val fakeClient = object : AiClient {
            override suspend fun verifyConnection(config: AiProviderConnectionConfig) =
                AiConnectionTestResult(true, config.providerType, config.modelId, 10L, 200, "OK", "OK")

            override suspend fun complete(
                config: AiProviderConnectionConfig,
                request: AiCompletionRequest,
            ): AiCompletionResult {
                capturedReq = request
                return AiCompletionResult(
                    text = "田中様\n\n来週火曜14時で承知いたしました。よろしくお願いいたします。",
                    providerType = config.providerType,
                    modelId = config.modelId,
                    latencyMs = 95L,
                )
            }
        }
        val syncEngine = NonDestructiveSyncEngine(
            db = db,
            credentialStore = AccountCredentialStore(db.accountSecretDao(), cipher),
            mailCore = mailCore,
            tokenRefresher = OAuth2TokenRefresher { _, _ -> error("unused") },
        )
        val aiService = AiPreparationService(
            db = db,
            appPreferences = prefs,
            secretVault = AiSecretVault(cipher),
            aiClient = fakeClient,
            messageRepository = MessageRepository(db, mailCore, syncEngine),
        )
        aiService.saveProfile(
            displayName = "Gemini",
            providerType = AiProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelId = "gemini-4-hydrogen",
            rawApiKeyInput = "AIzaTestKeyForCompose1234",
            setAsDefault = true,
        )

        val vm = ComposeViewModel(
            db = db,
            outboxManager = outboxManager,
            onEnqueueOutboxWorker = {},
            accountId = accountId,
            initialDraftId = null,
            replyToMetaId = metaId,
            initialMode = ComposeUiState.MODE_REPLY,
            autoSaveDebounceMs = 500L,
            signatureProvider = { "佐藤 花子" },
            aiPreparationService = aiService,
            initialAiTone = AiReplyTone.FORMAL_BUSINESS,
            initialAiInstruction = "火曜14時で承諾",
            autoTriggerAiOnStart = true,
        )
        advanceUntilIdle()

        assertEquals(AiWritingGoal.REPLY, capturedReq?.goal)
        assertNotNull(vm.uiState.value.aiGeneratedDraft)
        assertTrue(vm.uiState.value.aiAutoSheetRequested)
        val preApplyBody = vm.uiState.value.bodyText
        assertEquals("", preApplyBody)
        assertTrue(vm.uiState.value.includeSignature)
        assertTrue(vm.uiState.value.includeQuote)
        assertTrue(vm.uiState.value.quotePlain.contains("> 来週火曜14時はいかがでしょうか。"))
        val combinedBeforeApply = vm.buildCombinedPlainBody()
        assertTrue(combinedBeforeApply.contains("-- \n佐藤 花子"))
        assertTrue(combinedBeforeApply.contains("> 来週火曜14時はいかがでしょうか。"))

        vm.applyGeneratedAiDraft(append = false)
        val appliedBody = vm.uiState.value.bodyText
        assertTrue(appliedBody.startsWith("田中様\n\n来週火曜14時で承知いたしました。"))
        val combinedApplied = vm.buildCombinedPlainBody()
        assertTrue(combinedApplied.contains("-- \n佐藤 花子"))
        assertTrue(combinedApplied.contains("> 来週火曜14時はいかがでしょうか。"))
        assertEquals(preApplyBody, vm.uiState.value.previousBodyBeforeAi)

        vm.undoLastAiApply()
        assertEquals(preApplyBody, vm.uiState.value.bodyText)
        assertNull(vm.uiState.value.previousBodyBeforeAi)

        advanceTimeBy(600L)
        advanceUntilIdle()
    }

    @Test
    fun externalPrefillPopulatesComposeFieldsAboveSignature() = runTest(testDispatcher) {
        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "me@example.com",
                displayName = "自分",
                imapHost = "imap.example.com",
                smtpHost = "smtp.example.com",
                authType = "PASSWORD",
            ),
        )

        val vm = ComposeViewModel(
            db = db,
            outboxManager = outboxManager,
            onEnqueueOutboxWorker = {},
            accountId = accountId,
            initialDraftId = null,
            replyToMetaId = null,
            initialMode = ComposeUiState.MODE_NEW,
            signatureProvider = { "佐藤 花子" },
            externalPrefill = ComposeExternalPrefill(
                to = listOf("alice+work@example.com", "bob@example.org"),
                cc = listOf("team@example.com"),
                bcc = listOf("audit@example.com"),
                subject = "mailto 件名テスト",
                bodyText = "外部アプリからの本文\r\n2行目",
            ),
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals("alice+work@example.com, bob@example.org", state.toInput)
        assertEquals("team@example.com", state.ccInput)
        assertEquals("audit@example.com", state.bccInput)
        assertTrue(state.showCcBcc)
        assertEquals("mailto 件名テスト", state.subject)
        assertEquals("外部アプリからの本文\r\n2行目", state.bodyText)
        assertTrue(state.includeSignature)
        assertEquals("外部アプリからの本文\r\n2行目\n\n-- \n佐藤 花子", vm.buildCombinedPlainBody())
    }

    @Test
    fun composeViewModelLoadsInitialAiReferenceEmailsAndTextFiles() = runTest(testDispatcher) {
        val accId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "sato@example.jp",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                authType = "PASSWORD",
            ),
        )
        val folderId = db.folderDao().insert(
            FolderEntity(accountId = accId, folderName = "INBOX", fullPath = "INBOX", uidValidity = 1L),
        )
        val refMetaId = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accId,
                folderId = folderId,
                uid = 77L,
                senderName = "高橋",
                senderAddress = "takahashi@example.jp",
                subject = "仕様変更のお知らせ",
                receivedAt = 1_760_000_000_000L,
            ),
        )
        db.messageBodyDao().upsert(MessageBodyEntity(metaId = refMetaId, plainText = "新仕様はVer2.1です。"))

        val tempTxt = java.nio.file.Files.createTempFile("ai_compose_ref_", ".txt").toFile()
        tempTxt.writeText("補足資料: 納品日は12月20日")
        try {
            val prefs = AppPreferences(localMemoryPrefs())
            prefs.setAiFeaturesEnabled(true)
            prefs.setAiPrivacyConsentAccepted(true)
            var capturedPrompt = ""
            val fakeClient = object : AiClient {
                override suspend fun verifyConnection(config: AiProviderConnectionConfig) = error("unused")
                override suspend fun complete(config: AiProviderConnectionConfig, request: AiCompletionRequest): AiCompletionResult {
                    capturedPrompt = request.userPrompt
                    return AiCompletionResult("Ver2.1の件で12月20日納品として進めます。", config.providerType, config.modelId, 50L)
                }
            }
            val syncEngine = NonDestructiveSyncEngine(
                db = db,
                credentialStore = AccountCredentialStore(db.accountSecretDao(), cipher),
                mailCore = mailCore,
                tokenRefresher = OAuth2TokenRefresher { _, _ -> error("unused") },
            )
            val aiService = AiPreparationService(
                db = db,
                appPreferences = prefs,
                secretVault = AiSecretVault(cipher),
                aiClient = fakeClient,
                messageRepository = MessageRepository(db, mailCore, syncEngine),
                ioDispatcher = testDispatcher,
            )
            aiService.saveProfile(
                displayName = "Gemini",
                providerType = AiProviderType.GEMINI,
                baseUrl = AiProviderType.GEMINI.defaultBaseUrl,
                modelId = AiProviderType.GEMINI.defaultModelId,
                rawApiKeyInput = "AIzaTestComposeRefKey1234",
            )

            val vm = ComposeViewModel(
                db = db,
                outboxManager = outboxManager,
                onEnqueueOutboxWorker = {},
                accountId = accId,
                initialDraftId = null,
                replyToMetaId = null,
                initialMode = ComposeUiState.MODE_NEW,
                aiPreparationService = aiService,
                initialAiInstruction = "参照資料をもとに返信文を書いて",
                autoTriggerAiOnStart = true,
                initialAiReferenceMetaIds = listOf(refMetaId),
                initialAiReferenceFiles = listOf(
                    AiReferenceFileSpec(
                        id = "ref1",
                        displayName = "memo.txt",
                        mimeType = "text/plain",
                        localPath = tempTxt.absolutePath,
                        sizeBytes = tempTxt.length(),
                        kind = AiReferenceFileKind.TEXT_DOCUMENT,
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(1, vm.uiState.value.aiReferenceEmails.size)
            assertEquals(1, vm.uiState.value.aiReferenceFiles.size)
            assertTrue(capturedPrompt.contains("新仕様はVer2.1です。"))
            assertTrue(capturedPrompt.contains("補足資料: 納品日は12月20日"))
            assertNotNull(vm.uiState.value.aiGeneratedDraft)
        } finally {
            tempTxt.delete()
        }
    }

    @Test
    fun aiSignatureAlwaysAppliesWhenConfiguredEvenIfClearedOrReturnedByAi() {
        val generatedWithDuplicateSig = """
            田中様

            来週火曜14時で承知いたしました。よろしくお願いいたします。

            -- 
            [あなたの署名]
        """.trimIndent()

        // 1. Editor had quoted reply only (no signature yet), signature configured -> inserts before quote & strips AI duplicate sig
        val bodyOnlyQuote = "\n\n2026/11/25 10:00 tanaka@example.jp:\n> ご都合いかがでしょうか"
        val mergedWithQuote = ComposeAiBodyMerger.mergeGeneratedBody(
            currentBody = bodyOnlyQuote,
            generatedBody = generatedWithDuplicateSig,
            appendToExisting = false,
            configuredSignature = "株式会社Kaze / 佐藤 花子",
        )
        assertTrue(mergedWithQuote.startsWith("田中様\n\n来週火曜14時で承知いたしました。よろしくお願いいたします。"))
        assertTrue(mergedWithQuote.contains("-- \n株式会社Kaze / 佐藤 花子"))
        assertFalse(mergedWithQuote.contains("[あなたの署名]"))
        assertEquals(1, Regex("""-- ?\n""").findAll(mergedWithQuote).count())
        assertTrue(
            mergedWithQuote.indexOf("-- \n株式会社Kaze / 佐藤 花子") <
                mergedWithQuote.indexOf("2026/11/25 10:00"),
        )

        // 2. Editor was completely empty -> signature still appended cleanly
        val mergedEmpty = ComposeAiBodyMerger.mergeGeneratedBody(
            currentBody = "",
            generatedBody = "承知いたしました。",
            appendToExisting = false,
            configuredSignature = "佐藤 花子",
        )
        assertEquals("承知いたしました。\n\n-- \n佐藤 花子", mergedEmpty)

        // 3. Space trimmed after "--" in editor -> still recognized as signature block cleanly
        val noSpaceSigBody = "古い下書き\n\n--\n佐藤 花子"
        val mergedNoSpace = ComposeAiBodyMerger.mergeGeneratedBody(
            currentBody = noSpaceSigBody,
            generatedBody = "新しい本文です。",
            appendToExisting = false,
            configuredSignature = "佐藤 花子",
        )
        assertTrue(mergedNoSpace.startsWith("新しい本文です。"))
        assertFalse(mergedNoSpace.contains("古い下書き"))
        assertTrue(mergedNoSpace.contains("--\n佐藤 花子"))
    }

    @Test
    fun composeViewModelClarificationQuestionsAndBodyToSubjectEndToEnd() = runTest(testDispatcher) {
        val accId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "sato@example.jp",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                authType = "PASSWORD",
            ),
        )
        val prefs = AppPreferences(localMemoryPrefs())
        prefs.setAiFeaturesEnabled(true)
        prefs.setAiPrivacyConsentAccepted(true)

        val recordedGoals = mutableListOf<AiWritingGoal>()
        var lastDraftPrompt = ""
        var lastSubjectPrompt = ""
        val fakeClient = object : AiClient {
            override suspend fun verifyConnection(config: AiProviderConnectionConfig) = error("unused")
            override suspend fun complete(config: AiProviderConnectionConfig, request: AiCompletionRequest): AiCompletionResult {
                recordedGoals += request.goal
                return when (request.goal) {
                    AiWritingGoal.CLARIFY_QUESTIONS -> AiCompletionResult(
                        text = """
                            {
                              "summary": "詳細を2点確認します。",
                              "questions": [
                                {
                                  "id": "q1",
                                  "question": "希望の開催形式はどれですか？",
                                  "allowMultiple": false,
                                  "options": ["オンライン会議", "対面でのご訪問"],
                                  "customPlaceholder": "URLや会議室名を入力"
                                }
                              ]
                            }
                        """.trimIndent(),
                        providerType = config.providerType,
                        modelId = config.modelId,
                        latencyMs = 20L,
                    )
                    AiWritingGoal.GENERATE_SUBJECT -> {
                        lastSubjectPrompt = request.userPrompt
                        AiCompletionResult(
                            text = """{"subjects":["オンライン会議での打ち合わせ日程の件","お打ち合わせ開催形式のご案内"]}""",
                            providerType = config.providerType,
                            modelId = config.modelId,
                            latencyMs = 15L,
                        )
                    }
                    else -> {
                        lastDraftPrompt = request.userPrompt
                        AiCompletionResult(
                            text = "来週火曜14時よりオンライン会議（Meet）にてお願いできれば幸いです。",
                            providerType = config.providerType,
                            modelId = config.modelId,
                            latencyMs = 30L,
                        )
                    }
                }
            }
        }
        val syncEngine = NonDestructiveSyncEngine(
            db = db,
            credentialStore = AccountCredentialStore(db.accountSecretDao(), cipher),
            mailCore = mailCore,
            tokenRefresher = OAuth2TokenRefresher { _, _ -> error("unused") },
        )
        val aiService = AiPreparationService(
            db = db,
            appPreferences = prefs,
            secretVault = AiSecretVault(cipher),
            aiClient = fakeClient,
            messageRepository = MessageRepository(db, mailCore, syncEngine),
            ioDispatcher = testDispatcher,
        )
        aiService.saveProfile(
            displayName = "Gemini",
            providerType = AiProviderType.GEMINI,
            baseUrl = AiProviderType.GEMINI.defaultBaseUrl,
            modelId = AiProviderType.GEMINI.defaultModelId,
            rawApiKeyInput = "AIzaTestClarifyKey5678",
        )

        val vm = ComposeViewModel(
            db = db,
            outboxManager = outboxManager,
            onEnqueueOutboxWorker = {},
            accountId = accId,
            initialDraftId = null,
            replyToMetaId = null,
            initialMode = ComposeUiState.MODE_NEW,
            signatureProvider = { "佐藤 花子 / 株式会社サンプル" },
            aiPreparationService = aiService,
            initialAiInstruction = "打ち合わせのお願いを送りたい",
        )
        advanceUntilIdle()

        // Manually clear bodyText to simulate user wiping signature before using AI
        vm.updateBodyText("")
        vm.requestAiClarificationQuestions()
        advanceUntilIdle()

        val plan = requireNotNull(vm.uiState.value.aiClarificationPlan)
        assertEquals(1, plan.questions.size)

        vm.selectClarificationOption("q1", "オンライン会議")
        vm.updateClarificationCustomInput("q1", "Google Meet URLを希望")
        vm.generateAiDraft()
        advanceUntilIdle()

        assertTrue(lastDraftPrompt.contains("【AIからの逆質問への回答】"))
        assertTrue(lastDraftPrompt.contains("オンライン会議"))
        assertTrue(lastDraftPrompt.contains("Google Meet URLを希望"))

        // Apply AI body -> signature stays cleanly attached via separated Signature Card & combined output
        vm.applyGeneratedAiDraft(append = false)
        assertTrue(vm.uiState.value.bodyText.contains("オンライン会議（Meet）"))
        assertTrue(vm.buildCombinedPlainBody().contains("-- \n佐藤 花子 / 株式会社サンプル"))

        // Generate subject from written body -> signature excluded, top candidate applied, undo supported
        vm.generateAiSubjectFromBody()
        advanceUntilIdle()
        assertFalse(lastSubjectPrompt.contains("佐藤 花子 / 株式会社サンプル"))
        assertEquals(3, recordedGoals.size)
        assertEquals(AiWritingGoal.CLARIFY_QUESTIONS, recordedGoals[0])
        assertEquals(AiWritingGoal.GENERATE_SUBJECT, recordedGoals[2])
        assertEquals("オンライン会議での打ち合わせ日程の件", vm.uiState.value.subject)
        assertEquals(2, vm.uiState.value.aiSubjectSuggestions.size)

        vm.applyAiSubjectCandidate("お打ち合わせ開催形式のご案内")
        assertEquals("お打ち合わせ開催形式のご案内", vm.uiState.value.subject)

        vm.undoLastAiSubject()
        assertEquals("", vm.uiState.value.subject)
        advanceTimeBy(600L)
        advanceUntilIdle()
    }

    @Test
    fun composeHtmlHelperFormattingConversionAndLinkNormalization() {
        // Empty selection puts cursor directly inside tag pair for IME input
        val emptyBold = ComposeHtmlHelper.applyFormattingAction("前 文", 2, 2, HtmlFormatAction.BOLD)
        assertEquals("前 <b></b>文", emptyBold.newText)
        assertEquals("前 <b>".length, emptyBold.selectionStart)

        // Non-empty selection wraps selected text
        val wrapped = ComposeHtmlHelper.applyFormattingAction("重要です", 0, 2, HtmlFormatAction.ACCENT_COLOR)
        assertEquals("<span style=\"color:#C62828\">重要</span>です", wrapped.newText)

        // Bullet list splits selected lines
        val bullet = ComposeHtmlHelper.applyFormattingAction("項目A\n項目B", 0, 7, HtmlFormatAction.BULLET_LIST)
        assertTrue(bullet.newText.contains("<li>項目A</li>"))
        assertTrue(bullet.newText.contains("<li>項目B</li>"))

        // Link normalizes missing scheme & escapes HTML safely
        val link = ComposeHtmlHelper.insertLink("確認はこちら", 5, 5, "example.com/path?a=1&b=2", "詳細ページ")
        assertTrue(link.newText.contains("<a href=\"https://example.com/path?a=1&amp;b=2\">詳細ページ</a>"))

        // Plain <-> HTML roundtrip keeps draft, signature, and quote intact
        val plainWithQuote = "お世話になっております。\n\n-- \n株式会社A\n\n2026/11/25 10:00 x@example.jp:\n> 元メール本文"
        val html = ComposeHtmlHelper.convertPlainComposeBodyToHtml(plainWithQuote)
        assertTrue(html.contains("<p>お世話になっております。</p>"))
        assertTrue(html.contains(ComposeHtmlHelper.SIGNATURE_DATA_ATTR))
        assertTrue(html.contains(ComposeHtmlHelper.QUOTE_DATA_ATTR))

        val roundtripPlain = ComposeHtmlHelper.convertHtmlComposeBodyToPlain(html)
        assertTrue(roundtripPlain.contains("お世話になっております。"))
        assertTrue(roundtripPlain.contains("-- \n株式会社A"))
        assertTrue(roundtripPlain.contains("> 元メール本文"))

        // AI HTML output strips markdown fences & html shell
        val fenced = "```html\n<html><body><p><b>承認</b>いたしました。</p></body></html>\n```"
        assertEquals("<p><b>承認</b>いたしました。</p>", ComposeHtmlHelper.sanitizeAiHtmlOutput(fenced))
    }

    @Test
    fun perAccountSignatureResolutionFromSwitcherAndHtmlAiComposeFlow() = runTest(testDispatcher) {
        val workId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "work@company.jp",
                displayName = "仕事用",
                imapHost = "imap.company.jp",
                smtpHost = "smtp.company.jp",
                authType = "PASSWORD",
                signatureEnabled = true,
                signatureText = "<b>株式会社Work</b><br>work@company.jp",
                signatureConfigured = true,
            ),
        )
        val personalId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "personal@example.jp",
                displayName = "個人",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                authType = "PASSWORD",
                signatureEnabled = true,
                signatureText = "個人署名\npersonal@example.jp",
                signatureConfigured = true,
            ),
        )
        val legacyAcc = AccountEntity(
            accountId = 99L,
            emailAddress = "legacy@example.jp",
            imapHost = "imap.example.jp",
            smtpHost = "smtp.example.jp",
            authType = "PASSWORD",
            signatureConfigured = false,
        )
        assertEquals(
            "引継署名",
            AccountSignatureResolver.resolveActiveSignatureText(legacyAcc, legacyEnabled = true, legacyText = "引継署名"),
        )
        assertEquals(
            "個人署名\npersonal@example.jp",
            AccountSignatureResolver.resolveActiveSignatureText(db.accountDao().getById(personalId), true, "引継署名"),
        )

        val prefs = AppPreferences(localMemoryPrefs())
        prefs.setAiFeaturesEnabled(true)
        prefs.setAiPrivacyConsentAccepted(true)

        var sawHtmlPromptInstruction = false
        var lastSubjectPrompt = ""
        var lastSubjectSystemPrompt = ""
        var lastSubjectConfig: AiProviderConnectionConfig? = null
        val fakeClient = object : AiClient {
            override suspend fun verifyConnection(config: AiProviderConnectionConfig) = error("unused")
            override suspend fun complete(config: AiProviderConnectionConfig, request: AiCompletionRequest): AiCompletionResult {
                return when (request.goal) {
                    AiWritingGoal.GENERATE_SUBJECT -> {
                        lastSubjectPrompt = request.userPrompt
                        lastSubjectSystemPrompt = request.systemPrompt
                        lastSubjectConfig = config
                        val returnedModel = if (prefs.aiSubjectModelOverride.value.isNotBlank()) config.modelId else "test"
                        AiCompletionResult("""{"subjects":["HTML件名テスト"]}""", config.providerType, returnedModel, 10L)
                    }
                    else -> {
                        if (request.systemPrompt.contains("HTML メール用本文フラグメント")) {
                            sawHtmlPromptInstruction = true
                        }
                        AiCompletionResult(
                            "<p>佐藤様</p><p><b>HTML形式で回答</b>いたします。</p><div data-kazemail-signature=\"1\">-- <br>[仮署名]</div>",
                            AiProviderType.GEMINI,
                            "test",
                            12L,
                        )
                    }
                }
            }
        }
        val syncEngine = NonDestructiveSyncEngine(
            db = db,
            credentialStore = AccountCredentialStore(db.accountSecretDao(), cipher),
            mailCore = mailCore,
            tokenRefresher = OAuth2TokenRefresher { _, _ -> error("unused") },
        )
        val aiService = AiPreparationService(
            db = db,
            appPreferences = prefs,
            secretVault = AiSecretVault(cipher),
            aiClient = fakeClient,
            messageRepository = MessageRepository(db, mailCore, syncEngine),
            ioDispatcher = testDispatcher,
        )
        aiService.saveProfile(
            displayName = "HTML Mock AI",
            providerType = AiProviderType.GEMINI,
            baseUrl = AiProviderType.GEMINI.defaultBaseUrl,
            modelId = AiProviderType.GEMINI.defaultModelId,
            rawApiKeyInput = "AIzaTestHtmlMockKey1234",
        )

        val vm = ComposeViewModel(
            db = db,
            outboxManager = outboxManager,
            onEnqueueOutboxWorker = {},
            accountId = workId,
            initialDraftId = null,
            replyToMetaId = null,
            initialMode = ComposeUiState.MODE_NEW,
            autoSaveDebounceMs = 50L,
            aiPreparationService = aiService,
            initialAiInstruction = "HTMLで返信",
        )
        advanceUntilIdle()

        // Work account has an HTML signature -> automatically starts in HTML mode!
        assertEquals("work@company.jp", vm.uiState.value.fromEmail)
        assertEquals(2, vm.uiState.value.availableAccounts.size)
        assertTrue(vm.uiState.value.isHtmlMode)
        assertTrue(vm.uiState.value.includeSignature)
        val initialCombinedHtml = vm.buildCombinedHtmlBody()
        assertTrue(initialCombinedHtml.contains("<b>株式会社Work</b><br>work@company.jp"))
        assertFalse(initialCombinedHtml.contains("-- <br>"))

        // Before typing anything, switching to personal account (plain signature) switches to plain mode & swaps sig
        vm.selectFromAccount(personalId)
        assertEquals("personal@example.jp", vm.uiState.value.fromEmail)
        assertFalse(vm.uiState.value.isHtmlMode)
        assertTrue(vm.buildCombinedPlainBody().contains("個人署名"))

        // Type draft and switch From account back to work -> typed draft preserved, signature swapped back to Work HTML
        vm.updateBodyText("お疲れ様です。")
        vm.selectFromAccount(workId)
        assertEquals("work@company.jp", vm.uiState.value.fromEmail)
        assertEquals("お疲れ様です。", vm.uiState.value.bodyText)
        vm.toggleHtmlMode(true)
        assertTrue(vm.uiState.value.isHtmlMode)
        assertTrue(vm.buildCombinedHtmlBody().contains("<b>株式会社Work</b><br>work@company.jp"))

        // Even in HTML signature mode, AI body generation defaults to Plain Text until explicitly switched
        assertFalse(vm.uiState.value.aiPreferHtmlOutput)
        vm.generateAiDraft()
        advanceUntilIdle()
        assertFalse(sawHtmlPromptInstruction)
        assertFalse(vm.uiState.value.aiGeneratedDraftIsHtml)

        // Switching AI output format to HTML prefers HTML output, strips duplicate AI sig, keeps Work HTML sig separated
        vm.selectAiPreferHtmlOutput(true)
        assertTrue(vm.uiState.value.aiPreferHtmlOutput)
        vm.generateAiDraft()
        advanceUntilIdle()
        assertTrue(sawHtmlPromptInstruction)
        assertTrue(vm.uiState.value.aiGeneratedDraftIsHtml)
        vm.applyGeneratedAiDraft(append = false)
        assertTrue(vm.uiState.value.bodyHtml.contains("<b>HTML形式で回答</b>"))
        assertFalse(vm.uiState.value.bodyHtml.contains("株式会社Work"))
        assertFalse(vm.uiState.value.bodyHtml.contains("[仮署名]"))
        val combinedAiHtml = vm.buildCombinedHtmlBody()
        assertTrue(combinedAiHtml.contains("<b>HTML形式で回答</b>"))
        assertTrue(combinedAiHtml.contains("<b>株式会社Work</b><br>work@company.jp"))
        assertFalse(combinedAiHtml.contains("[仮署名]"))

        // Visiting Text mode without editing and returning to HTML mode preserves rich HTML 100%
        vm.toggleHtmlMode(false)
        assertFalse(vm.uiState.value.isHtmlMode)
        assertTrue(vm.uiState.value.bodyText.contains("HTML形式で回答"))
        vm.toggleHtmlMode(true)
        assertTrue(vm.uiState.value.isHtmlMode)
        assertFalse(vm.uiState.value.pendingHtmlOverwriteConfirm)
        assertTrue(vm.uiState.value.bodyHtml.contains("<b>HTML形式で回答</b>"))
        assertTrue(vm.buildCombinedHtmlBody().contains("<b>株式会社Work</b><br>work@company.jp"))

        // Subject generation strips HTML tags & signature cleanly before sending prompt
        vm.generateAiSubjectFromBody()
        advanceUntilIdle()
        assertEquals("HTML件名テスト", vm.uiState.value.subject)
        assertTrue(lastSubjectPrompt.contains("HTML形式で回答"))
        assertFalse(lastSubjectPrompt.contains("<b>"))
        assertFalse(lastSubjectPrompt.contains("株式会社Work"))
        assertEquals("Google Gemini / test", vm.uiState.value.aiSubjectSourceLabel)

        // Separate subject override model & subject-only instruction take effect and reflect in badge
        prefs.setAiSubjectModelOverride("gemini-3.6-flash")
        prefs.setAiSubjectCustomInstruction("20字以内で簡潔に")
        vm.generateAiSubjectFromBody()
        advanceUntilIdle()
        assertEquals("gemini-3.6-flash", lastSubjectConfig?.modelId)
        assertTrue(lastSubjectSystemPrompt.contains("20字以内で簡潔に"))
        assertEquals("Google Gemini / gemini-3.6-flash", vm.uiState.value.aiSubjectSourceLabel)
        vm.dismissAiSubjectSuggestions()
        assertEquals(null, vm.uiState.value.aiSubjectSourceLabel)

        // Send queues OutboxMessageEntity with both HTML (incl. Work HTML signature) and plain text fallback
        vm.updateToInput("dest@example.jp")
        vm.queueSendToOutbox()
        advanceUntilIdle()
        val queued = db.outboxDao().getAllQueued().single()
        assertEquals("work@company.jp", queued.fromEmail)
        val queuedHtml = requireNotNull(queued.bodyHtml)
        assertTrue(queuedHtml.contains("<b>HTML形式で回答</b>"))
        assertTrue(queuedHtml.contains("<b>株式会社Work</b><br>work@company.jp"))
        assertTrue(queued.bodyText.contains("HTML形式で回答"))
        assertTrue(queued.bodyText.contains("株式会社Work"))
    }

    @Test
    fun htmlSignatureModeRoundtripDividerAndDraftReloadSafety() = runTest(testDispatcher) {
        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "design@example.jp",
                displayName = "デザイン署名",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                authType = "PASSWORD",
                signatureEnabled = true,
                signatureText = "---<br><b>株式会社デザイン</b><br><a href=\"https://design.example.jp\">サイト</a>",
                signatureConfigured = true,
            ),
        )
        val vm = ComposeViewModel(
            db = db,
            outboxManager = outboxManager,
            onEnqueueOutboxWorker = {},
            accountId = accountId,
            initialDraftId = null,
            replyToMetaId = null,
            initialMode = ComposeUiState.MODE_NEW,
            autoSaveDebounceMs = 50L,
        )
        advanceUntilIdle()

        // 1. HTML signature starting with "---" auto-opens HTML mode cleanly without chopping dashes
        assertTrue(vm.uiState.value.isHtmlMode)
        val sigCombinedHtml = vm.buildCombinedHtmlBody()
        assertTrue(sigCombinedHtml.contains("---<br><b>株式会社デザイン</b>"))
        assertFalse(sigCombinedHtml.contains("-- <br>"))
        val sigCombinedPlain = vm.buildCombinedPlainBody()
        assertTrue(sigCombinedPlain.contains("-- \n---\n株式会社デザイン"))

        // 2. Edit rich HTML body -> switch to Text -> edit Text -> confirm dialog asks safely!
        vm.updateBodyHtml("<h3>お知らせ</h3><p><b>重要</b>: <a href=\"https://info.jp\">詳細</a></p>")
        vm.toggleHtmlMode(false)
        assertFalse(vm.uiState.value.isHtmlMode)
        assertTrue(vm.uiState.value.bodyText.contains("重要"))

        // Modify text in Plain mode
        vm.updateBodyText("修正した本文です")
        vm.toggleHtmlMode(true)
        assertTrue(vm.uiState.value.pendingHtmlOverwriteConfirm)
        assertFalse(vm.uiState.value.isHtmlMode)

        // Cancel / Restore original rich HTML
        vm.confirmSwitchToHtmlWithTextChanges(applyTextChanges = false)
        assertFalse(vm.uiState.value.pendingHtmlOverwriteConfirm)
        assertTrue(vm.uiState.value.isHtmlMode)
        assertTrue(vm.uiState.value.bodyHtml.contains("<h3>お知らせ</h3>"))
        assertTrue(vm.buildCombinedHtmlBody().contains("<b>株式会社デザイン</b>"))

        // Go back to Text, edit text, this time confirm applying text changes -> HTML sig STILL untouched!
        vm.toggleHtmlMode(false)
        vm.updateBodyText("テキスト編集を反映")
        vm.toggleHtmlMode(true)
        assertTrue(vm.uiState.value.pendingHtmlOverwriteConfirm)
        vm.confirmSwitchToHtmlWithTextChanges(applyTextChanges = true)
        assertTrue(vm.uiState.value.isHtmlMode)
        assertTrue(vm.uiState.value.bodyHtml.contains("<p>テキスト編集を反映</p>"))
        assertTrue(vm.buildCombinedHtmlBody().contains("<a href=\"https://design.example.jp\">サイト</a>"))

        // 3. Per-email signature override + auto-save draft -> reload Draft preserves separated HTML body & override
        vm.updateSignatureOverride("<b>今回限定署名</b>")
        advanceTimeBy(100L)
        advanceUntilIdle()
        val draftId = requireNotNull(vm.uiState.value.draftId)

        val reloadedVm = ComposeViewModel(
            db = db,
            outboxManager = outboxManager,
            onEnqueueOutboxWorker = {},
            accountId = accountId,
            initialDraftId = draftId,
            replyToMetaId = null,
            initialMode = ComposeUiState.MODE_NEW,
            autoSaveDebounceMs = 50L,
        )
        advanceUntilIdle()
        assertTrue(reloadedVm.uiState.value.isHtmlMode)
        assertTrue(reloadedVm.uiState.value.bodyHtml.contains("<p>テキスト編集を反映</p>"))
        assertFalse(reloadedVm.uiState.value.bodyHtml.contains("今回限定署名"))
        assertTrue(reloadedVm.uiState.value.includeSignature)
        assertEquals("<b>今回限定署名</b>", reloadedVm.uiState.value.effectiveSignatureRaw)
        assertTrue(reloadedVm.buildCombinedHtmlBody().contains("<b>今回限定署名</b>"))
    }

    @Test
    fun aiDraftOutputFormatDefaultsToPlainAndSwitchesToHtmlOnApply() = runTest(testDispatcher) {
        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "plainuser@example.jp",
                displayName = "鈴木",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                authType = "PASSWORD",
                signatureEnabled = true,
                signatureText = "鈴木 太郎\nplainuser@example.jp",
                signatureConfigured = true,
            ),
        )
        val prefs = AppPreferences(localMemoryPrefs())
        prefs.setAiFeaturesEnabled(true)
        prefs.setAiPrivacyConsentAccepted(true)

        val capturedRequests = mutableListOf<AiCompletionRequest>()
        val fakeClient = object : AiClient {
            override suspend fun verifyConnection(config: AiProviderConnectionConfig) = error("unused")
            override suspend fun complete(
                config: AiProviderConnectionConfig,
                request: AiCompletionRequest,
            ): AiCompletionResult {
                capturedRequests += request
                val replyText = if (request.systemPrompt.contains("HTML メール用本文フラグメント")) {
                    "<p><b>重要なお知らせ</b></p><ul><li>10時に開始します</li></ul><div data-kazemail-signature=\"1\">-- <br>仮署名</div>"
                } else {
                    "お疲れ様です。\n日程を確定しました。\n\n-- \n仮署名"
                }
                return AiCompletionResult(replyText, config.providerType, config.modelId, 15L)
            }
        }
        val syncEngine = NonDestructiveSyncEngine(
            db = db,
            credentialStore = AccountCredentialStore(db.accountSecretDao(), cipher),
            mailCore = mailCore,
            tokenRefresher = OAuth2TokenRefresher { _, _ -> error("unused") },
        )
        val aiService = AiPreparationService(
            db = db,
            appPreferences = prefs,
            secretVault = AiSecretVault(cipher),
            aiClient = fakeClient,
            messageRepository = MessageRepository(db, mailCore, syncEngine),
            ioDispatcher = testDispatcher,
        )
        aiService.saveProfile(
            displayName = "Format Test AI",
            providerType = AiProviderType.GEMINI,
            baseUrl = AiProviderType.GEMINI.defaultBaseUrl,
            modelId = AiProviderType.GEMINI.defaultModelId,
            rawApiKeyInput = "AIzaFormatTestKey1234",
        )

        val vm = ComposeViewModel(
            db = db,
            outboxManager = outboxManager,
            onEnqueueOutboxWorker = {},
            accountId = accountId,
            initialDraftId = null,
            replyToMetaId = null,
            initialMode = ComposeUiState.MODE_NEW,
            autoSaveDebounceMs = 50L,
            aiPreparationService = aiService,
            initialAiPreferHtml = prefs.aiDefaultPreferHtml.value,
            initialAiInstruction = "開催案内を送る",
        )
        advanceUntilIdle()

        // Default AI body format must be Plain Text
        assertFalse(prefs.aiDefaultPreferHtml.value)
        assertFalse(vm.uiState.value.aiPreferHtmlOutput)
        assertFalse(vm.uiState.value.isHtmlMode)

        vm.generateAiDraft()
        advanceUntilIdle()
        assertTrue(capturedRequests.last().systemPrompt.contains("プレーンテキスト形式のみで出力"))
        assertFalse(vm.uiState.value.aiGeneratedDraftIsHtml)

        vm.applyGeneratedAiDraft(append = false)
        assertFalse(vm.uiState.value.isHtmlMode)
        assertEquals("お疲れ様です。\n日程を確定しました。", vm.uiState.value.bodyText)
        assertTrue(vm.buildCombinedPlainBody().contains("-- \n鈴木 太郎"))

        // Switch AI output format explicitly to HTML while editor was Plain -> switches to HTML cleanly on apply
        vm.selectAiPreferHtmlOutput(true)
        assertTrue(vm.uiState.value.aiPreferHtmlOutput)
        vm.generateAiDraft()
        advanceUntilIdle()
        assertTrue(capturedRequests.last().systemPrompt.contains("HTML メール用本文フラグメント"))
        assertTrue(vm.uiState.value.aiGeneratedDraftIsHtml)

        vm.applyGeneratedAiDraft(append = false)
        assertTrue(vm.uiState.value.isHtmlMode)
        assertTrue(vm.uiState.value.bodyHtml.contains("<b>重要なお知らせ</b>"))
        assertFalse(vm.uiState.value.bodyHtml.contains("仮署名"))
        assertTrue(vm.buildCombinedHtmlBody().contains("鈴木 太郎"))

        // Undo restores both previous bodyText and prior Plain editor mode
        vm.undoLastAiApply()
        assertFalse(vm.uiState.value.isHtmlMode)
        assertEquals("お疲れ様です。\n日程を確定しました。", vm.uiState.value.bodyText)
    }

    private fun localMemoryPrefs(): SharedPreferences = object : SharedPreferences {
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
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
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
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
