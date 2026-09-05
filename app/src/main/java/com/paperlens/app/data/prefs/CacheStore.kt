package com.paperlens.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.cacheDataStore: DataStore<Preferences> by preferencesDataStore(name = "paperlens_cache")

/** 摘要 AI 翻译缓存条目（body 已是可直接渲染的 markdown）。 */
@Serializable
data class TranslationEntry(
    val text: String = "",
    val at: Long = 0,
    val model: String = "",
)

/** AI 每日精选结果缓存（ids 与 scores 一一对应）。 */
@Serializable
data class CuratedCache(
    val at: Long = 0,
    val model: String = "",
    val profileSize: Int = 0,
    val ids: List<String> = emptyList(),
    val scores: List<Float> = emptyList(),
)

/**
 * 缓存仓（v1.5）：与 SettingsStore 分文件（paperlens_cache）。
 * - 摘要 AI 翻译：LRU 40 条，重进详情页不再重复烧 token；
 * - AI 每日精选：持久化最近一次计算结果，重开 App 即时回放。
 * 纯缓存数据，清除时直接删 key，不涉及任何用户配置。
 */
class CacheStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val translations = stringPreferencesKey("translations_v1")
        val curated = stringPreferencesKey("curated_v1")
    }

    // —— 翻译缓存 ——

    fun observeTranslation(arxivId: String): Flow<TranslationEntry?> =
        context.cacheDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { p -> p[Keys.translations]?.let { parseTranslations(it) }?.get(arxivId) }

    suspend fun translationFor(arxivId: String): TranslationEntry? =
        context.cacheDataStore.data.first()[Keys.translations]
            ?.let { parseTranslations(it) }?.get(arxivId)

    suspend fun putTranslation(arxivId: String, entry: TranslationEntry) {
        context.cacheDataStore.edit { p ->
            val map = p[Keys.translations]?.let { parseTranslations(it) } ?: linkedMapOf()
            map.remove(arxivId)
            map[arxivId] = entry
            while (map.size > TRANSLATION_CAP) {
                val oldest = map.entries.minByOrNull { it.value.at }?.key
                if (oldest == null) break
                map.remove(oldest)
            }
            p[Keys.translations] = json.encodeToString(map)
        }
    }

    // —— 精选缓存 ——

    suspend fun curated(): CuratedCache? =
        context.cacheDataStore.data.first()[Keys.curated]
            ?.let { runCatching { json.decodeFromString<CuratedCache>(it) }.getOrNull() }

    suspend fun putCurated(cache: CuratedCache) {
        context.cacheDataStore.edit { it[Keys.curated] = json.encodeToString(cache) }
    }

    suspend fun clearCurated() {
        context.cacheDataStore.edit { it.remove(Keys.curated) }
    }

    private fun parseTranslations(raw: String): MutableMap<String, TranslationEntry> =
        runCatching {
            json.decodeFromString<Map<String, TranslationEntry>>(raw).toMutableMap()
        }.getOrDefault(linkedMapOf())

    private companion object {
        const val TRANSLATION_CAP = 40
    }
}
