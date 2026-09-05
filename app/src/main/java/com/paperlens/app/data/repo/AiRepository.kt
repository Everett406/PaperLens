package com.paperlens.app.data.repo

import com.paperlens.app.ai.AiClient
import com.paperlens.app.ai.Prompts
import com.paperlens.app.data.db.AiReadingEntity
import com.paperlens.app.data.db.AppDatabase
import com.paperlens.app.data.prefs.AppSettings
import com.paperlens.app.data.prefs.CacheStore
import com.paperlens.app.data.prefs.SettingsStore
import com.paperlens.app.data.prefs.TranslationEntry
import com.paperlens.app.domain.AiLayer
import com.paperlens.app.domain.Paper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * AI 能力仓：
 * - 三层阅读：按需生成（点按钮触发，v1.5 起由 [AiReadManager] 后台队列调度），
 *   流式输出经 onDelta 实时回调、思考过程经 onThinking 上屏，完成后整段落 Room 缓存；
 * - 摘要翻译（v1.5 新增）：流式翻译标题+摘要，成功后 LRU 缓存（CacheStore），
 *   重进详情页直接回放不重复消耗 token；
 * - 未配置 AI 服务时 isConfigured 为 false，UI 展示引导文案，不阻塞其他功能。
 */
class AiRepository(
    private val aiClient: AiClient,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
    private val cacheStore: CacheStore,
) {

    fun observeReading(arxivId: String, layer: AiLayer): Flow<AiReadingEntity?> =
        db.aiReadingDao().observe(arxivId, layer.name)

    fun isConfigured(): Flow<Boolean> =
        settingsStore.settings.map { it.aiConfigured }

    suspend fun currentSettings(): AppSettings = settingsStore.settings.first()

    /**
     * 生成一层阅读。返回完整文本（可能为部分输出——网络中断时也把已生成的部分返回，
     * 由调用方决定是否缓存，避免长输出白费）。
     */
    suspend fun generate(
        paper: Paper,
        layer: AiLayer,
        onDelta: (String) -> Unit,
        onThinking: (String) -> Unit = {},
    ): Result<String> {
        val settings = currentSettings()
        if (!settings.aiConfigured) {
            return Result.failure(IllegalStateException("尚未配置 AI 服务"))
        }
        return try {
            val text = aiClient.streamChat(
                settings = settings,
                systemPrompt = Prompts.system(layer),
                userPrompt = Prompts.user(paper),
                onDelta = onDelta,
                onThinking = onThinking,
            )
            if (text.isBlank()) {
                Result.failure(IllegalStateException("模型没有返回内容"))
            } else {
                db.aiReadingDao().put(
                    AiReadingEntity(
                        arxivId = paper.arxivId,
                        layer = layer.name,
                        content = text,
                        generatedAt = System.currentTimeMillis(),
                        model = settings.aiModel,
                    )
                )
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // —— 摘要 AI 翻译（v1.5） ——

    suspend fun cachedTranslation(arxivId: String): TranslationEntry? =
        cacheStore.translationFor(arxivId)

    /**
     * 流式翻译标题+摘要。成功返回完整文本并写缓存；失败返回已生成部分（不缓存）或错误。
     */
    suspend fun translateAbstract(
        paper: Paper,
        onDelta: (String) -> Unit,
    ): Result<String> {
        val settings = currentSettings()
        if (!settings.aiConfigured) {
            return Result.failure(IllegalStateException("尚未配置 AI 服务"))
        }
        return try {
            val text = aiClient.streamChat(
                settings = settings,
                systemPrompt = Prompts.TRANSLATE_SYSTEM,
                userPrompt = Prompts.translateUser(paper),
                onDelta = onDelta,
            )
            if (text.isBlank()) {
                Result.failure(IllegalStateException("模型没有返回内容"))
            } else {
                cacheStore.putTranslation(
                    paper.arxivId,
                    TranslationEntry(
                        text = text,
                        at = System.currentTimeMillis(),
                        model = settings.aiModel,
                    ),
                )
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearCache() {
        db.aiReadingDao().clear()
        cacheStore.clearCurated()
    }
}
