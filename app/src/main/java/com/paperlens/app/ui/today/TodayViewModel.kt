package com.paperlens.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperlens.app.data.prefs.AppSettings
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
 * 今日页三种信息流（v1.2 三板块）：
 * ALL = arXiv AI 类目最新（国内可直连，冷启动就有内容）；
 * SUBSCRIPTIONS = 关键词订阅的 arXiv 合并；FEATURED = HF 当日榜（可达性受网络限制）。
 */
enum class TodayFeed(val label: String) { ALL("全部"), SUBSCRIPTIONS("订阅"), FEATURED("精选") }

class TodayViewModel(private val graph: AppGraph) : ViewModel() {

    data class UiState(
        val feed: TodayFeed = TodayFeed.ALL,
        val all: List<Paper> = emptyList(),
        val subscriptions: List<Paper> = emptyList(),
        val featured: List<Paper> = emptyList(),
        val savedIds: Set<String> = emptySet(),
        val refreshing: Boolean = false,
        val hasKeywords: Boolean = false,
        val keywordsCount: Int = 0,
        // 各信息流最近一次刷新是否因网络失败（用于空态时给出诚实文案）
        val allError: Boolean = false,
        val subscriptionsError: Boolean = false,
        val featuredError: Boolean = false,
    ) {
        val currentList: List<Paper>
            get() = when (feed) {
                TodayFeed.ALL -> all
                TodayFeed.SUBSCRIPTIONS -> subscriptions
                TodayFeed.FEATURED -> featured
            }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    val settings: StateFlow<AppSettings> =
        graph.settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

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
            graph.paperRepository.featuredFeed.collect { list ->
                _ui.update { it.copy(featured = list) }
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
        // 冷启动：缓存过期则后台刷新（全部 1h / 订阅 30min / 精选 6h，仓库内控）
        refreshAllFeed(force = false)
        refreshSubscriptions(force = false)
        refreshFeatured(force = false)
    }

    fun setFeed(feed: TodayFeed) {
        _ui.update { it.copy(feed = feed) }
    }

    fun refreshCurrent() {
        when (_ui.value.feed) {
            TodayFeed.ALL -> refreshAllFeed(force = true)
            TodayFeed.SUBSCRIPTIONS -> refreshSubscriptions(force = true)
            TodayFeed.FEATURED -> refreshFeatured(force = true)
        }
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
                val ok = graph.paperRepository.refreshAllFeed(force)
                if (!ok) _ui.update { it.copy(allError = true) }
            } finally {
                if (force) _ui.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun refreshSubscriptions(force: Boolean) {
        viewModelScope.launch {
            if (force) _ui.update { it.copy(refreshing = true) }
            try {
                val ok = graph.paperRepository.refreshSubscriptions(force)
                _ui.update { it.copy(subscriptionsError = !ok) }
            } finally {
                if (force) _ui.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun refreshFeatured(force: Boolean) {
        viewModelScope.launch {
            if (force) _ui.update { it.copy(refreshing = true) }
            try {
                val ok = graph.paperRepository.refreshFeatured(force)
                if (!ok) _ui.update { it.copy(featuredError = true) }
            } finally {
                if (force) _ui.update { it.copy(refreshing = false) }
            }
        }
    }
}
