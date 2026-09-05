package com.paperlens.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PaperDao {

    /**
     * 带来源优先级的 upsert：
     * - 标题/摘要/时间等字段以最新抓取为准；
     * - source 只升不降（HF_DAILY > ARXIV > ARXIV_ALL > SEARCH）：一篇先被搜索缓存、后被订阅命中的论文
     *   应归入订阅流；反之被 HF 榜单收录的论文永远留在精选流；
     * - sourceKeyword 保留首次认领的关键词；
     * - upvotes 取较大值，避免非 HF 来源的抓取把 HF 点赞数清零。
     */
    @Query(
        """
        INSERT INTO papers (arxivId, title, authors, abstractText, upvotes, source, sourceKeyword, publishedAt, fetchedAt, paperUrl)
        VALUES (:arxivId, :title, :authors, :abstractText, :upvotes, :source, :sourceKeyword, :publishedAt, :fetchedAt, :paperUrl)
        ON CONFLICT(arxivId) DO UPDATE SET
            title = excluded.title,
            authors = excluded.authors,
            abstractText = excluded.abstractText,
            upvotes = MAX(papers.upvotes, excluded.upvotes),
            publishedAt = excluded.publishedAt,
            fetchedAt = excluded.fetchedAt,
            paperUrl = COALESCE(excluded.paperUrl, papers.paperUrl),
            source = CASE
                WHEN papers.source = 'HF_DAILY' THEN 'HF_DAILY'
                WHEN papers.source = 'ARXIV' THEN 'ARXIV'
                WHEN papers.source = 'ARXIV_ALL' AND excluded.source = 'SEARCH' THEN 'ARXIV_ALL'
                ELSE excluded.source
            END,
            sourceKeyword = COALESCE(papers.sourceKeyword, excluded.sourceKeyword)
        """
    )
    suspend fun upsertOne(
        arxivId: String,
        title: String,
        authors: List<String>,
        abstractText: String,
        upvotes: Int,
        source: String,
        sourceKeyword: String?,
        publishedAt: Long,
        fetchedAt: Long,
        paperUrl: String?,
    )

    suspend fun upsertAll(papers: List<PaperEntity>) {
        val now = System.currentTimeMillis()
        papers.forEach { p ->
            upsertOne(
                arxivId = p.arxivId,
                title = p.title,
                authors = p.authors,
                abstractText = p.abstractText,
                upvotes = p.upvotes,
                source = p.source,
                sourceKeyword = p.sourceKeyword,
                publishedAt = p.publishedAt,
                fetchedAt = p.fetchedAt.takeIf { it > 0 } ?: now,
                paperUrl = p.paperUrl,
            )
        }
    }

    @Query("SELECT * FROM papers WHERE source = 'HF_DAILY' ORDER BY upvotes DESC, publishedAt DESC LIMIT 120")
    fun featuredFeed(): Flow<List<PaperEntity>>

    @Query("SELECT * FROM papers WHERE source = 'ARXIV' ORDER BY publishedAt DESC LIMIT 200")
    fun subscriptionFeed(): Flow<List<PaperEntity>>

    /** 「全部」流：arXiv AI 类目最新提交（国内可直连的主信息源）。 */
    @Query("SELECT * FROM papers WHERE source = 'ARXIV_ALL' ORDER BY publishedAt DESC LIMIT 150")
    fun allFeed(): Flow<List<PaperEntity>>

    @Query("SELECT MAX(fetchedAt) FROM papers WHERE source = :source")
    fun lastFetchedAt(source: String): Flow<Long?>

    @Query("SELECT * FROM papers WHERE arxivId = :arxivId")
    fun observePaper(arxivId: String): Flow<PaperEntity?>

    @Query("SELECT * FROM papers WHERE arxivId = :arxivId LIMIT 1")
    suspend fun paperOnce(arxivId: String): PaperEntity?

    /** 断网兜底：本地缓存的关键词匹配（标题/摘要）。 */
    @Query(
        """
        SELECT * FROM papers
        WHERE title LIKE '%' || :query || '%' OR abstractText LIKE '%' || :query || '%'
        ORDER BY publishedAt DESC LIMIT 40
        """
    )
    fun searchCached(query: String): Flow<List<PaperEntity>>

    /** 清空缓存：已收藏的论文永不删除（shelf 外键 RESTRICT 的语义由这条查询保证）。 */
    @Query("DELETE FROM papers WHERE arxivId NOT IN (SELECT arxivId FROM shelf)")
    suspend fun clearUnshelved()
}

@Dao
interface ShelfDao {

    @Query(
        """
        SELECT papers.*, shelf.status AS status, shelf.note AS note, shelf.savedAt AS savedAt
        FROM papers INNER JOIN shelf ON papers.arxivId = shelf.arxivId
        WHERE :status IS NULL OR shelf.status = :status
        ORDER BY shelf.savedAt DESC
        """
    )
    fun observeShelf(status: String?): Flow<List<ShelfItem>>

    @Query("SELECT arxivId FROM shelf")
    fun observeSavedIds(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM shelf WHERE arxivId = :arxivId)")
    fun observeSaved(arxivId: String): Flow<Boolean>

    @Upsert
    suspend fun insert(entry: ShelfEntry)

    @Query("DELETE FROM shelf WHERE arxivId = :arxivId")
    suspend fun remove(arxivId: String)

    @Query("UPDATE shelf SET status = :status WHERE arxivId = :arxivId")
    suspend fun setStatus(arxivId: String, status: String?)

    @Query("UPDATE shelf SET note = :note WHERE arxivId = :arxivId")
    suspend fun setNote(arxivId: String, note: String?)
}

@Dao
interface SubscriptionDao {

    @Query("SELECT * FROM subscriptions ORDER BY id DESC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT keyword FROM subscriptions WHERE enabled = 1")
    suspend fun enabledKeywords(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entity: SubscriptionEntity): Long

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE subscriptions SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 10")
    fun observeRecent(): Flow<List<SearchHistoryEntity>>

    @Query(
        """
        INSERT INTO search_history (query, searchedAt) VALUES (:query, :searchedAt)
        ON CONFLICT(query) DO UPDATE SET searchedAt = excluded.searchedAt
        """
    )
    suspend fun record(query: String, searchedAt: Long)

    @Query(
        "DELETE FROM search_history WHERE id NOT IN " +
            "(SELECT id FROM search_history ORDER BY searchedAt DESC LIMIT 10)"
    )
    suspend fun trimToTen()

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface AiReadingDao {

    @Query("SELECT * FROM ai_readings WHERE arxivId = :arxivId AND layer = :layer")
    fun observe(arxivId: String, layer: String): Flow<AiReadingEntity?>

    @Query("SELECT * FROM ai_readings WHERE arxivId = :arxivId AND layer = :layer LIMIT 1")
    suspend fun get(arxivId: String, layer: String): AiReadingEntity?

    @Upsert
    suspend fun put(entity: AiReadingEntity)

    @Query("DELETE FROM ai_readings")
    suspend fun clear()
}
