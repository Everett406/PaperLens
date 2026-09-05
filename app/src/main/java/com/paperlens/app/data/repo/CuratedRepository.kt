package com.paperlens.app.data.repo

import com.paperlens.app.ai.AiClient
import com.paperlens.app.data.prefs.AiProtocol
import com.paperlens.app.data.prefs.CacheStore
import com.paperlens.app.data.prefs.CuratedCache
import com.paperlens.app.data.prefs.SettingsStore
import com.paperlens.app.domain.Paper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.math.sqrt

/**
 * AI 每日精选（v1.5）：Embedding 兴趣画像推荐。
 *
 * 原理：
 * 1. 把书架收藏的论文（标题+摘要）用 Embedding 模型向量化 → 这就是「你的口味画像」；
 * 2. 把当天抓到的论文同样向量化；
 * 3. 逐篇计算候选与画像的余弦相似度（取与画像中最像的前 3 篇收藏的平均值，
 *    这样即使收藏跨多个方向，每个方向都能命中）；
 * 4. 得分最高的 8 篇进入「今日 · 精选」，卡片上直接标出匹配度。
 *
 * 成本与隐私：每次计算 = 1~2 次 embeddings 批量请求（书架 ≤24 + 候选 ≤80 条文本），
 * 仅发给用户自己配置的 AI 服务商；结果持久化（CacheStore），冷启动直接回放。
 * Embedding 暂只支持 OpenAI 兼容协议（DeepSeek/Kimi 等部分国内服务商无 embeddings 端点）。
 */
class CuratedRepository(
    private val aiClient: AiClient,
    private val settingsStore: SettingsStore,
    private val shelfRepository: ShelfRepository,
    private val paperRepository: PaperRepository,
    private val cacheStore: CacheStore,
) {

    sealed interface State {
        /** 未开始计算（冷启动且无缓存时不自动消耗 token，等用户下拉或配置完成）。 */
        data object Idle : State
        data object Loading : State
        data object Unconfigured : State
        data object NeedTaste : State
        data object Unsupported : State
        data class Ready(val items: List<ScoredPaper>, val generatedAt: Long, val profileSize: Int) : State
        data class Error(val reason: String) : State
    }

    data class ScoredPaper(val paper: Paper, val score: Float)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /**
     * force = true：用户下拉，强制重算。
     * force = false：冷启动回放 —— 有缓存直接回放（哪怕过期也不自动烧 token）；
     * 无缓存且条件满足时自动计算一次（首次使用引导）。
     */
    suspend fun refresh(force: Boolean) {
        val settings = settingsStore.settings.first()
        if (!settings.aiConfigured) {
            if (force) _state.value = State.Unconfigured
            return
        }
        if (settings.aiProtocol != AiProtocol.OPENAI) {
            if (force) _state.value = State.Unsupported
            return
        }
        if (!force) {
            val cached = cacheStore.curated()
            if (cached != null && emitFromCache(cached)) return
        }

        _state.value = State.Loading
        try {
            val shelf = shelfRepository.observeAllShelf().first()
            val profile = shelf.sortedByDescending { it.savedAt }.take(MAX_PROFILE)
            if (profile.size < MIN_PROFILE) {
                _state.value = State.NeedTaste
                return
            }
            val shelfIds = shelf.map { it.paper.arxivId }.toSet()
            val candidates = paperRepository.allFeed.first()
                .filter { it.arxivId !in shelfIds }
                .take(MAX_CANDIDATES)
            if (candidates.isEmpty()) {
                _state.value = State.Error("今日论文列表还是空的，先去「全部」下拉抓取论文")
                return
            }

            val profileTexts = profile.map { embedText(it.paper.title, it.paper.abstractText) }
            val candidateTexts = candidates.map { embedText(it.title, it.abstract) }
            val vectors = aiClient.embedTexts(settings, profileTexts + candidateTexts)
                .getOrElse { e ->
                    _state.value = State.Error(e.message ?: "Embedding 请求失败")
                    return
                }

            val profileVectors = vectors.take(profile.size)
            val candidateVectors = vectors.drop(profile.size)
            val scored = candidates.mapIndexed { index, paper ->
                val vector = candidateVectors.getOrNull(index) ?: return@mapIndexed null
                val top3 = profileVectors.map { cosine(it, vector) }.sortedDescending().take(3)
                ScoredPaper(
                    paper = paper,
                    score = if (top3.isEmpty()) 0f else top3.average().toFloat(),
                )
            }.filterNotNull().sortedByDescending { it.score }

            val picked = scored.take(PICK_COUNT)
            _state.value = State.Ready(picked, System.currentTimeMillis(), profile.size)
            cacheStore.putCurated(
                CuratedCache(
                    at = System.currentTimeMillis(),
                    model = settings.embeddingModel,
                    profileSize = profile.size,
                    ids = picked.map { it.paper.arxivId },
                    scores = picked.map { it.score },
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            _state.update { State.Error(e.message ?: "精选计算失败") }
        }
    }

    private fun embedText(title: String, abstract: String): String =
        title.trim().take(300) + "\n" + abstract.trim().take(1200)

    /** 缓存回放：按 ids 从当前 papers 表拼回论文（被清掉的丢弃）。全部失效时返回 false。 */
    private suspend fun emitFromCache(cached: CuratedCache): Boolean {
        val papers = paperRepository.allFeed.first().associateBy { it.arxivId }
        val items = cached.ids.mapIndexedNotNull { index, id ->
            papers[id]?.let { paper ->
                ScoredPaper(paper, cached.scores.getOrNull(index) ?: 0f)
            }
        }
        if (items.isEmpty()) return false
        _state.value = State.Ready(items, cached.at, cached.profileSize)
        return true
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom <= 1e-9f) 0f else dot / denom
    }

    private companion object {
        const val MIN_PROFILE = 3
        const val MAX_PROFILE = 24
        const val MAX_CANDIDATES = 80
        const val PICK_COUNT = 8
    }
}
