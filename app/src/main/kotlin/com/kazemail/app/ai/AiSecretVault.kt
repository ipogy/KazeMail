package com.kazemail.app.ai

import com.kazemail.app.security.EncryptedBlob
import com.kazemail.app.security.SecretCipher
import java.net.URI
import java.util.Locale

/**
 * Encrypts and masks BYOK AI provider keys using Android Keystore AES-256-GCM
 * via [SecretCipher] and enforces safe endpoint URL validation.
 */
class AiSecretVault(
    private val secretCipher: SecretCipher,
) {
    fun encryptApiKey(rawApiKey: String?): EncryptedBlob? {
        val trimmed = rawApiKey?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return secretCipher.encrypt(trimmed.encodeToByteArray())
    }

    fun decryptApiKey(ciphertextB64: String?, ivB64: String?): String? {
        val cipher = ciphertextB64?.trim().orEmpty()
        val iv = ivB64?.trim().orEmpty()
        if (cipher.isEmpty() || iv.isEmpty()) return null
        return secretCipher.decrypt(
            EncryptedBlob(ciphertextB64 = cipher, ivB64 = iv),
        ).decodeToString().trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        fun maskApiKey(rawApiKey: String?): String? {
            val trimmed = rawApiKey?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            if (trimmed.length <= 4) return "••••"
            if (trimmed.length <= 10) {
                return "••••" + trimmed.takeLast(2)
            }
            val prefix = trimmed.take(4)
            val suffix = trimmed.takeLast(4)
            return "$prefix••••$suffix"
        }

        fun sanitizeEndpointUrl(providerType: AiProviderType, rawUrl: String?): String {
            val candidate = rawUrl?.trim().orEmpty().ifEmpty { providerType.defaultBaseUrl }.trimEnd('/')
            val parsed = runCatching { URI(candidate) }.getOrNull()
                ?: throw AiClientException(
                    kind = AiClientException.Kind.INVALID_CONFIG,
                    message = "エンドポイント URL の形式が不正です",
                )
            val scheme = parsed.scheme?.lowercase(Locale.US).orEmpty()
            val host = parsed.host?.lowercase(Locale.US).orEmpty()
            if (host.isBlank()) {
                throw AiClientException(
                    kind = AiClientException.Kind.INVALID_CONFIG,
                    message = "エンドポイントのホスト名が空です",
                )
            }
            val isLoopbackOrLan = host == "localhost" ||
                host == "127.0.0.1" ||
                host == "10.0.2.2" ||
                host.startsWith("192.168.") ||
                host.startsWith("10.")
            when (providerType) {
                AiProviderType.OPENAI_COMPATIBLE -> {
                    if (scheme != "https" && !(scheme == "http" && isLoopbackOrLan)) {
                        throw AiClientException(
                            kind = AiClientException.Kind.INVALID_CONFIG,
                            message = "外部エンドポイントには https:// を指定してください（ローカル接続のみ http:// 可）",
                        )
                    }
                }
                else -> {
                    if (scheme != "https") {
                        throw AiClientException(
                            kind = AiClientException.Kind.INVALID_CONFIG,
                            message = "${providerType.displayLabel} のエンドポイントは https:// が必須です",
                        )
                    }
                }
            }
            if (!parsed.rawQuery.isNullOrBlank() || !parsed.rawFragment.isNullOrBlank()) {
                throw AiClientException(
                    kind = AiClientException.Kind.INVALID_CONFIG,
                    message = "エンドポイント URL にクエリ文字列やフラグメントは含めないでください",
                )
            }
            return candidate
        }

        fun isEndpointValid(providerType: AiProviderType, rawUrl: String?): Boolean =
            runCatching { sanitizeEndpointUrl(providerType, rawUrl) }.isSuccess
    }
}
