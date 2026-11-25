package com.kazemail.app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class AiProviderConnectionConfig(
    val providerType: AiProviderType,
    val baseUrl: String,
    val modelId: String,
    val apiKey: String?,
)

interface AiClient {
    suspend fun verifyConnection(config: AiProviderConnectionConfig): AiConnectionTestResult
    suspend fun complete(
        config: AiProviderConnectionConfig,
        request: AiCompletionRequest,
    ): AiCompletionResult
}

/**
 * Unified direct-HTTPS client for Gemini, OpenAI, Anthropic, and OpenAI-compatible endpoints.
 * Keeps API keys strictly in request headers (never query URLs).
 */
class MultiProviderAiClient(
    private val transport: AiHttpTransport = HttpUrlConnectionAiTransport(),
    private val json: Json = defaultJson,
) : AiClient {

    override suspend fun verifyConnection(config: AiProviderConnectionConfig): AiConnectionTestResult {
        return try {
            val normalized = validateAndNormalize(config)
            val probeRequest = AiCompletionRequest(
                goal = AiWritingGoal.NEW_DRAFT,
                systemPrompt = "You are a connectivity probe.",
                userPrompt = "Reply with OK.",
                temperature = null,
                maxOutputTokens = 512,
                isProbe = true,
            )
            val result = executeCompletion(normalized, probeRequest)
            val preview = result.text.trim().take(60).ifEmpty { "(empty)" }
            AiConnectionTestResult(
                success = true,
                providerType = normalized.providerType,
                modelId = normalized.modelId,
                latencyMs = result.latencyMs,
                httpStatus = 200,
                message = "接続成功 (${normalized.providerType.displayLabel} / ${normalized.modelId} • ${result.latencyMs}ms)",
                sampleReply = preview,
            )
        } catch (e: AiClientException) {
            AiConnectionTestResult(
                success = false,
                providerType = config.providerType,
                modelId = config.modelId.trim(),
                latencyMs = 0L,
                httpStatus = e.httpStatus,
                message = e.message,
            )
        }
    }

    override suspend fun complete(
        config: AiProviderConnectionConfig,
        request: AiCompletionRequest,
    ): AiCompletionResult {
        val normalized = validateAndNormalize(config)
        return executeCompletion(normalized, request)
    }

    private suspend fun executeCompletion(
        config: AiProviderConnectionConfig,
        request: AiCompletionRequest,
    ): AiCompletionResult {
        val httpRequest = buildHttpRequest(config, request)
        val response = transport.execute(httpRequest)
        if (response.statusCode !in 200..299) {
            throw mapHttpFailure(config.providerType, response.statusCode, response.body)
        }
        return parseCompletionResponse(config, response)
    }

    private fun validateAndNormalize(config: AiProviderConnectionConfig): AiProviderConnectionConfig {
        val sanitizedUrl = AiSecretVault.sanitizeEndpointUrl(config.providerType, config.baseUrl)
        val model = config.modelId.trim().let { raw ->
            if (config.providerType == AiProviderType.GEMINI && raw.startsWith("models/")) {
                raw.removePrefix("models/")
            } else {
                raw
            }
        }
        if (model.isBlank()) {
            throw AiClientException(
                kind = AiClientException.Kind.INVALID_CONFIG,
                message = "モデル ID を入力してください",
            )
        }
        val key = config.apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (config.providerType.requiresApiKey && key == null) {
            throw AiClientException(
                kind = AiClientException.Kind.MISSING_API_KEY,
                message = "${config.providerType.displayLabel} の API キーが設定されていません",
            )
        }
        return config.copy(baseUrl = sanitizedUrl, modelId = model, apiKey = key)
    }

    internal fun buildHttpRequest(
        config: AiProviderConnectionConfig,
        request: AiCompletionRequest,
    ): AiHttpRequest =
        when (config.providerType) {
            AiProviderType.GEMINI -> buildGeminiRequest(config, request)
            AiProviderType.OPENAI,
            AiProviderType.OPENAI_COMPATIBLE,
            -> buildOpenAiRequest(config, request)
            AiProviderType.ANTHROPIC -> buildAnthropicRequest(config, request)
        }

    private fun buildGeminiRequest(
        config: AiProviderConnectionConfig,
        request: AiCompletionRequest,
    ): AiHttpRequest {
        val base = normalizePathSuffix(config.baseUrl, defaultPrefixIfRoot = "/v1beta")
        val encodedModel = URLEncoder.encode(config.modelId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        val endpoint = "$base/models/$encodedModel:generateContent"

        val lowerModel = config.modelId.lowercase(Locale.US)
        val isLegacyGeminiSampling = lowerModel.startsWith("gemini-1.") ||
            lowerModel.startsWith("gemini-2.")
        val preferLowThinking = request.isProbe || request.goal == AiWritingGoal.GENERATE_SUBJECT

        val bodyObj = buildJsonObject {
            if (request.systemPrompt.isNotBlank()) {
                put(
                    "systemInstruction",
                    buildJsonObject {
                        put(
                            "parts",
                            buildJsonArray {
                                add(buildJsonObject { put("text", request.systemPrompt) })
                            },
                        )
                    },
                )
            }
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "parts",
                                buildJsonArray {
                                    add(buildJsonObject { put("text", request.userPrompt) })
                                    request.attachments.forEach { att ->
                                        add(
                                            buildJsonObject {
                                                put(
                                                    "inlineData",
                                                    buildJsonObject {
                                                        put("mimeType", att.mimeType)
                                                        put("data", att.base64Data)
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                },
            )
            put(
                "generationConfig",
                buildJsonObject {
                    if (isLegacyGeminiSampling && request.temperature != null && !request.isProbe) {
                        put("temperature", request.temperature)
                    }
                    put("maxOutputTokens", request.maxOutputTokens.coerceAtLeast(1))
                    if (!isLegacyGeminiSampling && preferLowThinking) {
                        put(
                            "thinkingConfig",
                            buildJsonObject {
                                put("thinkingLevel", "low")
                            },
                        )
                    }
                },
            )
        }

        val headers = buildMap {
            config.apiKey?.let { put("x-goog-api-key", it) }
        }
        return AiHttpRequest(
            url = endpoint,
            method = "POST",
            headers = headers,
            bodyJson = json.encodeToString(JsonObject.serializer(), bodyObj),
            readTimeoutMs = if (request.attachments.isNotEmpty()) MULTIMODAL_READ_TIMEOUT_MS else DEFAULT_READ_TIMEOUT_MS,
        )
    }

    private fun buildOpenAiRequest(
        config: AiProviderConnectionConfig,
        request: AiCompletionRequest,
    ): AiHttpRequest {
        val endpoint = if (config.baseUrl.endsWith("/chat/completions", ignoreCase = true)) {
            config.baseUrl
        } else {
            val base = normalizePathSuffix(config.baseUrl, defaultPrefixIfRoot = "/v1")
            "$base/chat/completions"
        }

        val lowerModel = config.modelId.lowercase(Locale.US)
        val isLegacyOpenAiSampling = lowerModel.startsWith("gpt-4") ||
            lowerModel.startsWith("gpt-3.5")
        val omitCompatSampling = lowerModel.startsWith("openai/gpt-5") ||
            lowerModel.startsWith("openai/gpt-6") ||
            lowerModel.startsWith("anthropic/claude-5") ||
            lowerModel.startsWith("anthropic/claude-fable")

        val bodyObj = buildJsonObject {
            put("model", config.modelId)
            put(
                "messages",
                buildJsonArray {
                    if (request.systemPrompt.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", request.systemPrompt)
                            },
                        )
                    }
                    add(
                        buildJsonObject {
                            put("role", "user")
                            if (request.attachments.isEmpty()) {
                                put("content", request.userPrompt)
                            } else {
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", request.userPrompt)
                                            },
                                        )
                                        request.attachments.forEach { att ->
                                            if (att.mimeType.startsWith("image/", ignoreCase = true)) {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "image_url")
                                                        put(
                                                            "image_url",
                                                            buildJsonObject {
                                                                put(
                                                                    "url",
                                                                    "data:${att.mimeType};base64,${att.base64Data}",
                                                                )
                                                            },
                                                        )
                                                    },
                                                )
                                            } else if (att.mimeType.equals("application/pdf", ignoreCase = true)) {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "file")
                                                        put(
                                                            "file",
                                                            buildJsonObject {
                                                                put("filename", att.displayName)
                                                                put(
                                                                    "file_data",
                                                                    "data:application/pdf;base64,${att.base64Data}",
                                                                )
                                                            },
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    )
                },
            )
            if (config.providerType == AiProviderType.OPENAI) {
                put("max_completion_tokens", request.maxOutputTokens.coerceAtLeast(1))
                if (isLegacyOpenAiSampling && request.temperature != null && !request.isProbe) {
                    put("temperature", request.temperature)
                }
                val preferLowReasoning = request.isProbe || request.goal == AiWritingGoal.GENERATE_SUBJECT
                if (preferLowReasoning && !isLegacyOpenAiSampling) {
                    put("reasoning_effort", "low")
                }
            } else {
                put("max_tokens", request.maxOutputTokens.coerceAtLeast(1))
                if (!request.isProbe && request.temperature != null && !omitCompatSampling) {
                    put("temperature", request.temperature)
                }
            }
        }

        val headers = buildMap {
            config.apiKey?.let { put("Authorization", "Bearer $it") }
        }
        return AiHttpRequest(
            url = endpoint,
            method = "POST",
            headers = headers,
            bodyJson = json.encodeToString(JsonObject.serializer(), bodyObj),
            readTimeoutMs = if (request.attachments.isNotEmpty()) MULTIMODAL_READ_TIMEOUT_MS else DEFAULT_READ_TIMEOUT_MS,
        )
    }

    private fun buildAnthropicRequest(
        config: AiProviderConnectionConfig,
        request: AiCompletionRequest,
    ): AiHttpRequest {
        val endpoint = if (config.baseUrl.endsWith("/messages", ignoreCase = true)) {
            config.baseUrl
        } else {
            val base = normalizePathSuffix(config.baseUrl, defaultPrefixIfRoot = "/v1")
            "$base/messages"
        }

        val bodyObj = buildJsonObject {
            put("model", config.modelId)
            put("max_tokens", request.maxOutputTokens.coerceAtLeast(1))
            if (request.systemPrompt.isNotBlank()) {
                put("system", request.systemPrompt)
            }
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            if (request.attachments.isEmpty()) {
                                put("content", request.userPrompt)
                            } else {
                                put(
                                    "content",
                                    buildJsonArray {
                                        request.attachments.forEach { att ->
                                            if (att.mimeType.startsWith("image/", ignoreCase = true)) {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "image")
                                                        put(
                                                            "source",
                                                            buildJsonObject {
                                                                put("type", "base64")
                                                                put("media_type", att.mimeType)
                                                                put("data", att.base64Data)
                                                            },
                                                        )
                                                    },
                                                )
                                            } else if (att.mimeType.equals("application/pdf", ignoreCase = true)) {
                                                add(
                                                    buildJsonObject {
                                                        put("type", "document")
                                                        put(
                                                            "source",
                                                            buildJsonObject {
                                                                put("type", "base64")
                                                                put("media_type", "application/pdf")
                                                                put("data", att.base64Data)
                                                            },
                                                        )
                                                        put("title", att.displayName)
                                                    },
                                                )
                                            }
                                        }
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", request.userPrompt)
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }

        val headers = buildMap {
            config.apiKey?.let { put("x-api-key", it) }
            put("anthropic-version", ANTHROPIC_API_VERSION)
        }
        return AiHttpRequest(
            url = endpoint,
            method = "POST",
            headers = headers,
            bodyJson = json.encodeToString(JsonObject.serializer(), bodyObj),
            readTimeoutMs = if (request.attachments.isNotEmpty()) MULTIMODAL_READ_TIMEOUT_MS else DEFAULT_READ_TIMEOUT_MS,
        )
    }

    private fun parseCompletionResponse(
        config: AiProviderConnectionConfig,
        response: AiHttpResponse,
    ): AiCompletionResult {
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrElse { err ->
            throw AiClientException(
                kind = AiClientException.Kind.PARSE_ERROR,
                message = "AI API レスポンスの JSON 解析に失敗しました",
                httpStatus = response.statusCode,
                cause = err,
            )
        }

        return when (config.providerType) {
            AiProviderType.GEMINI -> parseGeminiResponse(config, root, response.latencyMs, response.statusCode)
            AiProviderType.OPENAI,
            AiProviderType.OPENAI_COMPATIBLE,
            -> parseOpenAiResponse(config, root, response.latencyMs, response.statusCode)
            AiProviderType.ANTHROPIC -> parseAnthropicResponse(config, root, response.latencyMs, response.statusCode)
        }
    }

    private fun parseGeminiResponse(
        config: AiProviderConnectionConfig,
        root: JsonObject,
        latencyMs: Long,
        statusCode: Int,
    ): AiCompletionResult {
        val blockReason = root["promptFeedback"]
            ?.let { it as? JsonObject }
            ?.get("blockReason")
            ?.jsonPrimitive?.contentOrNull
        if (!blockReason.isNullOrBlank()) {
            throw AiClientException(
                kind = AiClientException.Kind.POLICY_BLOCKED,
                message = "Gemini セーフティポリシーによりブロックされました ($blockReason)",
                httpStatus = statusCode,
            )
        }

        val firstCandidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw AiClientException(
                kind = AiClientException.Kind.PARSE_ERROR,
                message = "Gemini 応答に candidates が含まれていません",
                httpStatus = statusCode,
            )
        val finishReason = firstCandidate["finishReason"]?.jsonPrimitive?.contentOrNull
        val parts = firstCandidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        val text = parts.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            if (obj["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                null
            } else {
                obj["text"]?.jsonPrimitive?.contentOrNull
            }
        }.joinToString("").trim()

        if (text.isEmpty() && finishReason.equals("SAFETY", ignoreCase = true)) {
            throw AiClientException(
                kind = AiClientException.Kind.POLICY_BLOCKED,
                message = "Gemini セーフティフィルターにより応答が空でした",
                httpStatus = statusCode,
            )
        }

        val usage = root["usageMetadata"] as? JsonObject
        return AiCompletionResult(
            text = text,
            providerType = config.providerType,
            modelId = config.modelId,
            latencyMs = latencyMs,
            inputTokens = usage?.get("promptTokenCount")?.jsonPrimitive?.intOrNull,
            outputTokens = usage?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull,
            finishReason = finishReason,
        )
    }

    private fun parseOpenAiResponse(
        config: AiProviderConnectionConfig,
        root: JsonObject,
        latencyMs: Long,
        statusCode: Int,
    ): AiCompletionResult {
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw AiClientException(
                kind = AiClientException.Kind.PARSE_ERROR,
                message = "OpenAI互換応答に choices が含まれていません",
                httpStatus = statusCode,
            )
        val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        val messageObj = choice["message"]?.jsonObject
        val contentElem = messageObj?.get("content")
        val text = when (contentElem) {
            is JsonPrimitive -> contentElem.contentOrNull.orEmpty()
            is JsonArray -> contentElem.mapNotNull { item ->
                (item as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }.joinToString("")
            else -> ""
        }.trim()

        val usage = root["usage"] as? JsonObject
        val modelUsed = root["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: config.modelId
        return AiCompletionResult(
            text = text,
            providerType = config.providerType,
            modelId = modelUsed,
            latencyMs = latencyMs,
            inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull,
            outputTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull,
            finishReason = finishReason,
        )
    }

    private fun parseAnthropicResponse(
        config: AiProviderConnectionConfig,
        root: JsonObject,
        latencyMs: Long,
        statusCode: Int,
    ): AiCompletionResult {
        val contentBlocks = root["content"]?.jsonArray
            ?: throw AiClientException(
                kind = AiClientException.Kind.PARSE_ERROR,
                message = "Anthropic 応答に content が含まれていません",
                httpStatus = statusCode,
            )
        val text = contentBlocks.mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                obj["text"]?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
        }.joinToString("").trim()

        val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull
        val usage = root["usage"] as? JsonObject
        val modelUsed = root["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: config.modelId
        return AiCompletionResult(
            text = text,
            providerType = config.providerType,
            modelId = modelUsed,
            latencyMs = latencyMs,
            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull,
            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull,
            finishReason = stopReason,
        )
    }

    private fun mapHttpFailure(
        providerType: AiProviderType,
        statusCode: Int,
        rawBody: String,
    ): AiClientException {
        val detail = extractProviderErrorMessage(rawBody)
        val suffix = detail?.let { ": $it" }.orEmpty()
        return when (statusCode) {
            401, 403 -> AiClientException(
                kind = AiClientException.Kind.AUTH_FAILED,
                message = "${providerType.displayLabel} の認証に失敗しました (HTTP $statusCode)$suffix",
                httpStatus = statusCode,
            )
            404 -> AiClientException(
                kind = AiClientException.Kind.MODEL_NOT_FOUND,
                message = "${providerType.displayLabel} のモデルまたはエンドポイントが見つかりません (HTTP 404)$suffix",
                httpStatus = statusCode,
            )
            429 -> AiClientException(
                kind = AiClientException.Kind.RATE_LIMITED,
                message = "${providerType.displayLabel} のレート制限またはクォータ上限に達しました (HTTP 429)$suffix",
                httpStatus = statusCode,
            )
            else -> AiClientException(
                kind = AiClientException.Kind.HTTP_ERROR,
                message = "${providerType.displayLabel} API エラー (HTTP $statusCode)$suffix",
                httpStatus = statusCode,
            )
        }
    }

    private fun extractProviderErrorMessage(rawBody: String): String? {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return null
        val parsed = runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
        val msgFromJson = when (parsed) {
            is JsonObject -> {
                (parsed["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: parsed["message"]?.jsonPrimitive?.contentOrNull
                    ?: parsed["detail"]?.jsonPrimitive?.contentOrNull
            }
            is JsonArray -> {
                (parsed.firstOrNull() as? JsonObject)
                    ?.let { it["error"] as? JsonObject }
                    ?.get("message")
                    ?.jsonPrimitive?.contentOrNull
            }
            else -> null
        }
        val rawFallback = if (trimmed.startsWith("<")) "HTMLエラー応答" else trimmed
        return (msgFromJson ?: rawFallback).replace(Regex("\\s+"), " ").trim().take(200)
    }

    private fun normalizePathSuffix(baseUrl: String, defaultPrefixIfRoot: String): String {
        val cleaned = baseUrl.trimEnd('/')
        val path = runCatching { URI(cleaned).path.orEmpty() }.getOrDefault("")
        return if (path.isBlank() || path == "/") {
            cleaned + defaultPrefixIfRoot
        } else {
            cleaned
        }
    }

    companion object {
        const val ANTHROPIC_API_VERSION = "2026-10-21"
        private const val DEFAULT_READ_TIMEOUT_MS = 45_000
        private const val MULTIMODAL_READ_TIMEOUT_MS = 90_000
        val defaultJson = Json { ignoreUnknownKeys = true }
    }
}
