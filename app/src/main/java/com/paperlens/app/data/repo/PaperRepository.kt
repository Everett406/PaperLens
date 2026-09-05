package com.paperlens.app.data.repo

import com.paperlens.app.data.db.AppDatabase
import com.paperlens.app.data.db.PaperEntity
import com.paperlens.app.data.db.toDomain
import com.paperlens.app.data.db.toEntity
import com.paperlens.app.data.remote.ArxivApi
import com.paperlens.app.data.remote.ArxivAtomParser
import com.paperlens.app.data.remote.HfApi
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
 * 论文仓：负责「今日」两个信息流的抓取/缓存/合并与 arXiv 搜索。
 *
 * 拉取策略（对应规格第三节）：
 * - 精选：HF Daily Papers 当日榜（本地时区），当天为空自动回退前一天；
 *   缓存超过 6 小时才后台刷新，列表永远先渲染 Room 缓存；
 * - 订阅：所有启用关键词的 arXiv 最新结果，逐关键词抓取、按 arxiv_id 去重（主键天然去重）、
 *   按发布时间排序；缓存 30 分钟内不重复拉（arXiv 更新频繁且量小）；
 * - 断网时所有刷新静默失败，UI 沿用缓存 —— 全 App 可离线浏览。
 *
 * arXiv 速率约束：全局串行（Mutex），相邻请求间隔 ≥3s，失败重试 1 次。
 */
class PaperRepository(
    private val hfApi: HfApi,
    private val arxivApi: ArxivApi,
    private val db: AppDatabase,
) {

    private val arxivGate = Mutex()

    @Volatile
    private var lastArxivCallAt = 0L

    val featuredFeed: Flow<List<Paper>> =
        db.paperDao().featuredFeed().map { list -> list.map(PaperEntity::toDomain) }

    val subscriptionFeed: Flow<List<Paper>> =
        db.paperDao().subscriptionFeed().map { list -> list.map(PaperEntity::toDomain) }

    /** —— 精选 —— */

    suspend fun refreshFeatured(force: Boolean) {
        if (!force) {
            val last = firstOrNull(db.paperDao().lastFetchedAt(PaperSource.HF_DAILY.name)) ?: 0L
            if (System.currentTimeMillis() - last < FEATURED_TTL_MS) return
        }
        runCatching {
            val today = LocalDate.now()
            var entries = runCatching { hfApi.dailyPapers(today.toString()) }.getOrElse { emptyList() }
            if (entries.isEmpty()) {
                // 当天为空（或接口异常）→ 回退前一天
                val yesterday = today.minusDays(1)
                entries = runCatching { hfApi.dailyPapers(yesterday.toString()) }.getOrElse { emptyList() }
            }
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
        }
        // 失败静默：离线/接口抖动时继续用缓存，不打断用户
    }

    /** —— 订阅 —— */

    suspend fun refreshSubscriptions(force: Boolean) {
        if (!force) {
            val last = firstOrNull(db.paperDao().lastFetchedAt(PaperSource.ARXIV.name)) ?: 0L
            if (System.currentTimeMillis() - last < SUBSCRIPTION_TTL_MS) return
        }
        val keywords = db.subscriptionDao().enabledKeywords()
        if (keywords.isEmpty()) return
        keywords.forEach { keyword ->
            runCatching {
                val xml = arxivRateLimited {
                    arxivApi.query(searchQuery = "all:\"$keyword\"", maxResults = 50)
                }
                storeArxivEntries(ArxivAtomParser.parse(xml), source = PaperSource.ARXIV, keyword = keyword)
            }
            // 单个关键词失败不影响其余关键词
        }
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
     * arXiv 调用闸门：全局串行 + 相邻请求 ≥3s + 失败重试一次（重试前再等 3s）。
     */
    private suspend fun <T> arxivRateLimited(block: suspend () -> T): T = arxivGate.withLock {
        val wait = lastArxivCallAt + ARXIV_MIN_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            delay(ARXIV_MIN_INTERVAL_MS)
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
        private const val ARXIV_MIN_INTERVAL_MS = 3_000L
    }
}
