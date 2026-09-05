package com.paperlens.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperlens.app.data.db.AiReadingEntity
import com.paperlens.app.data.db.toDomain
import com.paperlens.app.di.AppGraph
import com.paperlens.app.domain.AiLayer
import com.paperlens.app.domain.Paper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DetailViewModel(
    private val graph: AppGraph,
    private val arxivId: String,
) : ViewModel() {

    data class UiState(
        val paper: Paper? = null,
        val saved: Boolean = false,
        val layer: AiLayer = AiLayer.STORY,
        val reading: AiReadingEntity? = null,
        val streaming: Boolean = false,
        val streamingText: String = "",
        val aiConfigured: Boolean = false,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val layerFlow = MutableStateFlow(AiLayer.STORY)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val readingFlow = layerFlow.flatMapLatest { layer ->
        graph.aiRepository.observeReading(arxivId, layer)
    }

    init {
        viewModelScope.launch {
            graph.database.paperDao().observePaper(arxivId).collect { entity ->
                _ui.update { it.copy(paper = entity?.toDomain()) }
            }
        }
        viewModelScope.launch {
            graph.shelfRepository.observeSaved(arxivId).collect { saved ->
                _ui.update { it.copy(saved = saved) }
            }
        }
        viewModelScope.launch {
            readingFlow.collect { reading ->
                _ui.update { it.copy(reading = reading) }
            }
        }
        viewModelScope.launch {
            graph.aiRepository.isConfigured().collect { configured ->
                _ui.update { it.copy(aiConfigured = configured) }
            }
        }
    }

    fun setLayer(layer: AiLayer) {
        layerFlow.value = layer
        _ui.update { it.copy(layer = layer, error = null) }
    }

    fun toggleSave() {
        val paper = _ui.value.paper ?: return
        viewModelScope.launch {
            if (_ui.value.saved) {
                graph.shelfRepository.remove(paper.arxivId)
            } else {
                graph.shelfRepository.save(paper)
            }
        }
    }

    /** 生成 / 重新生成（重新生成 = 覆盖同 (arxiv_id, layer) 缓存）。 */
    fun generate() {
        val paper = _ui.value.paper ?: return
        val layer = _ui.value.layer
        if (_ui.value.streaming) return
        _ui.update { it.copy(streaming = true, streamingText = "", error = null) }
        viewModelScope.launch {
            val result = graph.aiRepository.generate(
                paper = paper,
                layer = layer,
                onDelta = { delta ->
                    _ui.update { it.copy(streamingText = it.streamingText + delta) }
                },
            )
            result.onSuccess { text ->
                _ui.update {
                    it.copy(
                        streaming = false,
                        // 生成完成：若当前层缓存已被本线程写入，reading Flow 会自动刷新
                        streamingText = text,
                    )
                }
                // 让流式文本平滑过渡到缓存的正式内容
                _ui.update { it.copy(streamingText = "") }
            }.onFailure { e ->
                _ui.update {
                    it.copy(
                        streaming = false,
                        error = e.message ?: "生成失败",
                    )
                }
            }
        }
    }
}
