package com.paperlens.app.data.repo

import com.paperlens.app.data.db.AppDatabase
import com.paperlens.app.data.db.PaperEntity
import com.paperlens.app.data.db.toDomain
import com.paperlens.app.data.db.toEntity
import com.paperlens.app.data.prefs.AppSettings
import com.paperlens.app.data.prefs.SettingsStore
import com.paperlens.app.data.remote.ArxivApi
import com.paperlens.app.data.remote.ArxivAtomParser
import com.paperlens.app.data.remote.HfApi
import com.paperlens.app.data.remote.HfEntryDto
import com.paperlens.app.domain.Paper
import com.paperlens.app.domain.PaperSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 论文仓：负责「今日」三个信息流的抓取/缓存/合并与 arXiv 搜索。
 *
 * 拉取策略（v1.2 国内可达性重构）：
 * - 全部：arXiv AI 类目最新提交（cs.AI/CL/CV/LG/RO/MA + stat.ML，国内可直连，
 *   是冷启动就能出内容的主信息源）；缓存 1 小时；
 * - 精选：HF Daily Papers 当日榜（本地时区），用户镜像优先、失败回退官方站；
 *   当天为空自动回退前一天；镜像不跟随重定向（hf-mirror 等 308 跳官方站的
 *   「伪镜像」会直接快速失败，不会在国内网络下被拖到超时）；
 * - 订阅：所有启用关键词的 arXiv 最新结果，逐关键词抓取、按 arxiv_id 去重（主键天然去重）、
 *   按发布时间排序；缓存 30 分钟内不重复拉；
 * - 刷新函数返回 Boolean（本次是否至少部分成功），UI 据此展示诚实错误态，
 *   而不是永远「还在路上」。
 *
 * arXiv 速率约束：全局串行（Mutex），相邻请求间隔 ≥3s；单次失败不重试
 * （下轮 TTL/手动下拉再试），避免搜索被批量订阅拉取堵在队尾几分钟。
 */
class PaperRepository(
    private val hfApi: HfApi,
    private val arxivApi: ArxivApi,
    private val db: AppDatabase,
    private val settingsStore: SettingsStore,
) {

    private val arxivGate = Mutex()

    @Volatile
    private var lastArxivCallAt = 0L

    val featuredFeed: Flow<List<Paper>> =
        db.paperDao().featuredFeed().map { list -> list.map(PaperEntity::toDomain) }

    val subscriptionFeed: Flow<List<Paper>> =
        db.paperDao().subscriptionFeed().map { list -> list.map(PaperEntity::toDomain) }

    val allFeed: Flow<List<Paper>> =
        db.paperDao().allFeed().map { list -> list.map(PaperEntity::toDomain) }

    /** —— 全部（arXiv AI 类目最新） —— */

    /** 返回 false 表示本次刷新因网络失败（缓存仍可用）。 */
    suspend fun refreshAllFeed(force: Boolean): Boolean {
        if (!force) {
            val last = firstOrNull(db.paperDao().lastFetchedAt(PaperSource.ARXIV_ALL.name)) ?: 0L
            if (System.currentTimeMillis() - last < ALL_FEED_TTL_MS) return true
        }
        return try {
            val xml = arxivRateLimited {
                arxivApi.query(searchQuery = AI_CATEGORIES_QUERY, maxResults = 100)
            }
            storeArxivEntries(ArxivAtomParser.parse(xml), source = PaperSource.ARXIV_ALL, keyword = null)
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            false
        }
    }

    /** —— 精选 —— */

    /** 返回 false 表示镜像与官方全部失败（缓存仍可用，UI 展示诚实错误态）。 */
    suspend fun refreshFeatured(force: Boolean): Boolean {
        if (!force) {
            val last = firstOrNull(db.paperDao().lastFetchedAt(PaperSource.HF_DAILY.name)) ?: 0L
            if (System.currentTimeMillis() - last < FEATURED_TTL_MS) return true
        }
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        // 拉取链：镜像今天 → 官方今天 → 镜像昨天 → 官方昨天
        //（镜像国内可直连；官方兜底防镜像站不同步；昨天兜底防当日未更新）
        // 镜像不跟随重定向：hf-mirror 对 API 路径 308 跳官方，跟随只会在国内被拖到超时
        val mirror = hfMirrorBase()
        val official = AppSettings.DEFAULT_HF_MIRROR_URL_OFFICIAL
        var entries: List<HfEntryDto>? = null
        for ((base, date) in listOf(mirror to today, official to today, mirror to yesterday, official to yesterday)) {
            entries = fetchDaily(base, date) ?: continue   // null = 网络失败，试下一站
            if (entries.isNotEmpty()) break                 // 空数组 = 当日未更新，继续往后兜底
        }
        if (entries == null) return false                   // 四站全败
        val now = System.currentTimeMillis()
        val entities = entries.mapNotNull { entry ->
                val p = entry.paper ?: return@mapNotNull null
                val rawId = p.id?.trim().takeUnless { it.isNullOrEmpty() } ?: return@mapNotNull null
                val arxivId = rawId.substringAfterLast('/').replace(Regex("v\\d+$"), "")
                if (arxivId.isBlank()) return@mapNotNull null
                PaperEntity(
                    arxivId = arxivId,
                    title = collapse(p.title.orEmpty()),
                    authors = p.authors.orEmpty().mapNotNull { it.name?.trim() }.filter { it.isNotEmpty() },
                    abstractText = collapse(p.summary.orEmpty()),
                    upvotes = p.upvotes ?: 0,
                    source = PaperSource.HF_DAILY.name,
                    sourceKeyword = null,
                    publishedAt = parseIso(p.publishedAt ?: entry.publishedAt) ?: now,
                    fetchedAt = now,
                    paperUrl = p.paperUrl?.takeIf { it.isNotBlank() } ?: "https://arxiv.org/abs/$arxivId",
            )
        }
        db.paperDao().upsertAll(entities)
        return true
    }

    /** —— 订阅 —— */

    /** 返回 false 表示所有启用关键词全部拉取失败。 */
    suspend fun refreshSubscriptions(force: Boolean): Boolean {
        if (!force) {
            val last = firstOrNull(db.paperDao().lastFetchedAt(PaperSource.ARXIV.name)) ?: 0L
            if (System.currentTimeMillis() - last < SUBSCRIPTION_TTL_MS) return true
        }
        val keywords = db.subscriptionDao().enabledKeywords()
        if (keywords.isEmpty()) return true
        var succeeded = 0
        keywords.forEach { keyword ->
            try {
                val xml = arxivRateLimited {
                    arxivApi.query(searchQuery = "all:\"$keyword\"", maxResults = 50)
                }
                storeArxivEntries(ArxivAtomParser.parse(xml), source = PaperSource.ARXIV, keyword = keyword)
                succeeded++
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // 单个关键词失败不影响其余关键词
            }
        }
        return succeeded > 0
    }

    /** —— 搜索 —— */

    suspend fun searchArxiv(query: String, maxResults: Int = 25): List<Paper> {
        val trimmed = query.trim()
        require(trimmed.isNotEmpty()) { "empty query" }
        val xml = arxivRateLimited {
            arxivApi.query(
                searchQuery = "(ti:\"$trimmed\" OR abs:\"$trimmed\")",
                maxResults = maxResults,
            )
        }
        val entries = ArxivAtomParser.parse(xml)
        storeArxivEntries(entries, source = PaperSource.SEARCH, keyword = null)
        return entriesToPapers(entries, PaperSource.SEARCH)
    }

    /** —— 公共 —— */

    suspend fun paperOnce(arxivId: String): Paper? = db.paperDao().paperOnce(arxivId)?.toDomain()

    fun searchCached(query: String): Flow<List<Paper>> =
        db.paperDao().searchCached(query).map { list -> list.map(PaperEntity::toDomain) }

    /** 供 ShelfRepository 兜底写入缓存。 */
    suspend fun ensureCached(paper: Paper) {
        db.paperDao().upsertAll(listOf(paper.toEntity()))
    }

    suspend fun clearCaches() {
        db.paperDao().clearUnshelved()
        db.aiReadingDao().clear()
        db.searchHistoryDao().clear()
    }

    /** —— 内部 —— */

    /** 用户设置的镜像基址（归一化；空回落默认镜像）。 */
    private suspend fun hfMirrorBase(): String {
        val configured = runCatching { settingsStore.settings.first().hfMirror }
            .getOrNull().orEmpty().trim().trimEnd('/')
        return configured.ifEmpty { AppSettings.DEFAULT_HF_MIRROR }
    }

    /** null = 网络/HTTP 失败；空数组 = 接口正常但当日无数据。 */
    private suspend fun fetchDaily(base: String, date: LocalDate): List<HfEntryDto>? =
        runCatching { hfApi.dailyPapers("$base/api/daily_papers", date.toString()) }
            .getOrNull()

    private suspend fun storeArxivEntries(
        entries: List<ArxivAtomParser.Entry>,
        source: PaperSource,
        keyword: String?,
    ) {
        val now = System.currentTimeMillis()
        val entities = entries.map { e ->
            PaperEntity(
                arxivId = e.arxivId,
                title = e.title,
                authors = e.authors,
                abstractText = e.summary,
                upvotes = 0,
                source = source.name,
                sourceKeyword = keyword,
                publishedAt = e.publishedAt,
                fetchedAt = now,
                paperUrl = e.paperUrl,
            )
        }
        db.paperDao().upsertAll(entities)
    }

    private fun entriesToPapers(
        entries: List<ArxivAtomParser.Entry>,
        source: PaperSource,
    ): List<Paper> = entries.map { e ->
        Paper(
            arxivId = e.arxivId,
            title = e.title,
            authors = e.authors,
            abstract = e.summary,
            upvotes = 0,
            source = source,
            sourceKeyword = null,
            publishedAt = e.publishedAt,
            fetchedAt = System.currentTimeMillis(),
            paperUrl = e.paperUrl,
        )
    }

    /**
     * arXiv 调用闸门：全局串行 + 相邻请求 ≥3s。
     * 不做自动重试：重试会让批量订阅占用闸门的时间翻倍，搜索会被堵在队尾；
     * 失败交给下轮 TTL 刷新或用户手动下拉。
     */
    private suspend fun <T> arxivRateLimited(block: suspend () -> T): T = arxivGate.withLock {
        val wait = lastArxivCallAt + ARXIV_MIN_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            lastArxivCallAt = System.currentTimeMillis()
        }
    }

    private fun collapse(raw: String): String = raw.replace(Regex("\\s+"), " ").trim()

    private fun parseIso(raw: String?): Long? {
        val s = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return runCatching { Instant.parse(s).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private suspend fun <T> firstOrNull(flow: Flow<T>): T? =
        runCatching { flow.first() }.getOrNull()

    companion object {
        private const val FEATURED_TTL_MS = 6 * 60 * 60 * 1000L
        private const val SUBSCRIPTION_TTL_MS = 30 * 60 * 1000L
        private const val ALL_FEED_TTL_MS = 60 * 60 * 1000L
        private const val ARXIV_MIN_INTERVAL_MS = 3_000L

        /** 「全部」流的类目：AI 主战场（HF Daily Papers 的收录范围基本是其子集）。 */
        private const val AI_CATEGORIES_QUERY =
            "(cat:cs.AI OR cat:cs.CL OR cat:cs.CV OR cat:cs.LG OR cat:cs.RO OR cat:cs.MA OR cat:stat.ML)"
    }
}
