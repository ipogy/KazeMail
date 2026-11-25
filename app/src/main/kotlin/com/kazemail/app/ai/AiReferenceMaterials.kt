package com.kazemail.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.kazemail.app.ui.viewer.HtmlEmailDetector
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

interface AiBinaryMediaEncoder {
    fun encodeFile(
        spec: AiReferenceFileSpec,
        providerType: AiProviderType,
        baseUrl: String,
        remainingBudgetBytes: Int,
    ): List<AiBinaryAttachment>
}

object AiReferenceDocumentExtractor {
    const val MAX_TEXT_READ_BYTES = 256 * 1024
    const val MAX_TEXT_CHARS_PER_DOC = 4000
    const val MAX_ZIP_ENTRY_BYTES = 2 * 1024 * 1024
    const val MAX_ZIP_ENTRIES = 40

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    private val textExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml",
        "log", "ini", "conf", "cfg", "properties", "eml", "html", "htm",
        "sql", "kt", "java", "py", "js", "ts", "sh", "bat", "gradle", "kts",
    )
    private val officeExtensions = setOf("docx", "xlsx", "pptx")

    fun classifyFileKind(displayName: String, mimeType: String?): AiReferenceFileKind {
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()

        return when {
            mime == "application/pdf" || ext == "pdf" -> AiReferenceFileKind.PDF
            mime.startsWith("image/") || ext in imageExtensions -> AiReferenceFileKind.IMAGE
            ext in officeExtensions ||
                mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
                mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                AiReferenceFileKind.OFFICE_DOCUMENT
            mime.startsWith("text/") ||
                mime == "application/json" ||
                mime == "application/xml" ||
                mime == "application/csv" ||
                mime == "application/javascript" ||
                mime == "application/x-yaml" ||
                mime == "message/rfc822" ||
                ext in textExtensions -> AiReferenceFileKind.TEXT_DOCUMENT
            else -> AiReferenceFileKind.UNSUPPORTED
        }
    }

    fun normalizeMimeType(displayName: String, rawMime: String?, kind: AiReferenceFileKind): String {
        val clean = rawMime?.substringBefore(';')?.trim()?.lowercase(Locale.US)
            ?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
        if (clean != null) return clean
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
        return when (kind) {
            AiReferenceFileKind.PDF -> "application/pdf"
            AiReferenceFileKind.IMAGE -> when (ext) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
            AiReferenceFileKind.OFFICE_DOCUMENT -> when (ext) {
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                else -> "application/octet-stream"
            }
            AiReferenceFileKind.TEXT_DOCUMENT -> when (ext) {
                "json" -> "application/json"
                "csv" -> "text/csv"
                "html", "htm" -> "text/html"
                "eml" -> "message/rfc822"
                else -> "text/plain"
            }
            AiReferenceFileKind.UNSUPPORTED -> "application/octet-stream"
        }
    }

    fun extractTextDocument(
        file: File,
        displayName: String,
        mimeType: String,
        kind: AiReferenceFileKind,
        redactSensitive: Boolean,
        maxChars: Int = MAX_TEXT_CHARS_PER_DOC,
    ): AiPreparedTextDocument? {
        if (!file.exists() || !file.isFile) return null
        val rawExtracted = when (kind) {
            AiReferenceFileKind.TEXT_DOCUMENT -> readAndDecodeText(file, displayName, mimeType)
            AiReferenceFileKind.OFFICE_DOCUMENT -> extractOfficeDocumentText(file, displayName)
            else -> return null
        }
        if (rawExtracted.isBlank()) return null

        val redactionRes = if (redactSensitive) {
            AiSensitiveDataRedactor.redact(rawExtracted)
        } else {
            RedactionResult(rawExtracted, 0)
        }

        val limit = maxChars.coerceAtLeast(300)
        val wasTruncated = redactionRes.text.length > limit
        val finalText = if (wasTruncated) {
            redactionRes.text.take(limit).trimEnd() + "\n…(省略)"
        } else {
            redactionRes.text
        }
        return AiPreparedTextDocument(
            displayName = displayName,
            mimeType = mimeType,
            content = finalText,
            wasTruncated = wasTruncated,
            redactionCount = redactionRes.redactionCount,
        )
    }

    private fun readAndDecodeText(file: File, displayName: String, mimeType: String): String {
        val rawBytes = readBoundedBytes(file, MAX_TEXT_READ_BYTES)
        val decoded = decodeBytesSmart(rawBytes)
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lowerName = displayName.lowercase(Locale.US)
        val isHtml = mimeType.equals("text/html", ignoreCase = true) ||
            lowerName.endsWith(".html") ||
            lowerName.endsWith(".htm") ||
            HtmlEmailDetector.looksLikeRawHtmlDocument(decoded)
        return if (isHtml) {
            HtmlEmailDetector.stripHtmlFallbackText(decoded).trim()
        } else {
            decoded.trim()
        }
    }

    internal fun extractOfficeDocumentText(file: File, displayName: String): String {
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
        val xmlEntries = linkedMapOf<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zis ->
            var count = 0
            while (count < MAX_ZIP_ENTRIES) {
                val entry = zis.nextEntry ?: break
                count++
                if (entry.isDirectory) continue
                val name = entry.name
                val relevant = when (ext) {
                    "docx" -> name == "word/document.xml" ||
                        name.startsWith("word/header") ||
                        name.startsWith("word/footer")
                    "pptx" -> name.startsWith("ppt/slides/slide") && name.endsWith(".xml")
                    "xlsx" -> name == "xl/sharedStrings.xml" ||
                        (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
                    else -> name.endsWith(".xml")
                }
                if (relevant) {
                    xmlEntries[name] = readZipEntryBounded(zis, MAX_ZIP_ENTRY_BYTES)
                }
            }
        }
        if (xmlEntries.isEmpty()) return ""

        return when (ext) {
            "docx" -> {
                val orderedKeys = xmlEntries.keys.sortedBy {
                    when {
                        it == "word/document.xml" -> "0_$it"
                        it.startsWith("word/header") -> "1_$it"
                        else -> "2_$it"
                    }
                }
                orderedKeys.mapNotNull { key ->
                    extractXmlTextBlocks(xmlEntries.getValue(key), paragraphTags = setOf("p", "tr"))
                        .takeIf { it.isNotBlank() }
                }.joinToString("\n\n")
            }
            "pptx" -> {
                val slideRegex = Regex("""slide(\d+)\.xml$""")
                val orderedSlides = xmlEntries.keys.sortedBy { key ->
                    slideRegex.find(key)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE
                }
                orderedSlides.mapIndexedNotNull { idx, key ->
                    val txt = extractXmlTextBlocks(xmlEntries.getValue(key), paragraphTags = setOf("p"))
                    txt.takeIf { it.isNotBlank() }?.let { "[スライド ${idx + 1}]\n$it" }
                }.joinToString("\n\n")
            }
            "xlsx" -> {
                val sharedStrings = xmlEntries["xl/sharedStrings.xml"]
                    ?.let(::parseXlsxSharedStrings)
                    .orEmpty()
                val sheetRegex = Regex("""sheet(\d+)\.xml$""")
                val orderedSheets = xmlEntries.keys
                    .filter { it.startsWith("xl/worksheets/sheet") }
                    .sortedBy { key ->
                        sheetRegex.find(key)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE
                    }
                orderedSheets.mapIndexedNotNull { idx, key ->
                    val txt = parseXlsxWorksheet(xmlEntries.getValue(key), sharedStrings)
                    txt.takeIf { it.isNotBlank() }?.let { "[シート ${idx + 1}]\n$it" }
                }.joinToString("\n\n")
            }
            else -> xmlEntries.values.map {
                extractXmlTextBlocks(it, paragraphTags = setOf("p", "row", "tr"))
            }.filter { it.isNotBlank() }.joinToString("\n\n")
        }.trim()
    }

    private fun extractXmlTextBlocks(xmlBytes: ByteArray, paragraphTags: Set<String>): String {
        val doc = parseXmlSecurely(xmlBytes) ?: return ""
        val sb = StringBuilder()
        fun walk(node: Node) {
            val local = node.localName ?: node.nodeName.substringAfter(':')
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                sb.append(node.nodeValue.orEmpty())
                return
            }
            if (local == "tab") {
                sb.append('\t')
                return
            }
            if (local == "br") {
                sb.append('\n')
                return
            }
            val children = node.childNodes
            for (i in 0 until children.length) {
                walk(children.item(i))
            }
            if (local in paragraphTags && sb.isNotEmpty() && sb.last() != '\n') {
                sb.append('\n')
            }
        }
        walk(doc.documentElement)
        return sb.toString().lines().joinToString("\n") { it.trimEnd() }.trim()
    }

    private fun parseXlsxSharedStrings(xmlBytes: ByteArray): List<String> {
        val doc = parseXmlSecurely(xmlBytes) ?: return emptyList()
        val siNodes = doc.getElementsByTagName("*")
        val items = mutableListOf<String>()
        for (i in 0 until siNodes.length) {
            val node = siNodes.item(i)
            val local = node.localName ?: node.nodeName.substringAfter(':')
            if (local == "si") {
                items += node.textContent.orEmpty().trim()
            }
        }
        return items
    }

    private fun parseXlsxWorksheet(xmlBytes: ByteArray, sharedStrings: List<String>): String {
        val doc = parseXmlSecurely(xmlBytes) ?: return ""
        val allNodes = doc.getElementsByTagName("*")
        val rowsOut = mutableListOf<String>()
        for (i in 0 until allNodes.length) {
            val rowNode = allNodes.item(i)
            val rowLocal = rowNode.localName ?: rowNode.nodeName.substringAfter(':')
            if (rowLocal != "row") continue
            val cellTexts = mutableListOf<String>()
            val children = rowNode.childNodes
            for (cIdx in 0 until children.length) {
                val cell = children.item(cIdx)
                val cellLocal = cell.localName ?: cell.nodeName.substringAfter(':')
                if (cellLocal != "c") continue
                val cellType = cell.attributes?.getNamedItem("t")?.nodeValue
                var vText = ""
                var inlineText = ""
                val cellChildren = cell.childNodes
                for (k in 0 until cellChildren.length) {
                    val part = cellChildren.item(k)
                    val partLocal = part.localName ?: part.nodeName.substringAfter(':')
                    if (partLocal == "v") {
                        vText = part.textContent.orEmpty().trim()
                    } else if (partLocal == "is") {
                        inlineText = part.textContent.orEmpty().trim()
                    }
                }
                val resolved = when (cellType) {
                    "s" -> vText.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
                    "inlineStr" -> inlineText
                    else -> vText.ifEmpty { inlineText }
                }
                if (resolved.isNotEmpty()) {
                    cellTexts += resolved
                }
            }
            if (cellTexts.isNotEmpty()) {
                rowsOut += cellTexts.joinToString("\t")
            }
        }
        return rowsOut.joinToString("\n")
    }

    private fun parseXmlSecurely(xmlBytes: ByteArray): org.w3c.dom.Document? =
        runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                isExpandEntityReferences = false
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        }.getOrNull()

    private fun decodeBytesSmart(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val utf8Decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        runCatching {
            return utf8Decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }
        runCatching {
            val sjis = Charset.forName("MS932").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return sjis.decode(ByteBuffer.wrap(bytes)).toString()
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun readBoundedBytes(file: File, maxBytes: Int): ByteArray =
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var total = 0
            while (total < maxBytes) {
                val toRead = minOf(buf.size, maxBytes - total)
                val read = input.read(buf, 0, toRead)
                if (read <= 0) break
                out.write(buf, 0, read)
                total += read
            }
            out.toByteArray()
        }

    private fun readZipEntryBounded(zis: ZipInputStream, maxBytes: Int): ByteArray {
        val buf = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        while (total < maxBytes) {
            val toRead = minOf(buf.size, maxBytes - total)
            val read = zis.read(buf, 0, toRead)
            if (read <= 0) break
            out.write(buf, 0, read)
            total += read
        }
        return out.toByteArray()
    }
}

object AiReferenceStaging {
    private const val STAGE_DIR_NAME = "ai_ref_stage"

    fun stageUri(context: Context, uri: Uri): AiReferenceFileSpec {
        val resolver = context.contentResolver
        var displayName = "file"
        var reportedSize = -1L

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        reportedSize = cursor.getLong(sizeIdx)
                    }
                }
            }

        val rawMime = resolver.getType(uri)
        val kind = AiReferenceDocumentExtractor.classifyFileKind(displayName, rawMime)
        val mime = AiReferenceDocumentExtractor.normalizeMimeType(displayName, rawMime, kind)

        val stageDir = File(context.cacheDir, STAGE_DIR_NAME).apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val id = UUID.randomUUID().toString()
        val destFile = File(stageDir, "${id}_$safeName")

        resolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("ファイルを開けませんでした: $displayName")

        val actualSize = destFile.length().takeIf { it > 0L } ?: reportedSize.coerceAtLeast(0L)
        return buildSpecFromStagedFile(id, displayName, mime, kind, destFile, actualSize)
    }

    fun stageLocalFile(
        context: Context,
        sourceFile: File,
        displayName: String,
        rawMimeType: String?,
        customId: String? = null,
    ): AiReferenceFileSpec {
        require(sourceFile.exists() && sourceFile.isFile) { "ファイルが存在しません: $displayName" }
        val kind = AiReferenceDocumentExtractor.classifyFileKind(displayName, rawMimeType)
        val mime = AiReferenceDocumentExtractor.normalizeMimeType(displayName, rawMimeType, kind)

        val stageDir = File(context.cacheDir, STAGE_DIR_NAME).apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val id = customId ?: UUID.randomUUID().toString()
        val fileToken = UUID.randomUUID().toString()
        val destFile = File(stageDir, "${fileToken}_$safeName")
        sourceFile.inputStream().use { input ->
            destFile.outputStream().use { output -> input.copyTo(output) }
        }
        return buildSpecFromStagedFile(id, displayName, mime, kind, destFile, destFile.length())
    }

    private fun buildSpecFromStagedFile(
        id: String,
        displayName: String,
        mime: String,
        kind: AiReferenceFileKind,
        file: File,
        sizeBytes: Long,
    ): AiReferenceFileSpec {
        var preview: String? = null
        var warning: String? = null
        if (kind == AiReferenceFileKind.TEXT_DOCUMENT || kind == AiReferenceFileKind.OFFICE_DOCUMENT) {
            val extracted = AiReferenceDocumentExtractor.extractTextDocument(
                file = file,
                displayName = displayName,
                mimeType = mime,
                kind = kind,
                redactSensitive = true,
                maxChars = 300,
            )
            if (extracted == null) {
                warning = "テキストを抽出できませんでした"
            } else {
                preview = extracted.content.replace('\n', ' ').take(120)
            }
        } else if (kind == AiReferenceFileKind.UNSUPPORTED) {
            warning = "テキスト・画像・PDF・Office 文書以外は AI へ内容送信できません"
        }
        return AiReferenceFileSpec(
            id = id,
            displayName = displayName,
            mimeType = mime,
            localPath = file.absolutePath,
            sizeBytes = sizeBytes,
            kind = kind,
            extractedPreview = preview,
            warningNote = warning,
        )
    }
}

class AndroidAiBinaryMediaEncoder : AiBinaryMediaEncoder {

    override fun encodeFile(
        spec: AiReferenceFileSpec,
        providerType: AiProviderType,
        baseUrl: String,
        remainingBudgetBytes: Int,
    ): List<AiBinaryAttachment> {
        if (remainingBudgetBytes <= 0) {
            throw AiClientException(
                kind = AiClientException.Kind.INVALID_CONFIG,
                message = "AI に添付する画像・PDF の合計サイズ上限を超えています",
            )
        }
        val file = File(spec.localPath)
        if (!file.exists() || !file.isFile) {
            throw AiClientException(
                kind = AiClientException.Kind.INVALID_CONFIG,
                message = "参照ファイルが見つかりません: ${spec.displayName}",
            )
        }

        return when (spec.kind) {
            AiReferenceFileKind.IMAGE -> listOf(encodeImage(file, spec.displayName, spec.mimeType, remainingBudgetBytes))
            AiReferenceFileKind.PDF -> encodePdf(file, spec.displayName, providerType, baseUrl, remainingBudgetBytes)
            else -> emptyList()
        }
    }

    private fun encodeImage(
        file: File,
        displayName: String,
        mimeType: String,
        remainingBudgetBytes: Int,
    ): AiBinaryAttachment {
        val rawLength = file.length()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        val exifOrientation = readExifOrientation(file.absolutePath)
        val needsRotation = exifOrientation != ExifInterface.ORIENTATION_NORMAL &&
            exifOrientation != ExifInterface.ORIENTATION_UNDEFINED
        val webSafeMime = mimeType.lowercase(Locale.US) in setOf(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif",
        )
        val normalizedMime = if (mimeType.equals("image/jpg", ignoreCase = true)) "image/jpeg" else mimeType

        if (
            webSafeMime &&
            !needsRotation &&
            maxDim in 1..MAX_IMAGE_DIMENSION &&
            rawLength in 1..minOf(MAX_SINGLE_IMAGE_BYTES.toLong(), remainingBudgetBytes.toLong())
        ) {
            val rawBytes = file.readBytes()
            return AiBinaryAttachment(
                displayName = displayName,
                mimeType = normalizedMime,
                base64Data = Base64.getEncoder().encodeToString(rawBytes),
                byteSize = rawBytes.size,
            )
        }

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIMENSION)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        var bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            ?: throw AiClientException(
                kind = AiClientException.Kind.INVALID_CONFIG,
                message = "画像を開けませんでした ($displayName)",
            )

        bitmap = scaleDownIfNeeded(bitmap, MAX_IMAGE_DIMENSION)
        bitmap = rotateByExifIfNeeded(bitmap, exifOrientation)

        val cap = minOf(MAX_SINGLE_IMAGE_BYTES, remainingBudgetBytes)
        val jpegBytes = try {
            compressBitmapToJpegBudget(bitmap, cap)
        } finally {
            bitmap.recycle()
        }
        return AiBinaryAttachment(
            displayName = displayName,
            mimeType = "image/jpeg",
            base64Data = Base64.getEncoder().encodeToString(jpegBytes),
            byteSize = jpegBytes.size,
        )
    }

    private fun encodePdf(
        file: File,
        displayName: String,
        providerType: AiProviderType,
        baseUrl: String,
        remainingBudgetBytes: Int,
    ): List<AiBinaryAttachment> {
        val supportsNativePdf = providerType == AiProviderType.GEMINI ||
            providerType == AiProviderType.ANTHROPIC ||
            providerType == AiProviderType.OPENAI ||
            (providerType == AiProviderType.OPENAI_COMPATIBLE &&
                baseUrl.contains("openrouter.ai", ignoreCase = true))

        val fileLen = file.length()
        if (supportsNativePdf && fileLen in 1..minOf(MAX_NATIVE_PDF_BYTES.toLong(), remainingBudgetBytes.toLong())) {
            val rawBytes = file.readBytes()
            return listOf(
                AiBinaryAttachment(
                    displayName = displayName,
                    mimeType = "application/pdf",
                    base64Data = Base64.getEncoder().encodeToString(rawBytes),
                    byteSize = rawBytes.size,
                ),
            )
        }

        if (fileLen > MAX_RENDERABLE_PDF_FILE_BYTES) {
            throw AiClientException(
                kind = AiClientException.Kind.INVALID_CONFIG,
                message = "PDF が大きすぎます ($displayName)。15MB 以下のファイルをお試しください",
            )
        }

        return renderPdfPagesAsJpegs(file, displayName, remainingBudgetBytes)
    }

    private fun renderPdfPagesAsJpegs(
        file: File,
        displayName: String,
        remainingBudgetBytes: Int,
    ): List<AiBinaryAttachment> {
        val attachments = mutableListOf<AiBinaryAttachment>()
        var budgetLeft = remainingBudgetBytes
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val pageCount = minOf(renderer.pageCount, MAX_PDF_RENDER_PAGES)
                for (pageIndex in 0 until pageCount) {
                    if (budgetLeft <= 64 * 1024) break
                    renderer.openPage(pageIndex).use { page ->
                        val scale = (MAX_PDF_PAGE_WIDTH.toFloat() / page.width.coerceAtLeast(1)).coerceIn(0.4f, 2.0f)
                        val width = (page.width * scale).toInt().coerceIn(320, MAX_PDF_PAGE_WIDTH)
                        val height = (page.height * scale).toInt().coerceIn(320, 2000)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val encoded = try {
                            Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val perPageBudget = minOf(MAX_SINGLE_IMAGE_BYTES, budgetLeft)
                            compressBitmapToJpegBudget(bitmap, perPageBudget)
                        } finally {
                            bitmap.recycle()
                        }
                        budgetLeft -= encoded.size
                        attachments += AiBinaryAttachment(
                            displayName = "$displayName (p.${pageIndex + 1})",
                            mimeType = "image/jpeg",
                            base64Data = Base64.getEncoder().encodeToString(encoded),
                            byteSize = encoded.size,
                        )
                    }
                }
            }
        }
        if (attachments.isEmpty()) {
            throw AiClientException(
                kind = AiClientException.Kind.INVALID_CONFIG,
                message = "PDF ページを読み取れませんでした: $displayName",
            )
        }
        return attachments
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var inSample = 1
        val largest = maxOf(width, height)
        if (largest > maxDim) {
            while ((largest / inSample) > maxDim * 2) {
                inSample *= 2
            }
        }
        return inSample.coerceAtLeast(1)
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= maxDim || largest <= 0) return bitmap
        val ratio = maxDim.toFloat() / largest.toFloat()
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun readExifOrientation(path: String): Int =
        runCatching {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun rotateByExifIfNeeded(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun compressBitmapToJpegBudget(bitmap: Bitmap, maxBytes: Int): ByteArray {
        var quality = 85
        while (quality >= 45) {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val bytes = baos.toByteArray()
            if (bytes.size <= maxBytes || quality == 45) return bytes
            quality -= 15
        }
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 45, baos)
        return baos.toByteArray()
    }

    companion object {
        const val MAX_TOTAL_BINARY_BYTES = 4_200_000
        const val MAX_BINARY_FILES = 4
        private const val MAX_IMAGE_DIMENSION = 1600
        private const val MAX_SINGLE_IMAGE_BYTES = 1_400_000
        private const val MAX_NATIVE_PDF_BYTES = 3_500_000
        private const val MAX_RENDERABLE_PDF_FILE_BYTES = 15 * 1024 * 1024L
        private const val MAX_PDF_PAGE_WIDTH = 1240
        private const val MAX_PDF_RENDER_PAGES = 4
    }
}
