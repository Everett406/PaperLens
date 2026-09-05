package com.paperlens.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperlens.app.data.db.AiReadingEntity
import com.paperlens.app.data.db.toDomain
import com.paperlens.app.data.repo.AiReadManager
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

/**
 * 论文详情 VM（v1.5）：
 * - AI 生成全部交给 [AiReadManager] 后台队列 —— 返回退出本页不中断生成，
 *   重新进入后自动接上进度（流式文本/思考/排队状态都从队列读取）；
 * - 「一次生成三层」：把尚无缓存的层按 故事→细节→第一性原理 依次入队；
 * - 摘要 AI 翻译：流式渲染 + LRU 缓存（重进不重复烧 token）。
 */
class DetailViewModel(
    private val graph: AppGraph,
    private val arxivId: String,
) : ViewModel() {

    data class UiState(
        val paper: Paper? = null,
        val saved: Boolean = false,
        val layer: AiLayer = AiLayer.STORY,
        val reading: AiReadingEntity? = null,
        val aiConfigured: Boolean = false,
        /** 当前层关联的后台任务（排队/运行中/失败未隐藏时非空）。 */
        val job: AiReadManager.AiJob? = null,
        /** 本篇论文后台任务数（排队+运行中），用于「正在阅读其他层」提示。 */
        val activeCount: Int = 0,
        // —— 摘要翻译 ——
        val translation: String? = null,
        val translationStreaming: Boolean = false,
        val translationError: String? = null,
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
        // 后台队列状态 → 当前层任务 + 本篇任务计数
        viewModelScope.launch {
            graph.aiReadManager.jobs.collect { jobs ->
                _ui.update { st ->
                    val mine = jobs.filter { it.arxivId == arxivId }
                    st.copy(
                        job = mine.firstOrNull {
                            it.layer == st.layer &&
                                (it.status == AiReadManager.Status.QUEUED ||
                                    it.status == AiReadManager.Status.RUNNING ||
                                    (it.status == AiReadManager.Status.FAILED && !it.hidden))
                        },
                        activeCount = mine.count {
                            it.status == AiReadManager.Status.QUEUED || it.status == AiReadManager.Status.RUNNING
                        },
                    )
                }
            }
        }
        // 回放缓存翻译
        viewModelScope.launch {
            graph.aiRepository.cachedTranslation(arxivId)?.let { entry ->
                _ui.update { it.copy(translation = entry.text) }
            }
        }
    }

    fun setLayer(layer: AiLayer) {
        layerFlow.value = layer
        _ui.update { it.copy(layer = layer) }
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

    /** 生成/重新生成当前层：入队后台任务，返回退出都不中断。 */
    fun generate() {
        val paper = _ui.value.paper ?: return
        graph.aiReadManager.enqueue(paper, _ui.value.layer)
    }

    /** 一次生成三层：只补尚无缓存且未在跑的层，按 故事→细节→第一性原理 排队。 */
    fun generateAll() {
        val paper = _ui.value.paper ?: return
        viewModelScope.launch {
            AiLayer.entries.forEach { layer ->
                val cached = graph.database.aiReadingDao().get(paper.arxivId, layer.name)
                if (cached == null) {
                    graph.aiReadManager.enqueue(paper, layer)
                }
            }
        }
    }

    /** AI 翻译开关：未显示 → 流式翻译（命中缓存直接回放）；已显示 → 收起。 */
    fun toggleTranslation() {
        val paper = _ui.value.paper ?: return
        val st = _ui.value
        if (st.translation != null || st.translationStreaming) {
            _ui.update { it.copy(translation = null, translationError = null) }
            return
        }
        viewModelScope.launch {
            val cached = graph.aiRepository.cachedTranslation(paper.arxivId)
            if (cached != null) {
                _ui.update { it.copy(translation = cached.text, translationError = null) }
                return@launch
            }
            _ui.update { it.copy(translationStreaming = true, translation = "", translationError = null) }
            val result = graph.aiRepository.translateAbstract(paper) { delta ->
                _ui.update { it.copy(translation = (it.translation ?: "") + delta) }
            }
            result.onSuccess {
                _ui.update { it.copy(translationStreaming = false) }
            }.onFailure { e ->
                _ui.update {
                    it.copy(
                        translationStreaming = false,
                        translation = null,
                        translationError = e.message ?: "翻译失败",
                    )
                }
            }
        }
    }
}
