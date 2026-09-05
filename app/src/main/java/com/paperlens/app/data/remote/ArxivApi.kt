package com.paperlens.app.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * arXiv 官方 Atom API。
 * 返回原始 XML，由 [ArxivAtomParser] 用 XmlPullParser 手写解析（规格要求：不引重型 XML 库）。
 * 基址 https://export.arxiv.org/ （规格给的是 http，https 官方支持且避免明文流量告警）。
 */
interface ArxivApi {
    @GET("api/query")
    suspend fun query(
        @Query("search_query") searchQuery: String,
        @Query("start") start: Int = 0,
        @Query("max_results") maxResults: Int = 50,
        @Query("sortBy") sortBy: String = "submittedDate",
        @Query("sortOrder") sortOrder: String = "descending",
    ): String
}
