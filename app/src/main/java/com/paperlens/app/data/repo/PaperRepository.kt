package com.paperlens.app.data.repo

import com.paperlens.app.data.db.AppDatabase
import com.paperlens.app.data.db.PaperEntity
import com.paperlens.app.data.db.toDomain
import com.paperlens.app.data.db.toEntity
import com.paperlens.app.data.remote.ArxivApi
import com.paperlens.app.data.remote.ArxivAtomParser
import com.paperlens.app.data.remote.FeedMirrorClient
import com.paperlens.app.data.remote.NetDiag
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
 * v1.4 渠道纵深（国内可达性）：
 * - 全部流双通道：export.arxiv.org 直连优先（失败原因记入 [NetDiag]），
 *   失败或返回空集时自动降级 GitHub 仓库镜像 feed/all.json（Feed Mirror 工作流
 *   每 6h 与 arXiv 同参数同步；用户网络到 GitHub 已证实可达）；
 * - 订阅/搜索保持 arXiv 直连（任意关键词无法预缓存），失败给诚实原因；
 * - 刷新函数返回 [RefreshResult]（成功与否 + 一句话原因），UI 据此展示。
 *
 * 解析与响应读取统一在 Dispatchers.Default 上执行；arXiv 响应经 ResponseBody
 * 原样取出，绝不经过 JSON 转换层；镜像 JSON 走 kotlinx.serialization。
 *
 * arXiv 速率约束：全局串行（Mutex），相邻请求间隔 ≥3s；单次失败不自动重试
 * （全部流的重试由镜像通道承担），避免搜索被批量订阅拉取堵在队尾。
 */
class PaperRepository(
    private val arxivApi: ArxivApi,
    private val mirrorClient: FeedMirrorClient,
    private val netDiag: NetDiag,
    private val db: AppDatabase,
) {

    data class RefreshResult(val ok: Boolean, val reason: String? = null)

    private val arxivGate = Mutex()

    @Volatile
    private var lastArxivCallAt = 0L

    val allFeed: Flow<List<Paper>> =
        db.paperDao().allFeed().map { list -> list.map(PaperEntity::toDomain) }

    val subscriptionFeed: Flow<List<Paper>> =
        db.paperDao().subscriptionFeed().map { list -> list.map(PaperEntity::toDomain) }

    /** —— 全部（arXiv 直连 → GitHub 镜像兜底） —— */

    suspend fun refreshAllFeed(force: Boolean): RefreshResult {
        if (!force) {
            val last = firstOrNull(db.paperDao().lastFetchedAt(PaperSource.ARXIV_ALL.name)) ?: 0L
            if (System.currentTimeMillis() - last < ALL_FEED_TTL_MS) return RefreshResult(true)
        }
        // 1) arXiv 直连
        try {
            val entries = arxivQueryParsed(AI_CATEGORIES_QUERY, maxResults = 100)
            if (entries.isNotEmpty()) {
                storeArxivEntries(entries, source = PaperSource.ARXIV_ALL, keyword = null)
                return RefreshResult(true)
            }
            netDiag.record("全部/arXiv", ARXIV_HOST, "返回 0 篇（响应异常），转镜像通道")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            netDiag.record("全部/arXiv", ARXIV_HOST, e)
        }
        // 2) GitHub 镜像兜底
        return refreshAllFeedFromMirror()
    }

    private suspend fun refreshAllFeedFromMirror(): RefreshResult {
        return try {
            val feed = mirrorClient.fetchAllFeed()
            if (feed.papers.isEmpty()) {
                netDiag.record("全部/镜像", MIRROR_HOST, "镜像返回空数据")
                RefreshResult(false, "镜像源也没有可用数据")
            } else {
                storeMirrorPapers(feed.papers)
                RefreshResult(true)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            netDiag.record("全部/镜像", MIRROR_HOST, e)
            RefreshResult(false, netDiag.reason(e))
        }
    }

    /** —— 订阅 —— */

    suspend fun refreshSubscriptions(force: Boolean): RefreshResult {
        if (!force) {
            val last = firstOrNull(db.paperDao().lastFetchedAt(PaperSource.ARXIV.name)) ?: 0L
            if (System.currentTimeMillis() - last < SUBSCRIPTION_TTL_MS) return RefreshResult(true)
        }
        val keywords = db.subscriptionDao().enabledKeywords()
        if (keywords.isEmpty()) return RefreshResult(true)
        var succeeded = 0
        var firstReason: String? = null
        keywords.forEach { keyword ->
            try {
                val entries = arxivQueryParsed("all:\"$keyword\"", maxResults = 50)
                storeArxivEntries(entries, source = PaperSource.ARXIV, keyword = keyword)
                succeeded++
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // 单个关键词失败不影响其余关键词；原因记诊断，最终上屏
                netDiag.record("订阅/$keyword", ARXIV_HOST, e)
                if (firstReason == null) firstReason = netDiag.reason(e)
            }
        }
        return if (succeeded > 0) RefreshResult(true) else RefreshResult(false, firstReason)
    }

    /** —— 搜索 —— */

    suspend fun searchArxiv(query: String, maxResults: Int = 25): List<Paper> {
        val trimmed = query.trim()
        require(trimmed.isNotEmpty()) { "empty query" }
        try {
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
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            netDiag.record("搜索/$trimmed", ARXIV_HOST, e)
            throw e
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

    /** 镜像 JSON → 论文实体（来源仍记 ARXIV_ALL，用户无感知差别）。 */
    private suspend fun storeMirrorPapers(papers: List<FeedMirrorClient.MirrorPaper>) {
        val now = System.currentTimeMillis()
        val entities = papers.map { p ->
            PaperEntity(
                arxivId = p.arxivId,
                title = p.title,
                authors = p.authors,
                abstractText = p.abstract,
                upvotes = 0,
                source = PaperSource.ARXIV_ALL.name,
                sourceKeyword = null,
                publishedAt = p.publishedAt.takeIf { it > 0 } ?: now,
                fetchedAt = now,
                paperUrl = p.paperUrl ?: "https://arxiv.org/abs/${p.arxivId}",
            )
        }
        db.paperDao().upsertAll(entities)
    }

    /**
     * arXiv 调用闸门：全局串行 + 相邻请求 ≥3s。
     * 不做自动重试：重试会让批量订阅占用闸门的时间翻倍，搜索会被堵在队尾；
     * 失败交给镜像通道（全部流）或下轮 TTL/手动下拉。
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

        private const val ARXIV_HOST = "export.arxiv.org"
        private const val MIRROR_HOST = "api.github.com"

        /** 「全部」流的类目：AI 主战场（Feed Mirror 工作流使用同一查询）。 */
        const val AI_CATEGORIES_QUERY =
            "(cat:cs.AI OR cat:cs.CL OR cat:cs.CV OR cat:cs.LG OR cat:cs.RO OR cat:cs.MA OR cat:stat.ML)"
    }
}
