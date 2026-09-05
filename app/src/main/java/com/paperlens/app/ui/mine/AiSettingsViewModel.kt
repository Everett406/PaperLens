package com.paperlens.app.ui.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperlens.app.ai.AiClient
import com.paperlens.app.data.prefs.AiProtocol
import com.paperlens.app.data.prefs.AppSettings
import com.paperlens.app.di.AppGraph
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AI 服务设置页（v1.1 新增独立页面）：
 * - 协议切换（OpenAI 兼容 / Anthropic / Gemini）+ 常用服务商预设一键填充；
 * - Base URL / API Key / 模型名草稿防抖落库，即改即存；
 * - 连通性测试用当前草稿实时探测，结果带排障提示。
 */
class AiSettingsViewModel(private val graph: AppGraph) : ViewModel() {

    data class Draft(
        val protocol: AiProtocol = AiProtocol.OPENAI,
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
    )

    data class UiState(
        val settings: AppSettings = AppSettings(),
        val draft: Draft = Draft(),
        val testState: TestState = TestState.IDLE,
        val testMessage: String? = null,
        // v1.5：云端模型列表拉取
        val modelsLoading: Boolean = false,
        val models: List<String>? = null,
        val modelsMessage: String? = null,
        // v1.5：Embedding 模型（AI 每日精选用）
        val embeddingModel: String = AppSettings.DEFAULT_EMBEDDING_MODEL,
    )

    enum class TestState { IDLE, TESTING, OK, FAIL }

    /** 常用服务商预设：点击填充 Base URL（模型名仅在留空时顺带填充）。 */
    data class Preset(val label: String, val baseUrl: String, val domestic: Boolean, val model: String? = null)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var draftInitialized = false
    private val draftSaver = MutableStateFlow<Draft?>(null)
    private val embeddingSaver = MutableStateFlow<String?>(null)

    val presets: Map<AiProtocol, List<Preset>> = mapOf(
        AiProtocol.OPENAI to listOf(
            Preset("DeepSeek", "https://api.deepseek.com", domestic = true, model = "deepseek-chat"),
            Preset("Kimi · 月之暗面", "https://api.moonshot.cn", domestic = true),
            Preset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", domestic = true),
            Preset("OpenRouter", "https://openrouter.ai/api", domestic = false),
            Preset("OpenAI 官方", "https://api.openai.com", domestic = false, model = "gpt-4o-mini"),
        ),
        AiProtocol.ANTHROPIC to listOf(
            Preset("Anthropic 官方", "https://api.anthropic.com", domestic = false),
        ),
        AiProtocol.GEMINI to listOf(
            Preset("Google AI 官方", "https://generativelanguage.googleapis.com", domestic = false),
        ),
    )

    init {
        viewModelScope.launch {
            graph.settingsStore.settings.collect { s ->
                _ui.update {
                    if (!draftInitialized) {
                        draftInitialized = true
                        it.copy(
                            settings = s,
                            draft = Draft(s.aiProtocol, s.aiBaseUrl, s.aiApiKey, s.aiModel),
                            embeddingModel = s.embeddingModel,
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
                if (draft != null) persist(draft)
            }
        }
        // Embedding 模型防抖落库
        viewModelScope.launch {
            embeddingSaver.debounce(500).collect { model ->
                if (model != null) graph.settingsStore.setEmbeddingModel(model)
            }
        }
    }

    fun updateDraft(transform: (Draft) -> Draft) {
        _ui.update { it.copy(draft = transform(it.draft), testMessage = null) }
        draftSaver.value = _ui.value.draft
    }

    /** 切协议：Base URL 为空或等于旧协议默认值时，跟随新协议默认值。 */
    fun setProtocol(protocol: AiProtocol) {
        updateDraft { d ->
            val followsDefault = d.baseUrl.isBlank() || d.baseUrl.trimEnd('/') == d.protocol.defaultBaseUrl
            d.copy(
                protocol = protocol,
                baseUrl = if (followsDefault) protocol.defaultBaseUrl else d.baseUrl,
            )
        }
    }

    fun applyPreset(preset: Preset) {
        updateDraft { d ->
            d.copy(
                protocol = protocolForPreset(preset),
                baseUrl = preset.baseUrl,
                model = d.model.ifBlank { preset.model.orEmpty() },
            )
        }
    }

    private fun protocolForPreset(preset: Preset): AiProtocol =
        presets.entries.firstOrNull { (_, list) -> list.any { it.baseUrl == preset.baseUrl } }?.key
            ?: _ui.value.draft.protocol

    fun test() {
        val draft = _ui.value.draft
        if (draft.baseUrl.isBlank() || draft.apiKey.isBlank() || draft.model.isBlank()) {
            _ui.update {
                it.copy(testState = TestState.FAIL, testMessage = "请先填全 Base URL / API Key / 模型名")
            }
            return
        }
        _ui.update { it.copy(testState = TestState.TESTING, testMessage = null) }
        viewModelScope.launch {
            // 测试前先立即落库，保证返回详情页即可用
            persist(draft)
            val probe = AppSettings(
                aiBaseUrl = draft.baseUrl,
                aiApiKey = draft.apiKey,
                aiModel = draft.model,
                aiProtocol = draft.protocol,
            )
            graph.aiClient.testConnection(probe)
                .onSuccess { ms ->
                    _ui.update { it.copy(testState = TestState.OK, testMessage = "连接正常 · ${ms}ms") }
                }
                .onFailure { e ->
                    _ui.update {
                        it.copy(testState = TestState.FAIL, testMessage = e.message ?: "连接失败")
                    }
                }
        }
    }

    // —— v1.5：云端模型列表 ——

    /** 从服务商拉取 /models 列表，成功后弹选择框。 */
    fun fetchModels() {
        val draft = _ui.value.draft
        if (draft.baseUrl.isBlank() || draft.apiKey.isBlank()) {
            _ui.update { it.copy(modelsMessage = "请先填好 Base URL 和 API Key") }
            return
        }
        _ui.update { it.copy(modelsLoading = true, modelsMessage = null) }
        viewModelScope.launch {
            // 拉取前先落库，保证鉴权与地址一致
            persist(draft)
            val probe = AppSettings(
                aiBaseUrl = draft.baseUrl,
                aiApiKey = draft.apiKey,
                aiModel = draft.model,
                aiProtocol = draft.protocol,
            )
            graph.aiClient.listModels(probe)
                .onSuccess { models ->
                    _ui.update { it.copy(modelsLoading = false, models = models) }
                }
                .onFailure { e ->
                    _ui.update {
                        it.copy(modelsLoading = false, modelsMessage = e.message ?: "拉取失败")
                    }
                }
        }
    }

    fun applyModel(model: String) {
        updateDraft { it.copy(model = model) }
        _ui.update { it.copy(models = null) }
    }

    fun dismissModels() {
        _ui.update { it.copy(models = null) }
    }

    fun updateEmbeddingModel(value: String) {
        _ui.update { it.copy(embeddingModel = value) }
        embeddingSaver.value = value
    }

    private suspend fun persist(draft: Draft) {
        graph.settingsStore.setAiService(
            baseUrl = draft.baseUrl,
            apiKey = draft.apiKey,
            model = draft.model,
            protocol = draft.protocol,
        )
    }
}
