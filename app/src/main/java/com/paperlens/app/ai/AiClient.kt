package com.paperlens.app.ai

import com.paperlens.app.data.prefs.AiProtocol
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 多协议 AI 客户端（v1.1 起支持三种协议，全部流式 SSE）：
 * - OPENAI：Chat Completions 兼容（OpenAI / DeepSeek / Kimi / GLM / OpenRouter 等）；
 * - ANTHROPIC：Messages API（x-api-key + anthropic-version 头，event/data 行协议）；
 * - GEMINI：Generative Language API（streamGenerateContent?alt=sse）。
 *
 * 思考模型兼容（v1.1）：推理型模型的思考过程一律不进正文——
 * - OpenAI 兼容：忽略 delta.reasoning_content；正文中的 <think>…</think> 由 [ThinkTagFilter] 流式剥除；
 * - Anthropic：只渲染 text_delta，忽略 thinking_delta；
 * - Gemini：忽略带 thought 标记的 parts。
 *
 * 取消安全：协程取消时立刻 cancel 底层 HTTP 调用；失败时已生成的部分文本会返回。
 */
class AiClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    // —— DTO（全部可空 + 默认值：上游字段偶有缺省，绝不让解析炸掉流） ——

    @Serializable
    private data class OpenAiChunk(
        @SerialName("choices") val choices: List<Choice> = emptyList(),
    ) {
        @Serializable
        data class Choice(@SerialName("delta") val delta: Delta? = null)

        @Serializable
        data class Delta(
            @SerialName("content") val content: String? = null,
            // 推理型模型（DeepSeek-R1 等）的思考增量：明确解析但从不渲染
            @SerialName("reasoning_content") val reasoningContent: String? = null,
        )
    }

    @Serializable
    private data class OpenAiResponse(
        @SerialName("choices") val choices: List<RespChoice> = emptyList(),
    ) {
        @Serializable
        data class RespChoice(@SerialName("message") val message: Message? = null)

        @Serializable
        data class Message(
            @SerialName("content") val content: String? = null,
            @SerialName("reasoning_content") val reasoningContent: String? = null,
        )
    }

    @Serializable
    private data class AnthropicEvent(
        @SerialName("type") val type: String? = null,
        @SerialName("delta") val delta: Delta? = null,
        @SerialName("error") val error: ErrorBody? = null,
    ) {
        @Serializable
        data class Delta(
            @SerialName("type") val type: String? = null,
            @SerialName("text") val text: String? = null,
        )

        @Serializable
        data class ErrorBody(@SerialName("message") val message: String? = null)
    }

    @Serializable
    private data class AnthropicResponse(
        @SerialName("content") val content: List<Block> = emptyList(),
    ) {
        @Serializable
        data class Block(
            @SerialName("type") val type: String? = null,
            @SerialName("text") val text: String? = null,
        )
    }

    @Serializable
    private data class GeminiChunk(
        @SerialName("candidates") val candidates: List<Candidate> = emptyList(),
        @SerialName("error") val error: GeminiError? = null,
    ) {
        @Serializable
        data class Candidate(@SerialName("content") val content: Content? = null)

        @Serializable
        data class Content(@SerialName("parts") val parts: List<Part> = emptyList())

        @Serializable
        data class Part(
            @SerialName("text") val text: String? = null,
            // Gemini 2.5 思考标记：true 表示该段是思考过程，不渲染
            @SerialName("thought") val thought: Boolean? = null,
        )

        @Serializable
        data class GeminiError(
            @SerialName("code") val code: Int? = null,
            @SerialName("message") val message: String? = null,
        )
    }

    // —— 端点归一化 ——

    /** 按协议把 Base URL 归一化为完整请求端点。`#` 结尾 = 尊重用户原路径，仅去掉 #。 */
    fun endpoint(settings: AppSettings, stream: Boolean): String {
        val raw = settings.effectiveAiBaseUrl.trim()
        val explicit = raw.endsWith("#")
        val base = raw.trimEnd('#').trim().trimEnd('/')
        val model = settings.aiModel.trim().removePrefix("models/").trim('/')
        return when (settings.aiProtocol) {
            AiProtocol.OPENAI -> when {
                explicit -> "$base/chat/completions"
                Regex("/v\\d+$").containsMatchIn(base) -> "$base/chat/completions"
                else -> "$base/v1/chat/completions"
            }
            AiProtocol.ANTHROPIC -> when {
                explicit -> "$base/v1/messages"
                Regex("/v\\d+$").containsMatchIn(base) -> "$base/messages"
                else -> "$base/v1/messages"
            }
            AiProtocol.GEMINI -> {
                val action = if (stream) ":streamGenerateContent?alt=sse" else ":generateContent"
                val suffix = "models/$model$action"
                if (explicit || Regex("/v\\d+[a-z]*$").containsMatchIn(base)) "$base/$suffix"
                else "$base/v1beta/$suffix"
            }
        }
    }

    // —— 流式对话 ——

    /**
     * 流式对话。onDelta 回调的是「过滤思考后」的正文增量。
     * 成功返回完整正文；失败时若已有部分输出则返回部分，否则抛 IOException（含中文排障提示）。
     */
    suspend fun streamChat(
        settings: AppSettings,
        systemPrompt: String,
        userPrompt: String,
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val body = when (settings.aiProtocol) {
            AiProtocol.OPENAI -> openAiBody(settings.aiModel, systemPrompt, userPrompt, stream = true)
            AiProtocol.ANTHROPIC -> anthropicBody(settings.aiModel, systemPrompt, userPrompt, stream = true)
            AiProtocol.GEMINI -> geminiBody(systemPrompt, userPrompt)
        }.toString()

        val request = buildRequest(settings, stream = true, body = body)
        val call = client.newCall(request)
        // 协程取消 → 取消网络调用，避免流式读取悬挂
        coroutineContext[Job]?.invokeOnCompletion { call.cancel() }

        val full = StringBuilder()
        val filter = ThinkTagFilter()
        fun emitText(text: String) {
            if (text.isEmpty()) return
            full.append(text)
            onDelta(text)
        }

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string().orEmpty())
                val source = resp.body?.source() ?: throw IOException("空响应体")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when (settings.aiProtocol) {
                        AiProtocol.OPENAI -> {
                            val payload = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") } ?: continue
                            if (payload == "[DONE]") break
                            if (payload.isEmpty()) continue
                            runCatching { json.decodeFromString<OpenAiChunk>(payload) }.getOrNull()
                                ?.choices?.firstOrNull()?.delta?.content
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { filter.feed(it, ::emitText) }
                        }
                        AiProtocol.ANTHROPIC -> {
                            val payload = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") } ?: continue
                            if (payload.isEmpty()) continue
                            val event = runCatching { json.decodeFromString<AnthropicEvent>(payload) }.getOrNull() ?: continue
                            if (event.type == "error") {
                                throw IOException("服务商返回错误：${event.error?.message.orEmpty().take(160)}")
                            }
                            // 只渲染 text_delta；thinking_delta（思考过程）直接丢弃
                            if (event.type == "content_block_delta" && event.delta?.type == "text_delta") {
                                event.delta.text?.takeIf { it.isNotEmpty() }?.let { filter.feed(it, ::emitText) }
                            }
                        }
                        AiProtocol.GEMINI -> {
                            val payload = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") } ?: continue
                            if (payload.isEmpty()) continue
                            val chunk = runCatching { json.decodeFromString<GeminiChunk>(payload) }.getOrNull() ?: continue
                            chunk.error?.let { throw IOException("服务商返回错误：${it.message.orEmpty().take(160)}") }
                            chunk.candidates.firstOrNull()?.content?.parts?.forEach { part ->
                                if (part.thought == true) return@forEach
                                part.text?.takeIf { it.isNotEmpty() }?.let { filter.feed(it, ::emitText) }
                            }
                        }
                    }
                }
            }
            filter.flush(::emitText)
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

    // —— 连通性测试 ——

    /** 连通性测试：非流式最小请求，返回耗时毫秒。失败信息含中文排障提示。 */
    suspend fun testConnection(settings: AppSettings): Result<Long> = withContext(Dispatchers.IO) {
        val body = when (settings.aiProtocol) {
            // 不带 max_tokens：兼容推理型模型（o 系/DeepSeek-R1 只认 max_completion_tokens）
            AiProtocol.OPENAI -> buildJsonObject {
                put("model", settings.aiModel)
                put("stream", false)
                putJsonArray("messages") {
                    add(message("user", "ping"))
                }
            }
            AiProtocol.ANTHROPIC -> buildJsonObject {
                put("model", settings.aiModel)
                put("max_tokens", 16)
                putJsonArray("messages") {
                    add(message("user", "ping"))
                }
            }
            AiProtocol.GEMINI -> buildJsonObject {
                putJsonArray("contents") {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            putJsonArray("parts") { add(buildJsonObject { put("text", "ping") }) }
                        }
                    )
                }
            }
        }.toString()

        val request = buildRequest(settings, stream = false, body = body)
        val started = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - started
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(httpError(resp.code, resp.body?.string().orEmpty()))
                }
                val text = resp.body?.string().orEmpty()
                val ok = when (settings.aiProtocol) {
                    AiProtocol.OPENAI -> runCatching { json.decodeFromString<OpenAiResponse>(text) }
                        .getOrNull()?.choices?.isNotEmpty() == true
                    AiProtocol.ANTHROPIC -> runCatching { json.decodeFromString<AnthropicResponse>(text) }
                        .getOrNull()?.content?.isNotEmpty() == true
                    AiProtocol.GEMINI -> runCatching { json.decodeFromString<GeminiChunk>(text) }
                        .getOrNull()?.candidates?.isNotEmpty() == true
                }
                if (ok) Result.success(elapsed) else Result.failure(IOException("响应格式与所选协议不匹配"))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // —— 内部工具 ——

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    /** OpenAI 兼容请求体。不带 temperature：推理型模型（o 系 / gpt-5）会拒绝非默认值。 */
    private fun openAiBody(model: String, system: String, user: String, stream: Boolean) = buildJsonObject {
        put("model", model)
        put("stream", stream)
        putJsonArray("messages") {
            add(message("system", system))
            add(message("user", user))
        }
    }

    private fun anthropicBody(model: String, system: String, user: String, stream: Boolean) = buildJsonObject {
        put("model", model)
        put("max_tokens", 4096)
        put("stream", stream)
        put("temperature", 0.7)
        put("system", system)
        putJsonArray("messages") { add(message("user", user)) }
    }

    private fun geminiBody(system: String, user: String) = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
        }
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", user) }) }
                }
            )
        }
        putJsonObject("generationConfig") { put("temperature", 0.7) }
    }

    private fun buildRequest(settings: AppSettings, stream: Boolean, body: String): Request {
        val builder = Request.Builder()
            .url(endpoint(settings, stream))
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))
        when (settings.aiProtocol) {
            AiProtocol.OPENAI -> builder.header("Authorization", "Bearer ${settings.aiApiKey}")
            AiProtocol.ANTHROPIC -> {
                builder.header("x-api-key", settings.aiApiKey)
                builder.header("anthropic-version", "2023-06-01")
            }
            AiProtocol.GEMINI -> builder.header("x-goog-api-key", settings.aiApiKey)
        }
        return builder.build()
    }

    /** HTTP 错误 → 带中文排障提示的 IOException（截断响应体防止刷屏）。 */
    private fun httpError(code: Int, body: String): IOException {
        val hint = when (code) {
            401, 403 -> "鉴权失败，检查 API Key 是否正确"
            404 -> "接口路径不存在，检查 Base URL 与协议是否匹配（也可在 Base URL 末尾加 # 强制原样拼接）"
            429 -> "触发限流或额度不足"
            in 500..599 -> "服务商端错误，稍后再试"
            else -> "请求失败"
        }
        val snippet = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160).orEmpty()
        return IOException(if (snippet.isEmpty()) "HTTP $code · $hint" else "HTTP $code · $hint\n$snippet")
    }
}

/**
 * 流式安全的 `<think>…</think>` 剥除器：
 * 思考内容跨增量出现、标签本身也可能被拆在两个增量里（"<thin" + "k>"），逐字缓冲处理。
 * 顺带去掉正文开头的水位空白，避免渲染时顶部出现空行。
 */
internal class ThinkTagFilter {
    private var insideThink = false
    private var pending = StringBuilder()
    private var emittedAny = false

    fun feed(chunk: String, emit: (String) -> Unit) {
        var text = pending.append(chunk).toString()
        pending = StringBuilder()
        while (true) {
            if (insideThink) {
                val close = text.indexOf(END_TAG)
                if (close < 0) {
                    // 尾部可能是被截断的 "</thin"，留到下个增量再判断；思考本体全部丢弃
                    val keep = partialSuffixLen(text, END_TAG)
                    pending.append(text.takeLast(keep))
                    return
                }
                text = text.substring(close + END_TAG.length).trimStart('\n', '\r', ' ')
                insideThink = false
                continue
            }
            val open = text.indexOf(START_TAG)
            if (open < 0) {
                val keep = partialSuffixLen(text, START_TAG)
                val out = text.dropLast(keep)
                if (out.isNotEmpty()) emitText(out, emit)
                pending.append(text.takeLast(keep))
                return
            }
            if (open > 0) emitText(text.take(open), emit)
            val close = text.indexOf(END_TAG, open)
            if (close < 0) {
                insideThink = true
                return
            }
            text = text.substring(close + END_TAG.length).trimStart('\n', '\r', ' ')
        }
    }

    /** 流结束：把缓冲里剩下的非思考文本吐出去（被截断的普通 "<thin" 之类）。 */
    fun flush(emit: (String) -> Unit) {
        val rest = pending.toString()
        pending = StringBuilder()
        if (!insideThink && rest.isNotEmpty()) emitText(rest, emit)
    }

    private fun emitText(s: String, emit: (String) -> Unit) {
        if (!emittedAny) {
            val trimmed = s.trimStart('\n', '\r', '\t', ' ')
            if (trimmed.isEmpty()) return
            emittedAny = true
            emit(trimmed)
        } else {
            emit(s)
        }
    }

    private fun partialSuffixLen(text: String, tag: String): Int {
        val max = minOf(tag.length - 1, text.length)
        for (k in max downTo 1) {
            if (text.endsWith(tag.substring(0, k))) return k
        }
        return 0
    }

    private companion object {
        const val START_TAG = "<think>"
        const val END_TAG = "</think>"
    }
}
