package com.paperlens.app.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Hugging Face Daily Papers 接口。
 * GET {镜像或官方}/api/daily_papers?date=YYYY-MM-DD&limit=100
 * v1.1 起支持镜像站：完整 URL 由仓库层按用户设置拼出后经 @Url 传入
 * （默认 hf-mirror.com 国内可直连，失败自动回退 huggingface.co 官方）。
 * 返回数组的 paper 字段才是论文本体，外层 publishedAt 为收录时间（兜底用）。
 * DTO 全部可空 + lenient 解析：HF 字段偶有缺省，绝不让解析炸掉整个列表。
 */
@Serializable
data class HfAuthorDto(val name: String? = null)

@Serializable
data class HfPaperDto(
    val id: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val authors: List<HfAuthorDto>? = null,
    val upvotes: Int? = null,
    val publishedAt: String? = null,
    val paperUrl: String? = null,
)

@Serializable
data class HfEntryDto(
    val paper: HfPaperDto? = null,
    val publishedAt: String? = null,
)

interface HfApi {
    @GET
    suspend fun dailyPapers(
        @Url url: String,
        @Query("date") date: String,
        @Query("limit") limit: Int = 100,
    ): List<HfEntryDto>
}
