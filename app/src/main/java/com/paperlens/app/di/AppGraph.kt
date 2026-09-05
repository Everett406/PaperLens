package com.paperlens.app.di

import android.content.Context
import com.paperlens.app.ai.AiClient
import com.paperlens.app.data.db.AppDatabase
import com.paperlens.app.data.prefs.SettingsStore
import com.paperlens.app.data.remote.ArxivApi
import com.paperlens.app.data.remote.HfApi
import com.paperlens.app.data.repo.AiRepository
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

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
    }

    // —— 三个上游、三种超时策略（共享连接池） ——
    // AI 流式输出可能很长：读超时放宽到 180s。
    private val aiOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // HF：不跟随重定向。hf-mirror 等镜像对 API 路径返回 308 → 官方站，
    // 跟随只会在国内网络里被拖到超时；关闭后 308 直接抛 HttpException 快速失败，
    // 由仓库层落到链上的下一站（官方/昨天）。
    private val hfOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    // arXiv：国内一般可直连，但偶发慢；短超时保证批量订阅与搜索不被单个请求拖死。
    private val arxivOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://example.invalid/") // 每个接口用 @Url 或独立 builder；此处仅占位，实际基址见下方两个 api
        .build()

    // —— 为两个上游各建独立 Retrofit（基址不同，各用各的 OkHttp） ——
    private val hfRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://huggingface.co/")
        .client(hfOkHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private val arxivRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://export.arxiv.org/")
        .client(arxivOkHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val hfApi: HfApi = hfRetrofit.create(HfApi::class.java)
    val arxivApi: ArxivApi = arxivRetrofit.create(ArxivApi::class.java)

    val database: AppDatabase = AppDatabase.build(context)
    val settingsStore: SettingsStore = SettingsStore(context)
    val aiClient: AiClient = AiClient(aiOkHttpClient, json)

    val paperRepository: PaperRepository by lazy {
        PaperRepository(hfApi, arxivApi, database, settingsStore)
    }
    val shelfRepository: ShelfRepository by lazy { ShelfRepository(database) }
    val subscriptionRepository: SubscriptionRepository by lazy { SubscriptionRepository(database) }
    val searchRepository: SearchRepository by lazy { SearchRepository(database) }
    val aiRepository: AiRepository by lazy { AiRepository(aiClient, database, settingsStore) }
}
