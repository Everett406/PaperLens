package com.paperlens.app.ui.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperlens.app.data.db.toDomain
import com.paperlens.app.di.AppGraph
import com.paperlens.app.domain.Paper
import com.paperlens.app.domain.ShelfStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 书架 chips（规格二 2）：全部 / 稍后读 / 已读。 */
enum class ShelfFilter(val label: String) { ALL("全部"), LATER("稍后读"), READ("已读") }

class ShelfViewModel(private val graph: AppGraph) : ViewModel() {

    data class ShelfItemUi(
        val paper: Paper,
        val status: ShelfStatus,
        val note: String?,
        val savedAt: Long,
    )

    data class UiState(
        val filter: ShelfFilter = ShelfFilter.ALL,
        val items: List<ShelfItemUi> = emptyList(),
    )

    private val filter = MutableStateFlow(ShelfFilter.ALL)

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui: StateFlow<UiState> = combine(
        filter.flatMapLatest { f ->
            when (f) {
                ShelfFilter.ALL -> graph.shelfRepository.observeAllShelf()
                ShelfFilter.LATER -> graph.shelfRepository.observeShelf(ShelfStatus.LATER)
                ShelfFilter.READ -> graph.shelfRepository.observeShelf(ShelfStatus.READ)
            }
        },
        filter,
    ) { items, f ->
        UiState(
            filter = f,
            items = items.map { ShelfItemUi(it.toDomain(), ShelfStatus.fromDb(it.status), it.note, it.savedAt) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setFilter(f: ShelfFilter) {
        filter.value = f
    }

    fun setStatus(arxivId: String, status: ShelfStatus) {
        viewModelScope.launch { graph.shelfRepository.setStatus(arxivId, status) }
    }

    fun setNote(arxivId: String, note: String?) {
        viewModelScope.launch { graph.shelfRepository.setNote(arxivId, note) }
    }

    fun remove(arxivId: String) {
        viewModelScope.launch { graph.shelfRepository.remove(arxivId) }
    }

    /** 书架卡片的书签按钮 = 移除收藏。 */
    fun toggleSave(paper: Paper, currentlySaved: Boolean) {
        viewModelScope.launch {
            if (currentlySaved) graph.shelfRepository.remove(paper.arxivId)
        }
    }
}
