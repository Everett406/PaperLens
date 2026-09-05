package com.paperlens.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PaperDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFresh(entity: PaperEntity): Long

    @Update
    suspend fun updateExisting(entity: PaperEntity)

    /**
     * upsert（v1.4.1 重写为实体化写入）：
     * 行不存在 → 直接插入；已存在 → Kotlin 侧逐字段合并后更新，语义与旧版
     * 裸 SQL 的 ON CONFLICT 完全一致：
     * - 内容字段（标题/作者/摘要/发布时间/链接）以最新抓取为准；
     * - source 只升不降（HF_DAILY > ARXIV > ARXIV_ALL > SEARCH）：一篇先被搜索缓存、
     *   后被订阅命中的论文应归入订阅流；反之被 HF 榜单收录过的论文永远留在精选流；
     * - sourceKeyword 保留首次认领的关键词；
     * - upvotes 取较大值，避免低优先级来源的抓取把高优先级点赞数清零。
     *
     * 为什么不再用裸 SQL INSERT：Room 对裸 @Query 里的 List 参数按集合展开成
     * N 个占位符（不经过 authors 的 TypeConverter），列表一长就编译出
     * 「13 values for 10 columns」的非法 SQL，运行期 SQLiteException，
     * 且被上层 catch 误报成网络失败 —— 这是 v1.0 起所有渠道「永远没数据」的真凶。
     * 实体化 @Insert/@Update 走 TypeConverter 绑定，天然正确。
     */
    suspend fun upsertAll(papers: List<PaperEntity>) {
        val now = System.currentTimeMillis()
        papers.forEach { p ->
            val entity = if (p.fetchedAt > 0) p else p.copy(fetchedAt = now)
            val existing = paperOnce(entity.arxivId)
            when {
                existing == null -> insertFresh(entity)
                else -> updateExisting(mergeForUpsert(existing, entity))
            }
        }
    }

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

/** 来源优先级：数值大者优先保留；未知来源按最低档处理。 */
private val SOURCE_RANK = mapOf("HF_DAILY" to 4, "ARXIV" to 3, "ARXIV_ALL" to 2)

private fun mergeSource(old: String, new: String): String =
    if ((SOURCE_RANK[old] ?: 1) > (SOURCE_RANK[new] ?: 1)) old else new

private fun mergeForUpsert(old: PaperEntity, new: PaperEntity): PaperEntity = old.copy(
    title = new.title,
    authors = new.authors,
    abstractText = new.abstractText,
    upvotes = maxOf(old.upvotes, new.upvotes),
    publishedAt = new.publishedAt,
    fetchedAt = new.fetchedAt,
    paperUrl = new.paperUrl ?: old.paperUrl,
    source = mergeSource(old.source, new.source),
    sourceKeyword = old.sourceKeyword ?: new.sourceKeyword,
)

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
