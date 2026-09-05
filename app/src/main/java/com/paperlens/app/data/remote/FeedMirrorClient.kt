package com.paperlens.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GitHub 仓库镜像源（v1.4）：拉取由 Feed Mirror 工作流定时生成的 feed/all.json。
 *
 * 为什么需要它：export.arxiv.org 是康奈尔自托管（无 CDN），国内访问时通时断；
 * 而本项目的用户网络到 GitHub 已被证实可达（APK 就是从 GitHub Releases 下载的）。
 * arXiv 直连失败时降级到这里，保证「今日 · 全部」默认屏总有内容。
 *
 * 通道：GET api.github.com/.../contents/feed/all.json + Accept: application/vnd.github.raw
 * —— contents API 对 raw 请求直接返回文件原文（无需处理 base64），文件 ~180KB，
 * 远低于 raw 模式 1MB 上限。未认证限额 60 次/小时/IP，配合 App 端 1h TTL 绰绰有余。
 */
class FeedMirrorClient(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {

    @Serializable
    data class MirrorPaper(
        val arxivId: String,
        val title: String = "",
        val authors: List<String> = emptyList(),
        val abstract: String = "",
        val publishedAt: Long = 0,
        val paperUrl: String? = null,
    )

    @Serializable
    data class MirrorFeed(
        val generatedAt: Long = 0,
        val papers: List<MirrorPaper> = emptyList(),
    )

    /** 失败（网络/HTTP/解析）一律抛异常，由调用方记入 NetDiag。 */
    suspend fun fetchAllFeed(): MirrorFeed = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(MIRROR_URL)
            .header("Accept", "application/vnd.github.raw")
            .header("User-Agent", "PaperLens/1.4")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("mirror HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw java.io.IOException("mirror 空响应")
            json.decodeFromString(MirrorFeed.serializer(), body)
        }
    }

    private companion object {
        const val MIRROR_URL =
            "https://api.github.com/repos/Everett406/PaperLens/contents/feed/all.json"
    }
}
