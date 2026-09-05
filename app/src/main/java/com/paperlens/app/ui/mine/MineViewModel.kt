package com.paperlens.app.ui.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperlens.app.data.prefs.AppSettings
import com.paperlens.app.data.prefs.SettingsStore
import com.paperlens.app.data.prefs.ThemeMode
import com.paperlens.app.di.AppGraph
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「我的」页 ViewModel（v1.1 起 AI 配置独立到 AiSettingsScreen，这里保留
 * 订阅管理 / 外观 / 数据设置）。
 */
@OptIn(FlowPreview::class)
class MineViewModel(private val graph: AppGraph) : ViewModel() {

    data class UiState(
        val settings: AppSettings = AppSettings(),
        val cacheCleared: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    val subscriptions = graph.subscriptionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            graph.settingsStore.settings.collect { s ->
                _ui.update { it.copy(settings = s) }
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
