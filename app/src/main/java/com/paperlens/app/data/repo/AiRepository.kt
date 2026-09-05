package com.paperlens.app.data.repo

import com.paperlens.app.ai.OpenAiClient
import com.paperlens.app.ai.Prompts
import com.paperlens.app.data.db.AiReadingEntity
import com.paperlens.app.data.db.AppDatabase
import com.paperlens.app.data.prefs.AppSettings
import com.paperlens.app.data.prefs.SettingsStore
import com.paperlens.app.domain.AiLayer
import com.paperlens.app.domain.Paper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * AI 三层阅读仓：
 * - 按需生成（详情页点按钮才触发），按 (arxiv_id, layer) 缓存 Room；
 * - 流式输出经 onDelta 实时回调给 ViewModel，生成完成后整段落库；
 * - 未配置 AI 服务时 isConfigured 为 false，UI 展示引导文案，不阻塞其他功能。
 */
class AiRepository(
    private val openAiClient: OpenAiClient,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
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
    ): Result<String> {
        val settings = currentSettings()
        if (!settings.aiConfigured) {
            return Result.failure(IllegalStateException("尚未配置 AI 服务"))
        }
        return try {
            val text = openAiClient.streamChat(
                settings = settings,
                systemPrompt = Prompts.system(layer),
                userPrompt = Prompts.user(paper),
                onDelta = onDelta,
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

    suspend fun clearCache() = db.aiReadingDao().clear()
}
