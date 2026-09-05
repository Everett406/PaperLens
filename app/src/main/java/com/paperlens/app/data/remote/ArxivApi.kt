package com.paperlens.app.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * arXiv 官方 Atom API。
 * v1.3：返回类型从 String 改为 [ResponseBody] —— 此前挂着的 kotlinx-serialization
 * 转换器会把 String 返回值当 JSON 反序列化，Atom XML 必然解析失败（异常被吞掉后
 * 表现为「永远检索不到数据」）。ResponseBody 是 Retrofit 内建转换，不经任何 JSON 层，
 * 原始 XML 由 [com.paperlens.app.data.remote.ArxivAtomParser] 手写解析。
 * 基址 https://export.arxiv.org/ （https 官方支持且避免明文流量告警）。
 */
interface ArxivApi {
    @GET("api/query")
    suspend fun query(
        @Query("search_query") searchQuery: String,
        @Query("start") start: Int = 0,
        @Query("max_results") maxResults: Int = 50,
        @Query("sortBy") sortBy: String = "submittedDate",
        @Query("sortOrder") sortOrder: String = "descending",
    ): ResponseBody
}
