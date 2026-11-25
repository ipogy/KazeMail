package com.kazemail.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class AiHttpRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val bodyJson: String? = null,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 45_000,
)

data class AiHttpResponse(
    val statusCode: Int,
    val body: String,
    val latencyMs: Long,
)

interface AiHttpTransport {
    suspend fun execute(request: AiHttpRequest): AiHttpResponse
}

class HttpUrlConnectionAiTransport : AiHttpTransport {

    override suspend fun execute(request: AiHttpRequest): AiHttpResponse =
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val startNs = System.nanoTime()
            var connection: HttpURLConnection? = null
            try {
                val url = URL(request.url)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = request.method
                    connectTimeout = request.connectTimeoutMs
                    readTimeout = request.readTimeoutMs
                    useCaches = false
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    if (request.bodyJson != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    }
                    request.headers.forEach { (k, v) ->
                        setRequestProperty(k, v)
                    }
                }

                if (request.bodyJson != null) {
                    val payloadBytes = request.bodyJson.toByteArray(StandardCharsets.UTF_8)
                    connection.setFixedLengthStreamingMode(payloadBytes.size)
                    connection.outputStream.use { out ->
                        out.write(payloadBytes)
                    }
                }

                coroutineContext.ensureActive()
                val statusCode = connection.responseCode
                val stream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }
                val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                    val buffer = CharArray(8192)
                    val sb = StringBuilder()
                    var total = 0
                    while (true) {
                        val read = reader.read(buffer)
                        if (read <= 0) break
                        val allowed = (MAX_RESPONSE_CHARS - total).coerceAtMost(read)
                        if (allowed > 0) {
                            sb.appendRange(buffer, 0, allowed)
                            total += allowed
                        }
                        if (total >= MAX_RESPONSE_CHARS) break
                    }
                    sb.toString()
                }.orEmpty()

                val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
                AiHttpResponse(
                    statusCode = statusCode,
                    body = body,
                    latencyMs = elapsedMs,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw AiClientException(
                    kind = AiClientException.Kind.NETWORK_ERROR,
                    message = "AI API への通信に失敗しました: ${e.message ?: "ネットワークエラー"}",
                    cause = e,
                )
            } finally {
                connection?.disconnect()
            }
        }

    companion object {
        private const val MAX_RESPONSE_CHARS = 512 * 1024
    }
}
