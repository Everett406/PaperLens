package com.paperlens.app.data.repo

import com.paperlens.app.data.db.AppDatabase
import com.paperlens.app.data.db.ShelfDao
import com.paperlens.app.data.db.ShelfEntry
import com.paperlens.app.data.db.ShelfItem
import com.paperlens.app.data.db.toEntity
import com.paperlens.app.domain.Paper
import com.paperlens.app.domain.ShelfStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 书架仓：收藏/状态/一句话笔记。
 * 收藏动作保证 papers 表存在对应行（搜索结果在展示前已入缓存，这里再兜一次底），
 * 之后 shelf 行通过外键 RESTRICT 与 papers 绑定 —— 「清空缓存」不会误伤收藏。
 */
class ShelfRepository(private val db: AppDatabase) {

    private val shelfDao: ShelfDao = db.shelfDao()

    fun observeShelf(filter: ShelfStatus): Flow<List<ShelfItem>> =
        shelfDao.observeShelf(filter.dbValue)

    /** 「全部」chips：包含未分类。 */
    fun observeAllShelf(): Flow<List<ShelfItem>> = shelfDao.observeShelf(null)

    fun observeSavedIds(): Flow<Set<String>> =
        shelfDao.observeSavedIds().map { it.toSet() }

    fun observeSaved(arxivId: String): Flow<Boolean> = shelfDao.observeSaved(arxivId)

    suspend fun save(paper: Paper) {
        if (db.paperDao().paperOnce(paper.arxivId) == null) {
            db.paperDao().upsertAll(listOf(paper.toEntity()))
        }
        shelfDao.insert(
            ShelfEntry(
                arxivId = paper.arxivId,
                status = null,
                note = null,
                savedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun remove(arxivId: String) = shelfDao.remove(arxivId)

    suspend fun setStatus(arxivId: String, status: ShelfStatus) =
        shelfDao.setStatus(arxivId, status.dbValue)

    suspend fun setNote(arxivId: String, note: String?) =
        shelfDao.setNote(arxivId, note?.trim()?.takeIf { it.isNotEmpty() })
}
