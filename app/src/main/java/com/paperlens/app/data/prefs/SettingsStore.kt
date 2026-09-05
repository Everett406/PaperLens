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

data class AppSettings(
    val aiBaseUrl: String = "",
    val aiApiKey: String = "",
    val aiModel: String = "",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 动态取色种子色（ARGB）。 */
    val seedColor: Int = DEFAULT_SEED,
) {
    val aiConfigured: Boolean
        get() = aiBaseUrl.isNotBlank() && aiApiKey.isNotBlank() && aiModel.isNotBlank()

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
                themeMode = runCatching { ThemeMode.valueOf(p[Keys.themeMode] ?: "") }
                    .getOrDefault(ThemeMode.SYSTEM),
                seedColor = p[Keys.seedColor] ?: AppSettings.DEFAULT_SEED,
            )
        }

    suspend fun setAiService(baseUrl: String, apiKey: String, model: String) {
        context.dataStore.edit { p ->
            p[Keys.aiBaseUrl] = baseUrl.trim()
            p[Keys.aiApiKey] = apiKey.trim()
            p[Keys.aiModel] = model.trim()
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.themeMode] = mode.name }
    }

    suspend fun setSeedColor(color: Int) {
        context.dataStore.edit { it[Keys.seedColor] = color }
    }
}
