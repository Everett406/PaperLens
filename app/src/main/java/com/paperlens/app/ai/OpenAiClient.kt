package com.paperlens.app.ai

import com.paperlens.app.data.prefs.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * OpenAI 兼容 Chat Completions 客户端（规格第四节）。
 * - 流式：SSE（data: {...} 行协议），逐 delta 回调；
 * - 兼容性：Base URL 自动拼 /v1/chat/completions；用户 URL 已含 /vN 或以 # 结尾则尊重原样；
 * - 取消安全：协程取消时立刻 cancel 底层 HTTP 调用。
 */
class OpenAiClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    @Serializable
    private data class ChatChunk(
        @SerialName("choices") val choices: List<Choice> = emptyList(),
    ) {
        @Serializable
        data class Choice(
            @SerialName("delta") val delta: Delta? = null,
        )

        @Serializable
        data class Delta(
            @SerialName("content") val content: String? = null,
        )
    }

    @Serializable
    private data class ChatResponse(
        @SerialName("choices") val choices: List<RespChoice> = emptyList(),
    ) {
        @Serializable
        data class RespChoice(
            @SerialName("message") val message: Message? = null,
        )

        @Serializable
        data class Message(
            @SerialName("content") val content: String? = null,
        )
    }

    /**
     * 端点归一化：
     * - https://api.example.com            → https://api.example.com/v1/chat/completions
     * - https://api.example.com/v1         → https://api.example.com/v1/chat/completions
     * - https://api.example.com/v1/#（#结尾=原样使用，仅去掉#） → .../chat/completions 直接拼接
     */
    fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("#")) {
            base.dropLast(1).trimEnd('/') + "/chat/completions"
        } else if (Regex("/v\\d+$").containsMatchIn(base)) {
            "$base/chat/completions"
        } else {
            "$base/v1/chat/completions"
        }
    }

    /**
     * 流式对话。成功返回完整文本；失败抛 IOException（含 HTTP 状态）。
     */
    suspend fun streamChat(
        settings: AppSettings,
        systemPrompt: String,
        userPrompt: String,
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", settings.aiModel)
            put("stream", true)
            put("temperature", 0.7)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    }
                )
            }
        }.toString()

        val request = Request.Builder()
            .url(endpoint(settings.aiBaseUrl))
            .header("Authorization", "Bearer ${settings.aiApiKey}")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        // 协程取消 → 取消网络调用，避免流式读取悬挂
        val cancelJob: Job? = coroutineContext[Job]
        cancelJob?.invokeOnCompletion { call.cancel() }

        val full = StringBuilder()
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}")
                }
                val source = resp.body?.source() ?: throw IOException("空响应体")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    if (payload.isEmpty()) continue
                    runCatching {
                        json.decodeFromString<ChatChunk>(payload)
                    }.getOrNull()?.choices?.firstOrNull()?.delta?.content?.takeIf { it.isNotEmpty() }?.let {
                        full.append(it)
                        onDelta(it)
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (full.isNotEmpty()) {
                // 已有部分输出：返回已生成内容，让上层自行决定是否缓存
                return@withContext full.toString()
            }
            throw e
        }
        full.toString()
    }

    /**
     * 连通性测试：非流式、max_tokens=4 的最小请求，返回耗时毫秒。
     */
    suspend fun testConnection(settings: AppSettings): Result<Long> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", settings.aiModel)
            put("max_tokens", 4)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", "ping")
                    }
                )
            }
        }.toString()

        val request = Request.Builder()
            .url(endpoint(settings.aiBaseUrl))
            .header("Authorization", "Bearer ${settings.aiApiKey}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val started = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - started
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                }
                val text = resp.body?.string().orEmpty()
                val content = runCatching { json.decodeFromString<ChatResponse>(text) }
                    .getOrNull()?.choices?.firstOrNull()?.message?.content
                if (content == null) {
                    Result.failure(IOException("响应格式异常"))
                } else {
                    Result.success(elapsed)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
