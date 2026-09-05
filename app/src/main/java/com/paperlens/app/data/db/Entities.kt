package com.paperlens.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.paperlens.app.domain.Paper
import com.paperlens.app.domain.PaperSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * papers：全局论文缓存，arxiv_id 全局唯一（主键）。
 * source 表明该行最初/最高优先级的来源，用于三个信息流各自的查询：
 *   来源优先级 HF_DAILY > ARXIV > SEARCH（见 PaperDao.upsertPreservingPriority 的冲突策略）。
 */
@Entity(tableName = "papers")
data class PaperEntity(
    @PrimaryKey val arxivId: String,
    val title: String,
    val authors: List<String>,
    val abstractText: String,
    val upvotes: Int,
    val source: String,
    val sourceKeyword: String?,
    val publishedAt: Long,
    val fetchedAt: Long,
    val paperUrl: String?,
)

/**
 * shelf：书架。与 papers 是逻辑外键（不设 CASCADE——
 * 「清空缓存」只删除未被收藏的 papers，收藏条目永不因缓存清理丢失）。
 */
@Entity(
    tableName = "shelf",
    foreignKeys = [
        ForeignKey(
            entity = PaperEntity::class,
            parentColumns = ["arxivId"],
            childColumns = ["arxivId"],
            onDelete = ForeignKey.RESTRICT,
        )
    ],
    indices = [Index("arxivId")],
)
data class ShelfEntry(
    @PrimaryKey val arxivId: String,
    /** ShelfStatus.dbValue；null = 已收藏未分类 */
    val status: String?,
    val note: String?,
    val savedAt: Long,
)

@Entity(tableName = "subscriptions", indices = [Index(value = ["keyword"], unique = true)])
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
    val enabled: Boolean = true,
)

@Entity(tableName = "search_history", indices = [Index(value = ["query"], unique = true)])
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val searchedAt: Long,
)

/** AI 三层阅读缓存：按 (arxiv_id, layer) 复合主键，重新生成即覆盖。 */
@Entity(tableName = "ai_readings", primaryKeys = ["arxivId", "layer"])
data class AiReadingEntity(
    val arxivId: String,
    val layer: String,
    val content: String,
    val generatedAt: Long,
    val model: String?,
)

/** 书架联表视图：论文 + 收藏元信息。 */
data class ShelfItem(
    @Embedded val paper: PaperEntity,
    val status: String?,
    val note: String?,
    val savedAt: Long,
)

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun authorsToJson(value: List<String>): String = json.encodeToString(value)

    @TypeConverter
    fun authorsFromJson(value: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
}

fun PaperEntity.toDomain(): Paper = Paper(
    arxivId = arxivId,
    title = title,
    authors = authors,
    abstract = abstractText,
    upvotes = upvotes,
    source = runCatching { PaperSource.valueOf(source) }.getOrDefault(PaperSource.SEARCH),
    sourceKeyword = sourceKeyword,
    publishedAt = publishedAt,
    fetchedAt = fetchedAt,
    paperUrl = paperUrl,
)

fun Paper.toEntity(now: Long = System.currentTimeMillis()): PaperEntity = PaperEntity(
    arxivId = arxivId,
    title = title,
    authors = authors,
    abstractText = abstract,
    upvotes = upvotes,
    source = source.name,
    sourceKeyword = sourceKeyword,
    publishedAt = publishedAt,
    fetchedAt = fetchedAt.takeIf { it > 0 } ?: now,
    paperUrl = paperUrl,
)

fun ShelfItem.toDomain(): Paper = paper.toDomain()
