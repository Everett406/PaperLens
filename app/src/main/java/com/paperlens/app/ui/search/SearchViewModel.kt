package com.paperlens.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperlens.app.di.AppGraph
import com.paperlens.app.domain.Paper
import kotlinx.coroutines.FlowPreview
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

/**
 * 搜索（规格二）：全屏页、500ms 防抖实时调 arXiv（标题/摘要），
 * 最近 10 条历史存 Room；断网回退本地缓存 LIKE 匹配（保证验收「断网可浏览」体验）。
 */
class SearchViewModel(private val graph: AppGraph) : ViewModel() {

    data class UiState(
        val query: String = "",
        val results: List<Paper> = emptyList(),
        val offline: Boolean = false,
        val searching: Boolean = false,
        val searchedOnce: Boolean = false,
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
                    _ui.update { it.copy(searching = true) }
                    runCatching { graph.paperRepository.searchArxiv(q, maxResults = 25) }
                        .onSuccess { papers ->
                            _ui.update {
                                it.copy(
                                    results = papers,
                                    offline = false,
                                    searching = false,
                                    searchedOnce = true,
                                )
                            }
                            graph.searchRepository.record(q)
                        }
                        .onFailure {
                            // 断网/失败 → 本地缓存 LIKE 兜底，仍可浏览
                            val cached = graph.paperRepository.searchCached(q).first()
                            _ui.update {
                                it.copy(
                                    results = cached,
                                    offline = true,
                                    searching = false,
                                    searchedOnce = true,
                                )
                            }
                        }
                }
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
}
