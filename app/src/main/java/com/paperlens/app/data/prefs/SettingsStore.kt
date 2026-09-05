package com.paperlens.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "paperlens_settings")

/** 外观模式。SYSTEM = 跟随系统。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * AI 服务协议。
 * - OPENAI：OpenAI Chat Completions 兼容（OpenAI / DeepSeek / Kimi / GLM / OpenRouter 等）
 * - ANTHROPIC：Claude Messages API（x-api-key + anthropic-version）
 * - GEMINI：Google Generative Language API（streamGenerateContent SSE）
 */
enum class AiProtocol(val label: String, val defaultBaseUrl: String, val modelHint: String) {
    OPENAI("OpenAI 兼容", "https://api.openai.com", "如 deepseek-chat / gpt-4o-mini"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com", "如 claude-sonnet-4-5"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com", "如 gemini-2.5-flash");

    companion object {
        fun from(raw: String?): AiProtocol =
            entries.firstOrNull { it.name == raw } ?: OPENAI
    }
}

data class AppSettings(
    val aiBaseUrl: String = "",
    val aiApiKey: String = "",
    val aiModel: String = "",
    val aiProtocol: AiProtocol = AiProtocol.OPENAI,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 动态取色种子色（ARGB）。 */
    val seedColor: Int = DEFAULT_SEED,
) {
    val aiConfigured: Boolean
        get() = aiBaseUrl.isNotBlank() && aiApiKey.isNotBlank() && aiModel.isNotBlank()

    /** 适配协议后实际生效的 Base URL（空则用协议默认值）。 */
    val effectiveAiBaseUrl: String
        get() = aiBaseUrl.ifBlank { aiProtocol.defaultBaseUrl }

    companion object {
        const val DEFAULT_SEED: Int = 0xFF3D7EFF.toInt()
    }
}

/** DataStore 设置仓：小而稳，写操作即改即存。 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val aiBaseUrl = stringPreferencesKey("ai_base_url")
        val aiApiKey = stringPreferencesKey("ai_api_key")
        val aiModel = stringPreferencesKey("ai_model")
        val aiProtocol = stringPreferencesKey("ai_protocol")
        val themeMode = stringPreferencesKey("theme_mode")
        val seedColor = intPreferencesKey("seed_color")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { p ->
            AppSettings(
                aiBaseUrl = p[Keys.aiBaseUrl].orEmpty(),
                aiApiKey = p[Keys.aiApiKey].orEmpty(),
                aiModel = p[Keys.aiModel].orEmpty(),
                aiProtocol = AiProtocol.from(p[Keys.aiProtocol]),
                themeMode = runCatching { ThemeMode.valueOf(p[Keys.themeMode] ?: "") }
                    .getOrDefault(ThemeMode.SYSTEM),
                seedColor = p[Keys.seedColor] ?: AppSettings.DEFAULT_SEED,
            )
        }

    suspend fun setAiService(
        baseUrl: String,
        apiKey: String,
        model: String,
        protocol: AiProtocol = AiProtocol.OPENAI,
    ) {
        context.dataStore.edit { p ->
            p[Keys.aiBaseUrl] = baseUrl.trim()
            p[Keys.aiApiKey] = apiKey.trim()
            p[Keys.aiModel] = model.trim()
            p[Keys.aiProtocol] = protocol.name
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.themeMode] = mode.name }
    }

    suspend fun setSeedColor(color: Int) {
        context.dataStore.edit { it[Keys.seedColor] = color }
    }

    companion object
}
