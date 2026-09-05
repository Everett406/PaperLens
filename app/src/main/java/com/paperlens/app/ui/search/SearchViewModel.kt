package com.paperlens.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperlens.app.di.AppGraph
import com.paperlens.app.domain.Paper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 搜索（规格二）：全屏页、500ms 防抖实时调 arXiv（标题/摘要），
 * 最近 10 条历史存 Room；联网失败/超时回退本地缓存 LIKE 匹配（保证「断网可浏览」体验）。
 * v1.2：加了硬超时与错误归因 —— 之前 arXiv 慢/不可达时会永远停在「正在检索」。
 */
class SearchViewModel(private val graph: AppGraph) : ViewModel() {

    data class UiState(
        val query: String = "",
        val results: List<Paper> = emptyList(),
        val offline: Boolean = false,
        val searching: Boolean = false,
        val searchedOnce: Boolean = false,
        /** 最近一次检索失败的原因（中文，可空）。结果非空时展示为顶部提示。 */
        val errorMessage: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** 查询流独立于 UI 状态流，避免防抖期间把中间值写进 UI。 */
    private val queryFlow = MutableStateFlow("")

    val history = graph.searchRepository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedIds = graph.shelfRepository.observeSavedIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            queryFlow
                .debounce(500)   // 规格二：输入防抖 500ms
                .distinctUntilChanged()
                .filter { it.isNotBlank() }
                .collectLatest { q ->
                    _ui.update { it.copy(searching = true, errorMessage = null) }
                    try {
                        // 硬超时：包含排队等待 arXiv 闸门的时间，最多 45s 必须给用户交代
                        val papers = withTimeout(SEARCH_TIMEOUT_MS) {
                            graph.paperRepository.searchArxiv(q, maxResults = 25)
                        }
                        _ui.update {
                            it.copy(
                                results = papers,
                                offline = false,
                                searching = false,
                                searchedOnce = true,
                                errorMessage = null,
                            )
                        }
                        graph.searchRepository.record(q)
                    } catch (te: TimeoutCancellationException) {
                        fallbackToCache(q, "检索超时：arXiv 响应缓慢或网络不稳定，稍后再试一次")
                    } catch (ce: CancellationException) {
                        throw ce   // collectLatest 因新输入取消旧任务，属于正常流程
                    } catch (e: Exception) {
                        fallbackToCache(q, "检索失败：arXiv 暂时连不上，先看看本地缓存")
                    }
                }
        }
    }

    /** 网络检索失败 → 本地缓存 LIKE 兜底，仍可浏览，并带上失败原因。 */
    private suspend fun fallbackToCache(q: String, message: String) {
        val cached = runCatching { graph.paperRepository.searchCached(q).first() }.getOrDefault(emptyList())
        _ui.update {
            it.copy(
                results = cached,
                offline = true,
                searching = false,
                searchedOnce = true,
                errorMessage = message,
            )
        }
    }

    fun onQueryChange(q: String) {
        _ui.update { it.copy(query = q) }
        queryFlow.value = q
    }

    fun clearHistory() {
        viewModelScope.launch { graph.searchRepository.clear() }
    }

    fun toggleSave(paper: Paper, currentlySaved: Boolean) {
        viewModelScope.launch {
            if (currentlySaved) {
                graph.shelfRepository.remove(paper.arxivId)
            } else {
                graph.shelfRepository.save(paper)
            }
        }
    }

    companion object {
        private const val SEARCH_TIMEOUT_MS = 45_000L
    }
}
