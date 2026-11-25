package com.kazemail.app.ai

import android.content.SharedPreferences
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kazemail.app.data.AppPreferences
import com.kazemail.app.data.MessageRepository
import com.kazemail.app.data.db.AccountEntity
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiReferenceMaterialsTest {

    private lateinit var db: KazeMailDatabase
    private lateinit var messageRepository: MessageRepository
    private lateinit var appPreferences: AppPreferences
    private lateinit var secretVault: AiSecretVault
    private lateinit var tempDir: File

    private val fakeCipher = object : SecretCipher {
        override fun encrypt(plaintext: ByteArray) = EncryptedBlob(
            ciphertextB64 = Base64.getEncoder().encodeToString(plaintext),
            ivB64 = "TEST_IV",
        )

        override fun decrypt(blob: EncryptedBlob): ByteArray =
            Base64.getDecoder().decode(blob.ciphertextB64)
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("ai_ref_materials_test").toFile()
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
        appPreferences = AppPreferences(InMemPrefs())
        secretVault = AiSecretVault(fakeCipher)
    }

    @After
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun documentExtractorClassifiesAndExtractsTextOfficeAndRedactsSecrets() {
        assertEquals(AiReferenceFileKind.IMAGE, AiReferenceDocumentExtractor.classifyFileKind("photo.jpg", null))
        assertEquals(AiReferenceFileKind.PDF, AiReferenceDocumentExtractor.classifyFileKind("quote.PDF", "application/octet-stream"))
        assertEquals(AiReferenceFileKind.OFFICE_DOCUMENT, AiReferenceDocumentExtractor.classifyFileKind("notes.docx", null))
        assertEquals(AiReferenceFileKind.TEXT_DOCUMENT, AiReferenceDocumentExtractor.classifyFileKind("data.csv", null))
        assertEquals(AiReferenceFileKind.UNSUPPORTED, AiReferenceDocumentExtractor.classifyFileKind("archive.zip", "application/zip"))

        // Shift_JIS text file with sensitive bearer token
        val sjisFile = File(tempDir, "sjis_memo.txt")
        sjisFile.writeBytes("担当: 鈴木\nToken: Bearer sk-1234567890abcdef123456".toByteArray(Charset.forName("MS932")))
        val sjisDoc = AiReferenceDocumentExtractor.extractTextDocument(
            file = sjisFile,
            displayName = "sjis_memo.txt",
            mimeType = "text/plain",
            kind = AiReferenceFileKind.TEXT_DOCUMENT,
            redactSensitive = true,
        )
        assertNotNull(sjisDoc)
        assertTrue(sjisDoc!!.content.contains("担当: 鈴木"))
        assertFalse(sjisDoc.content.contains("sk-1234567890abcdef123456"))
        assertTrue(sjisDoc.redactionCount >= 1)

        // Synthetic .docx
        val docxFile = File(tempDir, "spec.docx")
        ZipOutputStream(docxFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                   <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                     <w:body>
                       <w:p><w:r><w:t>納期: 12月10日</w:t></w:r></w:p>
                       <w:p><w:r><w:t>予算上限: 50万円</w:t></w:r></w:p>
                     </w:body>
                   </w:document>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()
        }
        val docxDoc = AiReferenceDocumentExtractor.extractTextDocument(
            file = docxFile,
            displayName = "spec.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            kind = AiReferenceFileKind.OFFICE_DOCUMENT,
            redactSensitive = true,
        )
        assertNotNull(docxDoc)
        assertTrue(docxDoc!!.content.contains("納期: 12月10日"))
        assertTrue(docxDoc.content.contains("予算上限: 50万円"))

        // Synthetic .xlsx
        val xlsxFile = File(tempDir, "sheet.xlsx")
        ZipOutputStream(xlsxFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                   <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                     <si><t>品目A</t></si>
                     <si><t>合計金額</t></si>
                   </sst>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                   <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                     <sheetData>
                       <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>12000</v></c></row>
                       <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>24000</v></c></row>
                     </sheetData>
                   </worksheet>""".trimIndent().toByteArray(),
            )
            zos.closeEntry()
        }
        val xlsxDoc = AiReferenceDocumentExtractor.extractTextDocument(
            file = xlsxFile,
            displayName = "sheet.xlsx",
            mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            kind = AiReferenceFileKind.OFFICE_DOCUMENT,
            redactSensitive = true,
        )
        assertNotNull(xlsxDoc)
        assertTrue(xlsxDoc!!.content.contains("品目A\t12000"))
        assertTrue(xlsxDoc.content.contains("合計金額\t24000"))
    }

    @Test
    fun multiProviderClientSerializesImagesAndPdfsProperlyForAllProviders() {
        val client = MultiProviderAiClient(
            transport = object : AiHttpTransport {
                override suspend fun execute(request: AiHttpRequest): AiHttpResponse =
                    error("not used in buildHttpRequest")
            },
        )
        val req = AiCompletionRequest(
            goal = AiWritingGoal.REPLY,
            systemPrompt = "System",
            userPrompt = "参照資料を確認してください",
            attachments = listOf(
                AiBinaryAttachment("photo.jpg", "image/jpeg", "SU1BR0U=", 5),
                AiBinaryAttachment("quote.pdf", "application/pdf", "UERGRg==", 4),
            ),
        )

        // Gemini inlineData
        val geminiReq = client.buildHttpRequest(
            AiProviderConnectionConfig(AiProviderType.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-4-hydrogen", "k"),
            req,
        )
        assertEquals(90_000, geminiReq.readTimeoutMs)
        val geminiJson = Json.parseToJsonElement(geminiReq.bodyJson!!).jsonObject
        val geminiParts = geminiJson["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(3, geminiParts.size)
        assertEquals("image/jpeg", geminiParts[1].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals("application/pdf", geminiParts[2].jsonObject["inlineData"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content)

        // OpenAI content array (image_url + file)
        val openAiReq = client.buildHttpRequest(
            AiProviderConnectionConfig(AiProviderType.OPENAI, "https://api.openai.com/v1", "gpt-6-luna", "sk-test"),
            req,
        )
        val openAiJson = Json.parseToJsonElement(openAiReq.bodyJson!!).jsonObject
        val openAiContent = openAiJson["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals("text", openAiContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", openAiContent[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,SU1BR0U=", openAiContent[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("file", openAiContent[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("quote.pdf", openAiContent[2].jsonObject["file"]!!.jsonObject["filename"]!!.jsonPrimitive.content)

        // Anthropic image + document blocks
        val claudeReq = client.buildHttpRequest(
            AiProviderConnectionConfig(AiProviderType.ANTHROPIC, "https://api.anthropic.com/v1", "claude-sonnet-5-5", "sk-ant-test"),
            req,
        )
        val claudeJson = Json.parseToJsonElement(claudeReq.bodyJson!!).jsonObject
        val claudeContent = claudeJson["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("image", claudeContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("document", claudeContent[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("quote.pdf", claudeContent[1].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("text", claudeContent[2].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun searchReferenceEmailsPrepareContextsAndEncodeBinaryInService() = runTest {
        val accId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "me@example.jp",
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
        val primaryId = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accId,
                folderId = folderId,
                uid = 1L,
                senderName = "山田",
                senderAddress = "yamada@example.jp",
                subject = "新案件の打ち合わせ依頼",
                snippet = "日程を調整したいです",
                receivedAt = 2_000_000L,
            ),
        )
        val refId = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accId,
                folderId = folderId,
                uid = 2L,
                senderName = "佐藤",
                senderAddress = "sato@example.jp",
                subject = "前回のお見積書送付",
                snippet = "金額は30万円となります",
                receivedAt = 1_000_000L,
            ),
        )
        db.messageBodyDao().upsert(MessageBodyEntity(metaId = refId, plainText = "前回見積金額は合計30万円（税込）です。"))

        appPreferences.setAiFeaturesEnabled(true)
        appPreferences.setAiPrivacyConsentAccepted(true)

        var capturedRequest: AiCompletionRequest? = null
        val fakeClient = object : AiClient {
            override suspend fun verifyConnection(config: AiProviderConnectionConfig) = error("unused")
            override suspend fun complete(config: AiProviderConnectionConfig, request: AiCompletionRequest): AiCompletionResult {
                capturedRequest = request
                return AiCompletionResult(
                    text = "佐藤様の前回見積（30万円）を踏まえ回答いたします。",
                    providerType = config.providerType,
                    modelId = config.modelId,
                    latencyMs = 40L,
                )
            }
        }
        val fakeEncoder = object : AiBinaryMediaEncoder {
            override fun encodeFile(
                spec: AiReferenceFileSpec,
                providerType: AiProviderType,
                baseUrl: String,
                remainingBudgetBytes: Int,
            ): List<AiBinaryAttachment> = listOf(
                AiBinaryAttachment(
                    displayName = spec.displayName,
                    mimeType = "image/jpeg",
                    base64Data = "REVNT19JTV9BR0U=",
                    byteSize = 123,
                ),
            )
        }

        val service = AiPreparationService(
            db = db,
            appPreferences = appPreferences,
            secretVault = secretVault,
            aiClient = fakeClient,
            messageRepository = messageRepository,
            binaryEncoder = fakeEncoder,
        )
        service.saveProfile(
            displayName = "Gemini",
            providerType = AiProviderType.GEMINI,
            baseUrl = AiProviderType.GEMINI.defaultBaseUrl,
            modelId = AiProviderType.GEMINI.defaultModelId,
            rawApiKeyInput = "AIzaTestRefKey999999999",
        )

        val candidates = service.searchReferenceEmails(
            accountId = accId,
            query = "見積",
            excludeMetaIds = setOf(primaryId),
        )
        assertEquals(1, candidates.size)
        assertEquals(refId, candidates[0].metaId)

        val extraContexts = service.prepareExtraEmailContexts(listOf(refId), "me@example.jp")
        val fakeImg = File(tempDir, "diagram.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val fileSpecs = listOf(
            AiReferenceFileSpec(
                id = "img1",
                displayName = "diagram.png",
                mimeType = "image/png",
                localPath = fakeImg.absolutePath,
                sizeBytes = 3L,
                kind = AiReferenceFileKind.IMAGE,
            ),
        )
        val bundle = service.prepareFileReferenceBundle(fileSpecs)
        val prompt = service.buildPromptPreview(
            goal = AiWritingGoal.REPLY,
            tone = AiReplyTone.FORMAL_BUSINESS,
            context = null,
            userInstruction = "前回見積を踏まえて回答",
            currentSubject = "Re: 新案件の打ち合わせ依頼",
            currentBodyDraft = "",
            extraReferenceEmails = extraContexts,
            extraTextDocuments = bundle.inlineTextDocuments,
            binaryAttachmentDescriptors = bundle.binaryDescriptorLabels,
        )
        assertTrue(prompt.userPrompt.contains("【追加参照メール 1: 前回のお見積書送付】"))
        assertTrue(prompt.userPrompt.contains("合計30万円（税込）"))
        assertTrue(prompt.userPrompt.contains("diagram.png"))

        service.generateCompletion(prompt, referenceFiles = fileSpecs)
        val sent = requireNotNull(capturedRequest)
        assertEquals(1, sent.attachments.size)
        assertEquals("diagram.png", sent.attachments[0].displayName)
    }

    @Test
    fun clarificationPlanAndSubjectParsersHandleJsonFencesAndPlainFallback() {
        val fencedJson = """
            ```json
            {
              "summary": "日程と添付の扱いを確認します。",
              "questions": [
                {
                  "id": "date_choice",
                  "question": "どちらの日程で回答しますか？",
                  "allowMultiple": false,
                  "options": ["11月28日 14:00", "12月2日 10:00"],
                  "customPlaceholder": "別の候補日時を入力"
                },
                {
                  "id": "include_items",
                  "question": "返信に盛り込む項目を選んでください",
                  "allowMultiple": true,
                  "options": ["見積書送付のお礼", "NDA確認中である旨"]
                }
              ]
            }
            ```
        """.trimIndent()

        val plan = AiPromptBuilder.parseClarificationPlanResponse(fencedJson)
        assertEquals("日程と添付の扱いを確認します。", plan.summary)
        assertEquals(2, plan.questions.size)
        assertEquals("date_choice", plan.questions[0].id)
        assertFalse(plan.questions[0].allowMultiple)
        assertEquals(listOf("11月28日 14:00", "12月2日 10:00"), plan.questions[0].options)
        assertTrue(plan.questions[1].allowMultiple)

        val fallbackPlan = AiPromptBuilder.parseClarificationPlanResponse(
            "1. 来週の面談方法（オンラインか対面か）を指定しますか？\n2. 見積書の差し替え期限を明記しますか？",
        )
        assertEquals(2, fallbackPlan.questions.size)
        assertTrue(fallbackPlan.questions[0].options.isNotEmpty())

        val subjectsJson = AiPromptBuilder.parseSubjectSuggestionsResponse(
            """```json
               {"subjects":["【ご確認】11月28日お打ち合わせの件","お打ち合わせ日程確定のご連絡","来週のお打ち合わせに関するご返信"]}
               ```""".trimIndent(),
        )
        assertEquals(
            listOf("【ご確認】11月28日お打ち合わせの件", "お打ち合わせ日程確定のご連絡", "来週のお打ち合わせに関するご返信"),
            subjectsJson,
        )

        val fallbackSubjects = AiPromptBuilder.parseSubjectSuggestionsResponse(
            "1. 件名: 「納期変更のお願い」\n2. 納品日程調整のご相談",
        )
        assertEquals(listOf("納期変更のお願い", "納品日程調整のご相談"), fallbackSubjects)
    }

    @Test
    fun clarificationAnswerReducerSupportsSingleMultiAndCustomInputs() {
        val singleQ = AiClarificationQuestion(
            id = "q_single",
            question = "回答方針",
            allowMultiple = false,
            options = listOf("承諾", "辞退"),
        )
        val multiQ = AiClarificationQuestion(
            id = "q_multi",
            question = "確認事項",
            allowMultiple = true,
            options = listOf("料金", "納期", "サポート"),
        )

        var map = emptyMap<String, AiClarificationAnswer>()
        map = AiClarificationAnswerReducer.toggleOption(map, singleQ, "承諾")
        assertEquals(listOf("承諾"), map["q_single"]?.selectedOptions)
        map = AiClarificationAnswerReducer.toggleOption(map, singleQ, "辞退")
        assertEquals(listOf("辞退"), map["q_single"]?.selectedOptions)
        map = AiClarificationAnswerReducer.updateCustomInput(map, singleQ, "条件付きで回答")
        assertEquals("条件付きで回答", map["q_single"]?.customInput)

        map = AiClarificationAnswerReducer.toggleOption(map, multiQ, "料金")
        map = AiClarificationAnswerReducer.toggleOption(map, multiQ, "納期")
        assertEquals(listOf("料金", "納期"), map["q_multi"]?.selectedOptions)
        map = AiClarificationAnswerReducer.toggleOption(map, multiQ, "料金")
        assertEquals(listOf("納期"), map["q_multi"]?.selectedOptions)
    }

    private class InMemPrefs : SharedPreferences {
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
                pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
