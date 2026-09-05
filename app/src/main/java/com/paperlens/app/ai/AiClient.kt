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
 * 思考模型兼容（v1.5 起可见）：推理过程统一经 onThinking 回调上屏（UI 可折叠展示），绝不混入正文——
 * - OpenAI 兼容：delta.reasoning_content → onThinking；正文中的 <think>…</think> 由 [ThinkTagFilter]
 *   流式拆分（思考进 onThinking、正文进 onDelta）；
 * - Anthropic：thinking_delta → onThinking，text_delta → 正文；
 * - Gemini：thought 标记的 parts → onThinking。
 *
 * 取消安全：协程取消时立刻 cancel 底层 HTTP 调用；失败时已生成的部分文本会返回。
 */
class AiClient(
    private val client: OkHttpClient,
    private val json: Json,
) {
    private companion object {
        /** Embeddings 单批上限：超过自动分批。 */
        const val EMBED_BATCH = 64
    }

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
            // 推理型模型（DeepSeek-R1 等）的思考增量：v1.5 起实时上屏（可折叠），不混入正文
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
            // thinking_delta 的思考增量字段名是 thinking（不是 text）
            @SerialName("thinking") val thinking: String? = null,
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
            // Gemini 2.5 思考标记：true 表示该段是思考过程，v1.5 起实时上屏（可折叠）
            @SerialName("thought") val thought: Boolean? = null,
        )

        @Serializable
        data class GeminiError(
            @SerialName("code") val code: Int? = null,
            @SerialName("message") val message: String? = null,
        )
    }

    @Serializable
    private data class ModelsResponse(
        @SerialName("data") val data: List<ModelItem> = emptyList(),
    ) {
        @Serializable
        data class ModelItem(@SerialName("id") val id: String = "")
    }

    @Serializable
    private data class GeminiModelsResponse(
        @SerialName("models") val models: List<ModelEntry> = emptyList(),
    ) {
        @Serializable
        data class ModelEntry(@SerialName("name") val name: String = "")
    }

    @Serializable
    private data class EmbedResponse(
        @SerialName("data") val data: List<EmbedItem> = emptyList(),
    ) {
        @Serializable
        data class EmbedItem(
            @SerialName("index") val index: Int = 0,
            @SerialName("embedding") val embedding: List<Double> = emptyList(),
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

    /** 模型列表端点（归一规则与 [endpoint] 同族）。 */
    private fun modelsEndpoint(settings: AppSettings): String {
        val raw = settings.effectiveAiBaseUrl.trim()
        val explicit = raw.endsWith("#")
        val base = raw.trimEnd('#').trim().trimEnd('/')
        return when (settings.aiProtocol) {
            AiProtocol.OPENAI ->
                if (explicit || Regex("/v\\d+$").containsMatchIn(base)) "$base/models" else "$base/v1/models"
            AiProtocol.ANTHROPIC ->
                if (explicit) "$base/v1/models"
                else if (Regex("/v\\d+$").containsMatchIn(base)) "$base/models"
                else "$base/v1/models"
            AiProtocol.GEMINI ->
                if (explicit || Regex("/v\\d+[a-z]*$").containsMatchIn(base)) "$base/models" else "$base/v1beta/models"
        }
    }

    // —— 流式对话 ——

    /**
     * 流式对话。onDelta 回调「过滤思考后」的正文增量；onThinking 回调思考过程增量
     * （v1.5 起不再丢弃：OpenAI reasoning_content / Anthropic thinking_delta /
     * Gemini thought parts / 正文中的 <think>…</think> 四路归一到这里）。
     * 成功返回完整正文；失败时若已有部分输出则返回部分，否则抛 IOException（含中文排障提示）。
     */
    suspend fun streamChat(
        settings: AppSettings,
        systemPrompt: String,
        userPrompt: String,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit = {},
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
                                ?.choices?.firstOrNull()?.delta?.let { delta ->
                                    delta.reasoningContent?.takeIf { it.isNotEmpty() }?.let(onThinking)
                                    delta.content?.takeIf { it.isNotEmpty() }?.let { filter.feed(it, ::emitText, onThinking) }
                                }
                        }
                        AiProtocol.ANTHROPIC -> {
                            val payload = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") } ?: continue
                            if (payload.isEmpty()) continue
                            val event = runCatching { json.decodeFromString<AnthropicEvent>(payload) }.getOrNull() ?: continue
                            if (event.type == "error") {
                                throw IOException("服务商返回错误：${event.error?.message.orEmpty().take(160)}")
                            }
                            if (event.type == "content_block_delta") {
                                if (event.delta?.type == "thinking_delta") {
                                    event.delta.thinking?.takeIf { it.isNotEmpty() }?.let(onThinking)
                                } else if (event.delta?.type == "text_delta") {
                                    event.delta.text?.takeIf { it.isNotEmpty() }?.let { filter.feed(it, ::emitText, onThinking) }
                                }
                            }
                        }
                        AiProtocol.GEMINI -> {
                            val payload = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") } ?: continue
                            if (payload.isEmpty()) continue
                            val chunk = runCatching { json.decodeFromString<GeminiChunk>(payload) }.getOrNull() ?: continue
                            chunk.error?.let { throw IOException("服务商返回错误：${it.message.orEmpty().take(160)}") }
                            chunk.candidates.firstOrNull()?.content?.parts?.forEach { part ->
                                if (part.thought == true) {
                                    part.text?.takeIf { it.isNotEmpty() }?.let(onThinking)
                                } else {
                                    part.text?.takeIf { it.isNotEmpty() }?.let { filter.feed(it, ::emitText, onThinking) }
                                }
                            }
                        }
                    }
                }
            }
            filter.flush(::emitText, onThinking)
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

    // —— 模型列表 / Embeddings ——

    /**
     * 从服务商拉取可用模型列表（v1.5）：OpenAI 兼容与 Anthropic 返回 data[].id，
     * Gemini 返回 models[].name（去掉 models/ 前缀）。端点归一规则与 [endpoint] 一致。
     */
    suspend fun listModels(settings: AppSettings): Result<List<String>> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(modelsEndpoint(settings))
            .get()
            .let { applyAuth(it, settings) }
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(httpError(resp.code, resp.body?.string().orEmpty()))
                }
                val text = resp.body?.string().orEmpty()
                val models = when (settings.aiProtocol) {
                    AiProtocol.OPENAI, AiProtocol.ANTHROPIC ->
                        runCatching { json.decodeFromString<ModelsResponse>(text) }.getOrNull()
                            ?.data?.map { it.id }.orEmpty()
                    AiProtocol.GEMINI ->
                        runCatching { json.decodeFromString<GeminiModelsResponse>(text) }.getOrNull()
                            ?.models?.map { it.name.removePrefix("models/") }.orEmpty()
                }.filter { it.isNotBlank() }.distinct().sorted()
                if (models.isEmpty()) {
                    Result.failure(IOException("没有解析到模型列表，可手动填写模型名"))
                } else {
                    Result.success(models)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 批量文本向量化（v1.5，AI 每日精选用）。目前支持 OpenAI 兼容 /embeddings 协议；
     * 输入超过 64 条自动分批。返回向量顺序与输入一一对应。
     */
    suspend fun embedTexts(settings: AppSettings, inputs: List<String>): Result<List<FloatArray>> =
        withContext(Dispatchers.IO) {
            if (settings.aiProtocol != AiProtocol.OPENAI) {
                return@withContext Result.failure(
                    IllegalStateException("Embedding 暂只支持 OpenAI 兼容协议（可在 AI 设置切换）"),
                )
            }
            val raw = settings.effectiveAiBaseUrl.trim()
            val explicit = raw.endsWith("#")
            val base = raw.trimEnd('#').trim().trimEnd('/')
            val url = if (explicit || Regex("/v\\d+$").containsMatchIn(base)) "$base/embeddings" else "$base/v1/embeddings"

            try {
                val vectors = mutableListOf<FloatArray>()
                inputs.chunked(EMBED_BATCH).forEach { batch ->
                    val body = buildJsonObject {
                        put("model", settings.embeddingModel)
                        putJsonArray("input") { batch.forEach { add(it) } }
                    }.toString()
                    val request = Request.Builder()
                        .url(url)
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .let { applyAuth(it, settings) }
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw httpError(resp.code, resp.body?.string().orEmpty())
                        val parsed = runCatching {
                            json.decodeFromString<EmbedResponse>(resp.body?.string().orEmpty())
                        }.getOrNull() ?: throw IOException("Embedding 响应解析失败")
                        parsed.data.sortedBy { it.index }.forEach { item ->
                            vectors.add(item.embedding.map { it.toFloat() }.toFloatArray())
                        }
                    }
                }
                if (vectors.size != inputs.size) {
                    Result.failure(IOException("Embedding 返回条数不匹配（${vectors.size}/${inputs.size}）"))
                } else {
                    Result.success(vectors)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure(e)
            }
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
        return applyAuth(builder, settings).build()
    }

    /** 按协议写鉴权头（流式/测试/模型列表/embeddings 共用）。 */
    private fun applyAuth(builder: Request.Builder, settings: AppSettings): Request.Builder {
        when (settings.aiProtocol) {
            AiProtocol.OPENAI -> builder.header("Authorization", "Bearer ${settings.aiApiKey}")
            AiProtocol.ANTHROPIC -> {
                builder.header("x-api-key", settings.aiApiKey)
                builder.header("anthropic-version", "2023-06-01")
            }
            AiProtocol.GEMINI -> builder.header("x-goog-api-key", settings.aiApiKey)
        }
        return builder
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
 * 流式安全的 `<think>…</think>` 处理器（v1.5：不再丢弃思考内容，改经 emitThink 回调上屏）：
 * 思考内容跨增量出现、标签本身也可能被拆在两个增量里（"<thin" + "k>"），逐字缓冲处理。
 * 正文开头的水位空白仍然去掉，避免渲染时顶部出现空行。
 */
internal class ThinkTagFilter {
    private var insideThink = false
    private var pending = StringBuilder()
    private var emittedAny = false

    fun feed(chunk: String, emit: (String) -> Unit, emitThink: (String) -> Unit) {
        var text = pending.append(chunk).toString()
        pending = StringBuilder()
        while (true) {
            if (insideThink) {
                val close = text.indexOf(END_TAG)
                if (close < 0) {
                    // 尾部可能是被截断的 "</thin"，留到下个增量再判断；思考本体实时回调
                    val keep = partialSuffixLen(text, END_TAG)
                    val thinkOut = text.dropLast(keep)
                    if (thinkOut.isNotEmpty()) emitThink(thinkOut)
                    pending.append(text.takeLast(keep))
                    return
                }
                if (close > 0) emitThink(text.take(close))
                text = text.substring(close + END_TAG.length).trimStart('\n', '\r', ' ')
                insideThink = false
                continue
            }
            val open = text.indexOf(START_TAG)
            if (open < 0) {
                val keep = partialSuffixLen(text, START_TAG)
                val out = text.dropLast(keep)
                if (out.isNotEmpty()) emitText2(out, emit)
                pending.append(text.takeLast(keep))
                return
            }
            if (open > 0) emitText2(text.take(open), emit)
            text = text.substring(open + START_TAG.length)
            insideThink = true
            // 思考内容可能在同一增量里紧接开始，继续循环处理
        }
    }

    /** 流结束：把缓冲里剩下的非思考文本吐出去（被截断的普通 "<thin" 之类）。 */
    fun flush(emit: (String) -> Unit, emitThink: (String) -> Unit) {
        val rest = pending.toString()
        pending = StringBuilder()
        when {
            insideThink -> if (rest.isNotEmpty()) emitThink(rest)
            rest.isNotEmpty() -> emitText2(rest, emit)
        }
    }

    private fun emitText2(s: String, emit: (String) -> Unit) {
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
