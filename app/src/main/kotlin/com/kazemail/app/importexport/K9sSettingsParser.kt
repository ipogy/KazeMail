package com.kazemail.app.importexport

import com.kazemail.app.auth.OAuth2Providers
import com.kazemail.app.security.StoredSecurityMode
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class K9sImportedAccount(
    val uuid: String,
    val accountName: String,
    val email: String,
    val displayName: String,
    val incomingType: String,
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: StoredSecurityMode,
    val imapAuthType: String,
    val username: String,
    val incomingPassword: String?,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: StoredSecurityMode,
    val smtpAuthType: String,
    val smtpPassword: String?,
    val suggestedOAuthPresetId: String,
) {
    val isImapSupported: Boolean
        get() = incomingType.equals("IMAP", ignoreCase = true) && imapHost.isNotBlank()

    val usesOAuth2: Boolean
        get() = imapAuthType.equals("XOAUTH2", ignoreCase = true) ||
            smtpAuthType.equals("XOAUTH2", ignoreCase = true)
}

data class K9sImportResult(
    val settingsVersion: String?,
    val formatVersion: String?,
    val accounts: List<K9sImportedAccount>,
)

object K9sSettingsParser {
    private const val MAX_RAW_K9S_BYTES = 20 * 1024 * 1024
    private const val MAX_XML_BYTES = 10 * 1024 * 1024

    fun parseStream(input: InputStream): K9sImportResult {
        val rawBytes = readBounded(input, MAX_RAW_K9S_BYTES)
        if (rawBytes.isEmpty()) {
            throw IllegalArgumentException(".k9s ファイルが空です")
        }

        val xmlBytes = if (isZipHeader(rawBytes)) {
            extractXmlFromK9sZip(rawBytes)
        } else {
            rawBytes
        }
        return parseXmlBytes(xmlBytes)
    }

    fun parseXmlBytes(rawXmlBytes: ByteArray): K9sImportResult {
        var xmlText = rawXmlBytes.toString(StandardCharsets.UTF_8)
        if (xmlText.startsWith("\uFEFF")) {
            xmlText = xmlText.substring(1)
        }
        val lowerHeader = xmlText.take(4096).lowercase()
        if (lowerHeader.contains("<!doctype") || lowerHeader.contains("<!entity")) {
            throw SecurityException("Security policy rejected DOCTYPE/ENTITY in .k9s XML")
        }

        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = dbf.newDocumentBuilder().parse(
            ByteArrayInputStream(xmlText.toByteArray(StandardCharsets.UTF_8)),
        )
        val root = doc.documentElement
        if (root.tagName != "k9settings") {
            throw IllegalArgumentException("不正なファイル形式です (ルート要素: ${root.tagName})")
        }

        val settingsVersion = root.getAttribute("version").takeIf { it.isNotBlank() }
        val formatVersion = root.getAttribute("format").takeIf { it.isNotBlank() }

        val accountsEl = firstDirectChild(root, "accounts")
        val accountNodes = if (accountsEl != null) {
            directChildrenByTag(accountsEl, "account")
        } else {
            emptyList()
        }

        val imported = accountNodes.mapNotNull { parseAccountElement(it) }
        return K9sImportResult(
            settingsVersion = settingsVersion,
            formatVersion = formatVersion,
            accounts = imported,
        )
    }

    private fun parseAccountElement(accountEl: Element): K9sImportedAccount? {
        val uuid = accountEl.getAttribute("uuid").trim()
        val accountName = firstDirectChildText(accountEl, "name").orEmpty()
        val settingsMap = parseSettingsMap(firstDirectChild(accountEl, "settings"))

        val incomingEl = firstDirectChild(accountEl, "incoming-server")
        val incomingParsed = if (incomingEl != null) {
            parseServerElement(incomingEl, defaultType = "IMAP", defaultPort = 993)
        } else {
            settingsMap["storeUri"]?.let { parseLegacyServerUri(it, defaultType = "IMAP") }
        }

        val outgoingEl = firstDirectChild(accountEl, "outgoing-server")
        val outgoingParsed = if (outgoingEl != null) {
            parseServerElement(outgoingEl, defaultType = "SMTP", defaultPort = 465)
        } else {
            settingsMap["transportUri"]?.let { parseLegacyServerUri(it, defaultType = "SMTP") }
        }

        if (incomingParsed == null && outgoingParsed == null) {
            return null
        }

        val identitiesEl = firstDirectChild(accountEl, "identities")
        val firstIdentityEl = identitiesEl?.let { directChildrenByTag(it, "identity").firstOrNull() }
        val identityName = firstIdentityEl?.let { firstDirectChildText(it, "name") }?.takeIf { it.isNotBlank() }
        val identityEmail = firstIdentityEl?.let { firstDirectChildText(it, "email") }?.takeIf { it.isNotBlank() }

        val incomingUsername = incomingParsed?.username.orEmpty()
        val resolvedEmail = identityEmail
            ?: incomingUsername.takeIf { it.contains("@") }
            ?: accountName.takeIf { it.contains("@") }
            ?: ""
        val resolvedDisplayName = identityName
            ?: accountName.takeIf { it.isNotBlank() && it != resolvedEmail }
            ?: ""

        val imapHost = incomingParsed?.host.orEmpty()
        val imapPort = incomingParsed?.port ?: 993
        val imapSecurity = incomingParsed?.security ?: StoredSecurityMode.IMPLICIT_TLS
        val imapAuthType = incomingParsed?.authType ?: "PLAIN"

        val smtpHost = outgoingParsed?.host?.ifBlank { null }
            ?: imapHost.replaceFirst("imap.", "smtp.")
        val smtpPort = outgoingParsed?.port ?: 465
        val smtpSecurity = outgoingParsed?.security ?: StoredSecurityMode.IMPLICIT_TLS
        val smtpAuthType = outgoingParsed?.authType ?: imapAuthType

        val presetId = inferOAuthPreset(imapHost)

        return K9sImportedAccount(
            uuid = uuid,
            accountName = accountName.ifBlank { resolvedEmail.ifBlank { imapHost } },
            email = resolvedEmail,
            displayName = resolvedDisplayName,
            incomingType = incomingParsed?.type ?: "IMAP",
            imapHost = imapHost,
            imapPort = imapPort,
            imapSecurity = imapSecurity,
            imapAuthType = imapAuthType,
            username = incomingUsername.ifBlank { resolvedEmail },
            incomingPassword = incomingParsed?.password,
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            smtpSecurity = smtpSecurity,
            smtpAuthType = smtpAuthType,
            smtpPassword = outgoingParsed?.password,
            suggestedOAuthPresetId = presetId,
        )
    }

    private data class ParsedServerBlock(
        val type: String,
        val host: String,
        val port: Int,
        val security: StoredSecurityMode,
        val authType: String,
        val username: String,
        val password: String?,
    )

    private fun parseServerElement(
        serverEl: Element,
        defaultType: String,
        defaultPort: Int,
    ): ParsedServerBlock {
        val type = serverEl.getAttribute("type").ifBlank { defaultType }.uppercase()
        val host = firstDirectChildText(serverEl, "host").orEmpty().trim()
        val port = firstDirectChildText(serverEl, "port")?.trim()?.toIntOrNull() ?: defaultPort
        val secRaw = firstDirectChildText(serverEl, "connection-security").orEmpty().trim()
        val security = mapConnectionSecurity(secRaw, port, type)
        val authType = firstDirectChildText(serverEl, "authentication-type").orEmpty().trim()
            .ifBlank { "PLAIN" }
            .uppercase()
        val username = firstDirectChildText(serverEl, "username").orEmpty().trim()
        val password = firstDirectChildText(serverEl, "password")?.takeIf { it.isNotEmpty() }

        return ParsedServerBlock(
            type = type,
            host = host,
            port = port,
            security = security,
            authType = authType,
            username = username,
            password = password,
        )
    }

    private fun parseLegacyServerUri(rawUri: String, defaultType: String): ParsedServerBlock? {
        val uri = runCatching { URI(rawUri.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase().orEmpty()
        val type = when {
            scheme.startsWith("pop3") -> "POP3"
            scheme.startsWith("smtp") -> "SMTP"
            scheme.startsWith("imap") -> "IMAP"
            else -> defaultType
        }
        val security = when {
            scheme.contains("+ssl") -> StoredSecurityMode.IMPLICIT_TLS
            scheme.contains("+tls") -> StoredSecurityMode.START_TLS
            else -> StoredSecurityMode.PLAINTEXT
        }
        val defaultPort = when {
            type == "SMTP" && security == StoredSecurityMode.IMPLICIT_TLS -> 465
            type == "SMTP" -> 587
            security == StoredSecurityMode.IMPLICIT_TLS -> 993
            else -> 143
        }
        val host = uri.host.orEmpty()
        val port = if (uri.port > 0) uri.port else defaultPort

        val rawUserInfo = uri.rawUserInfo.orEmpty()
        val segments = rawUserInfo.split(':')
        var authType = "PLAIN"
        var username = ""
        var password: String? = null
        when (segments.size) {
            1 -> username = urlDecode(segments[0])
            2 -> {
                val first = urlDecode(segments[0])
                if (first in setOf("PLAIN", "CRAM_MD5", "EXTERNAL", "XOAUTH2", "AUTOMATIC")) {
                    authType = first
                    username = urlDecode(segments[1])
                } else {
                    username = first
                    password = urlDecode(segments[1]).takeIf { it.isNotEmpty() }
                }
            }
            else -> if (segments.size >= 3) {
                authType = urlDecode(segments[0]).ifBlank { "PLAIN" }
                username = urlDecode(segments[1])
                password = urlDecode(segments[2]).takeIf { it.isNotEmpty() }
            }
        }

        return ParsedServerBlock(
            type = type,
            host = host,
            port = port,
            security = security,
            authType = authType,
            username = username,
            password = password,
        )
    }

    private fun parseSettingsMap(settingsEl: Element?): Map<String, String> {
        if (settingsEl == null) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (valueEl in directChildrenByTag(settingsEl, "value")) {
            val key = valueEl.getAttribute("key").trim()
            if (key.isNotEmpty()) {
                map[key] = valueEl.textContent?.trim().orEmpty()
            }
        }
        return map
    }

    private fun mapConnectionSecurity(
        raw: String,
        port: Int,
        serverType: String,
    ): StoredSecurityMode =
        when (raw.uppercase()) {
            "SSL_TLS_REQUIRED", "SSL_TLS_IF_AVAILABLE", "SSL" -> StoredSecurityMode.IMPLICIT_TLS
            "STARTTLS_REQUIRED", "STARTTLS_IF_AVAILABLE", "STARTTLS", "TLS" -> StoredSecurityMode.START_TLS
            "NONE" -> StoredSecurityMode.PLAINTEXT
            else -> when {
                serverType == "SMTP" && (port == 587 || port == 25) -> StoredSecurityMode.START_TLS
                port == 143 || port == 110 -> StoredSecurityMode.START_TLS
                else -> StoredSecurityMode.IMPLICIT_TLS
            }
        }

    private fun inferOAuthPreset(imapHost: String): String {
        val h = imapHost.lowercase()
        return when {
            h.contains("gmail") || h.contains("googlemail") -> OAuth2Providers.Gmail.id
            h.contains("office365") || h.contains("outlook") || h.contains("hotmail") || h.contains("live.com") ->
                OAuth2Providers.Outlook.id
            else -> OAuth2Providers.Custom.id
        }
    }

    private fun isZipHeader(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun extractXmlFromK9sZip(zipBytes: ByteArray): ByteArray {
        var fallbackXml: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    val lowerName = entry.name.lowercase()
                    if (lowerName == "settings.xml" || lowerName.endsWith("/settings.xml")) {
                        return readBounded(zis, MAX_XML_BYTES)
                    }
                    if (fallbackXml == null && lowerName.endsWith(".xml")) {
                        fallbackXml = readBounded(zis, MAX_XML_BYTES)
                    }
                }
                zis.closeEntry()
            }
        }
        return fallbackXml
            ?: throw IllegalArgumentException(".k9s アーカイブ内に settings.xml が見つかりません")
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("ファイルサイズが上限 (${maxBytes / (1024 * 1024)}MB) を超えています")
            }
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun firstDirectChild(parent: Element, tagName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tagName) {
                return node as Element
            }
        }
        return null
    }

    private fun firstDirectChildText(parent: Element, tagName: String): String? =
        firstDirectChild(parent, tagName)?.textContent

    private fun directChildrenByTag(parent: Element, tagName: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tagName) {
                result += node as Element
            }
        }
        return result
    }

    private fun urlDecode(s: String): String =
        runCatching { URLDecoder.decode(s, StandardCharsets.UTF_8.name()) }.getOrDefault(s)
}
