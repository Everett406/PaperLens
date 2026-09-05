package com.paperlens.app.ui.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperlens.app.ai.OpenAiClient
import com.paperlens.app.data.prefs.AppSettings
import com.paperlens.app.data.prefs.SettingsStore
import com.paperlens.app.data.prefs.ThemeMode
import com.paperlens.app.di.AppGraph
import com.paperlens.app.domain.Paper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class MineViewModel(private val graph: AppGraph) : ViewModel() {

    data class AiDraft(
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
    )

    data class UiState(
        val settings: AppSettings = AppSettings(),
        val aiDraft: AiDraft = AiDraft(),
        val aiTestState: AiTestState = AiTestState.IDLE,
        val aiTestMessage: String? = null,
        val cacheCleared: Boolean = false,
    )

    enum class AiTestState { IDLE, TESTING, OK, FAIL }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    val subscriptions = graph.subscriptionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 设置流 → 初始化草稿（仅一次，避免用户输入被流回写覆盖）。 */
    private var draftInitialized = false

    @OptIn(FlowPreview::class)
    private val draftSaver = MutableStateFlow<AiDraft?>(null)

    init {
        viewModelScope.launch {
            graph.settingsStore.settings.collect { s ->
                _ui.update {
                    if (!draftInitialized) {
                        draftInitialized = true
                        it.copy(
                            settings = s,
                            aiDraft = AiDraft(s.aiBaseUrl, s.aiApiKey, s.aiModel),
                        )
                    } else {
                        it.copy(settings = s)
                    }
                }
            }
        }
        // 草稿防抖 500ms 落库
        viewModelScope.launch {
            draftSaver.debounce(500).collect { draft ->
                if (draft != null) {
                    graph.settingsStore.setAiService(draft.baseUrl, draft.apiKey, draft.model)
                }
            }
        }
    }

    fun updateAiDraft(transform: (AiDraft) -> AiDraft) {
        _ui.update { it.copy(aiDraft = transform(it.aiDraft)) }
        draftSaver.value = _ui.value.aiDraft
    }

    fun testAi() {
        val draft = _ui.value.aiDraft
        if (draft.baseUrl.isBlank() || draft.apiKey.isBlank() || draft.model.isBlank()) {
            _ui.update { it.copy(aiTestState = AiTestState.FAIL, aiTestMessage = "请先填全 Base URL / API Key / 模型名") }
            return
        }
        _ui.update { it.copy(aiTestState = AiTestState.TESTING, aiTestMessage = null) }
        viewModelScope.launch {
            val probe = AppSettings(
                aiBaseUrl = draft.baseUrl,
                aiApiKey = draft.apiKey,
                aiModel = draft.model,
            )
            // 测试前先立即落库，保证「去用」时配置已就绪
            graph.settingsStore.setAiService(draft.baseUrl, draft.apiKey, draft.model)
            graph.openAiClient.testConnection(probe)
                .onSuccess { ms ->
                    _ui.update {
                        it.copy(aiTestState = AiTestState.OK, aiTestMessage = "连接正常 · ${ms}ms")
                    }
                }
                .onFailure { e ->
                    _ui.update {
                        it.copy(
                            aiTestState = AiTestState.FAIL,
                            aiTestMessage = e.message ?: "连接失败",
                        )
                    }
                }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { graph.settingsStore.setThemeMode(mode) }
    }

    fun setSeedColor(color: Int) {
        viewModelScope.launch { graph.settingsStore.setSeedColor(color) }
    }

    fun addSubscription(keyword: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = graph.subscriptionRepository.add(keyword)
            onResult(ok)
        }
    }

    fun removeSubscription(id: Long) {
        viewModelScope.launch { graph.subscriptionRepository.delete(id) }
    }

    fun setSubscriptionEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { graph.subscriptionRepository.setEnabled(id, enabled) }
    }

    fun clearCache() {
        viewModelScope.launch {
            graph.paperRepository.clearCaches()
            _ui.update { it.copy(cacheCleared = true) }
        }
    }

    fun consumeCacheCleared() = _ui.update { it.copy(cacheCleared = false) }
}
