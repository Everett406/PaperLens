package com.paperlens.app.di

import android.content.Context
import com.paperlens.app.ai.AiClient
import com.paperlens.app.data.db.AppDatabase
import com.paperlens.app.data.prefs.CacheStore
import com.paperlens.app.data.prefs.SettingsStore
import com.paperlens.app.data.remote.ArxivApi
import com.paperlens.app.data.remote.FeedMirrorClient
import com.paperlens.app.data.remote.NetDiag
import com.paperlens.app.data.repo.AiReadManager
import com.paperlens.app.data.repo.AiRepository
import com.paperlens.app.data.repo.CuratedRepository
import com.paperlens.app.data.repo.PaperRepository
import com.paperlens.app.data.repo.SearchRepository
import com.paperlens.app.data.repo.ShelfRepository
import com.paperlens.app.data.repo.SubscriptionRepository
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 手动依赖图（规格：不引入 DI 框架）。
 * 单例生命周期与 Application 一致；构造即完成组装，无反射、无注解处理器魔法。
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
    }

    // —— 两个上游、两种超时策略 ——
    // AI 流式输出可能很长：读超时放宽到 180s。
    private val aiOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // arXiv：国内一般可直连，但偶发慢；短超时保证批量订阅与搜索不被单个请求拖死。
    private val arxivOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://example.invalid/") // 每个接口用 @Url 或独立 builder；此处仅占位，实际基址见下方 api
        .build()

    private val arxivRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://export.arxiv.org/")
        .client(arxivOkHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val arxivApi: ArxivApi = arxivRetrofit.create(ArxivApi::class.java)

    /** 网络诊断：数据渠道每次失败的原因归类 + 落盘，版本页可查看/复制。 */
    val netDiag: NetDiag = NetDiag(java.io.File(context.filesDir, "diag"))

    /** GitHub 仓库镜像源：arXiv 直连失败时的「全部」流兜底（复用 arXiv 超时策略）。 */
    val feedMirrorClient: FeedMirrorClient = FeedMirrorClient(arxivOkHttpClient, json)

    val database: AppDatabase = AppDatabase.build(context)
    val settingsStore: SettingsStore = SettingsStore(context)
    val cacheStore: CacheStore = CacheStore(appContext)
    val aiClient: AiClient = AiClient(aiOkHttpClient, json)

    val paperRepository: PaperRepository by lazy {
        PaperRepository(arxivApi, feedMirrorClient, netDiag, database)
    }
    val shelfRepository: ShelfRepository by lazy { ShelfRepository(database) }
    val subscriptionRepository: SubscriptionRepository by lazy { SubscriptionRepository(database) }
    val searchRepository: SearchRepository by lazy { SearchRepository(database) }
    val aiRepository: AiRepository by lazy { AiRepository(aiClient, database, settingsStore, cacheStore) }

    /** AI 阅读后台队列：App 级生命周期，返回/切页不中断生成，全局悬浮指示器数据源。 */
    val aiReadManager: AiReadManager by lazy { AiReadManager(aiRepository) }

    /** AI 每日精选：书架收藏 Embedding 画像 × 当日论文匹配。 */
    val curatedRepository: CuratedRepository by lazy {
        CuratedRepository(aiClient, settingsStore, shelfRepository, paperRepository, cacheStore)
    }
}
