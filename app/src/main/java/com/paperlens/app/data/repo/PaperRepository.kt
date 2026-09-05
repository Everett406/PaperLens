package com.paperlens.app.data.repo

import com.paperlens.app.data.db.AppDatabase
import com.paperlens.app.data.db.PaperEntity
import com.paperlens.app.data.db.toDomain
import com.paperlens.app.data.db.toEntity
import com.paperlens.app.data.remote.ArxivApi
import com.paperlens.app.data.remote.ArxivAtomParser
import com.paperlens.app.domain.Paper
import com.paperlens.app.domain.PaperSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 论文仓：负责「今日」两个信息流的抓取/缓存/合并与 arXiv 搜索。
 *
 * v1.3 渠道收敛（精选/HF 移除）：
 * - 全部：arXiv AI 类目最新提交（cs.AI/CL/CV/LG/RO/MA + stat.ML，国内可直连，
 *   冷启动就能出内容的主信息源）；缓存 1 小时；
 * - 订阅：所有启用关键词的 arXiv 最新结果，逐关键词抓取、按 arxiv_id 去重（主键天然去重）、
 *   按发布时间排序；缓存 30 分钟内不重复拉；
 * - 刷新函数返回 Boolean（本次是否至少部分成功），UI 据此展示诚实错误态。
 *
 * 解析与响应读取统一在 Dispatchers.Default 上执行（XmlPullParser 对上百条目有一定耗时，
 * 不占主线程）；arXiv 响应经 ResponseBody 原样取出，绝不经过 JSON 转换层。
 *
 * arXiv 速率约束：全局串行（Mutex），相邻请求间隔 ≥3s；单次失败不重试
 * （下轮 TTL/手动下拉再试），避免搜索被批量订阅拉取堵在队尾几分钟。
 */
class PaperRepository(
    private val arxivApi: ArxivApi,
    private val db: AppDatabase,
) {

    private val arxivGate = Mutex()

    @Volatile
    private var lastArxivCallAt = 0L

    val allFeed: Flow<List<Paper>> =
        db.paperDao().allFeed().map { list -> list.map(PaperEntity::toDomain) }

    val subscriptionFeed: Flow<List<Paper>> =
        db.paperDao().subscriptionFeed().map { list -> list.map(PaperEntity::toDomain) }

    /** —— 全部（arXiv AI 类目最新） —— */

    /** 返回 false 表示本次刷新因网络失败（缓存仍可用）。 */
    suspend fun refreshAllFeed(force: Boolean): Boolean {
        if (!force) {
            val last = firstOrNull(db.paperDao().lastFetchedAt(PaperSource.ARXIV_ALL.name)) ?: 0L
            if (System.currentTimeMillis() - last < ALL_FEED_TTL_MS) return true
        }
        return try {
            val entries = arxivQueryParsed(AI_CATEGORIES_QUERY, maxResults = 100)
            storeArxivEntries(entries, source = PaperSource.ARXIV_ALL, keyword = null)
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            false
        }
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
                val entries = arxivQueryParsed("all:\"$keyword\"", maxResults = 50)
                storeArxivEntries(entries, source = PaperSource.ARXIV, keyword = keyword)
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
        val entries = arxivQueryParsed("(ti:\"$trimmed\" OR abs:\"$trimmed\")", maxResults = maxResults)
        storeArxivEntries(entries, source = PaperSource.SEARCH, keyword = null)
        return entries.map { e ->
            Paper(
                arxivId = e.arxivId,
                title = e.title,
                authors = e.authors,
                abstract = e.summary,
                upvotes = 0,
                source = PaperSource.SEARCH,
                sourceKeyword = null,
                publishedAt = e.publishedAt,
                fetchedAt = System.currentTimeMillis(),
                paperUrl = e.paperUrl,
            )
        }
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

    /** 走 arXiv 闸门请求 + Default 线程上读取/解析，返回解析好的条目。 */
    private suspend fun arxivQueryParsed(searchQuery: String, maxResults: Int): List<ArxivAtomParser.Entry> {
        val body = arxivRateLimited {
            arxivApi.query(searchQuery = searchQuery, maxResults = maxResults)
        }
        return withContext(Dispatchers.Default) {
            val xml = body.use { it.string() }
            ArxivAtomParser.parse(xml)
        }
    }

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

    private suspend fun <T> firstOrNull(flow: Flow<T>): T? =
        runCatching { flow.first() }.getOrNull()

    companion object {
        private const val SUBSCRIPTION_TTL_MS = 30 * 60 * 1000L
        private const val ALL_FEED_TTL_MS = 60 * 60 * 1000L
        private const val ARXIV_MIN_INTERVAL_MS = 3_000L

        /** 「全部」流的类目：AI 主战场（HF Daily Papers 的收录范围基本是其子集）。 */
        private const val AI_CATEGORIES_QUERY =
            "(cat:cs.AI OR cat:cs.CL OR cat:cs.CV OR cat:cs.LG OR cat:cs.RO OR cat:cs.MA OR cat:stat.ML)"
    }
}
