package com.paperlens.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperlens.app.data.prefs.AppSettings
import com.paperlens.app.data.repo.CuratedRepository
import com.paperlens.app.data.repo.ShelfRepository
import com.paperlens.app.di.AppGraph
import com.paperlens.app.domain.Paper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 今日页三种信息流（v1.5 新增「精选」）：
 * ALL = arXiv AI 类目最新（直连失败自动降级 GitHub 镜像）；
 * FEATURED = AI 每日精选（书架收藏 Embedding 画像 × 当日论文匹配，AI 服务驱动）；
 * SUBSCRIPTIONS = 关键词订阅的 arXiv 合并。
 */
enum class TodayFeed(val label: String, val origin: String) {
    ALL("全部", "all"),
    FEATURED("精选", "feat"),
    SUBSCRIPTIONS("订阅", "sub"),
}

class TodayViewModel(private val graph: AppGraph) : ViewModel() {

    data class UiState(
        val feed: TodayFeed = TodayFeed.ALL,
        val all: List<Paper> = emptyList(),
        val subscriptions: List<Paper> = emptyList(),
        val savedIds: Set<String> = emptySet(),
        val refreshing: Boolean = false,
        val hasKeywords: Boolean = false,
        val keywordsCount: Int = 0,
        // 各信息流最近一次刷新失败时的一句话原因（用于空态时给出诚实文案）
        val allError: Boolean = false,
        val allReason: String? = null,
        val subscriptionsError: Boolean = false,
        val subscriptionsReason: String? = null,
    ) {
        val currentList: List<Paper>
            get() = if (feed == TodayFeed.ALL) all else subscriptions
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    val settings: StateFlow<AppSettings> =
        graph.settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** AI 每日精选状态（CuratedRepository 驱动，精选页直接订阅）。 */
    val curated: StateFlow<CuratedRepository.State> = graph.curatedRepository.state

    /** 关键词签名：用于检测「我的」页增删/启停关键词后自动重拉订阅。 */
    private var lastKeywordSignature: String? = null

    init {
        // 列表永远先渲染 Room 缓存，网络成功后 merge
        viewModelScope.launch {
            graph.paperRepository.allFeed.collect { list ->
                _ui.update { it.copy(all = list) }
            }
        }
        viewModelScope.launch {
            graph.paperRepository.subscriptionFeed.collect { list ->
                _ui.update { it.copy(subscriptions = list) }
            }
        }
        viewModelScope.launch {
            graph.shelfRepository.observeSavedIds().collect { ids ->
                _ui.update { it.copy(savedIds = ids) }
            }
        }
        // 监听关键词变化：首次发射只记录签名（冷启动 TTL 逻辑负责首次加载），
        // 之后每次增删/启停都立即强制刷新订阅流 —— 用户加完关键词马上能看到效果。
        viewModelScope.launch {
            graph.subscriptionRepository.observeAll().collect { subs ->
                val enabled = subs.filter { it.enabled }.map { it.keyword }.sorted()
                val signature = enabled.joinToString("\u0001")
                val changed = lastKeywordSignature != null && signature != lastKeywordSignature
                lastKeywordSignature = signature
                _ui.update {
                    it.copy(hasKeywords = enabled.isNotEmpty(), keywordsCount = enabled.size)
                }
                if (changed && enabled.isNotEmpty()) refreshSubscriptions(force = true)
            }
        }
        // 冷启动：缓存过期则后台刷新（全部 1h / 订阅 30min，仓库内控）；
        // 精选：有缓存直接回放，首次无缓存且条件满足时自动算一次
        refreshAllFeed(force = false)
        refreshSubscriptions(force = false)
        viewModelScope.launch { graph.curatedRepository.refresh(force = false) }
    }

    fun setFeed(feed: TodayFeed) {
        _ui.update { it.copy(feed = feed) }
    }

    fun refreshCurrent() {
        when (_ui.value.feed) {
            TodayFeed.ALL -> refreshAllFeed(force = true)
            TodayFeed.FEATURED -> refreshCurated()
            TodayFeed.SUBSCRIPTIONS -> refreshSubscriptions(force = true)
        }
    }

    private fun refreshCurated() {
        viewModelScope.launch { graph.curatedRepository.refresh(force = true) }
    }

    fun toggleSave(paper: Paper) {
        viewModelScope.launch {
            if (paper.arxivId in _ui.value.savedIds) {
                graph.shelfRepository.remove(paper.arxivId)
            } else {
                graph.shelfRepository.save(paper)
            }
        }
    }

    private fun refreshAllFeed(force: Boolean) {
        viewModelScope.launch {
            if (force) _ui.update { it.copy(refreshing = true) }
            try {
                val result = graph.paperRepository.refreshAllFeed(force)
                _ui.update {
                    it.copy(
                        allError = !result.ok,
                        allReason = if (result.ok) null else result.reason,
                    )
                }
            } finally {
                if (force) _ui.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun refreshSubscriptions(force: Boolean) {
        viewModelScope.launch {
            if (force) _ui.update { it.copy(refreshing = true) }
            try {
                val result = graph.paperRepository.refreshSubscriptions(force)
                _ui.update {
                    it.copy(
                        subscriptionsError = !result.ok,
                        subscriptionsReason = if (result.ok) null else result.reason,
                    )
                }
            } finally {
                if (force) _ui.update { it.copy(refreshing = false) }
            }
        }
    }
}
