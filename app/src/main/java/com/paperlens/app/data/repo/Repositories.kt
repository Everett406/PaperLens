package com.paperlens.app.data.repo

import com.paperlens.app.data.db.AppDatabase
import com.paperlens.app.data.db.SearchHistoryEntity
import com.paperlens.app.data.db.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

/** 关键词订阅：增删 + 单条开关（「我的」页管理，今日页消费）。 */
class SubscriptionRepository(private val db: AppDatabase) {

    fun observeAll(): Flow<List<SubscriptionEntity>> = db.subscriptionDao().observeAll()

    /** 返回 false 表示关键词重复。 */
    suspend fun add(keyword: String): Boolean {
        val kw = keyword.trim()
        if (kw.isEmpty()) return false
        return db.subscriptionDao().add(SubscriptionEntity(keyword = kw)) != -1L
    }

    suspend fun delete(id: Long) = db.subscriptionDao().delete(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) =
        db.subscriptionDao().setEnabled(id, enabled)
}

/** 搜索历史：最近 10 条（Room 落库，规格第三节）。 */
class SearchRepository(private val db: AppDatabase) {

    fun observeRecent(): Flow<List<SearchHistoryEntity>> = db.searchHistoryDao().observeRecent()

    suspend fun record(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        db.searchHistoryDao().record(q, System.currentTimeMillis())
        db.searchHistoryDao().trimToTen()
    }

    suspend fun clear() = db.searchHistoryDao().clear()
}
