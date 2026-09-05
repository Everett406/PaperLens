package com.paperlens.app.di

import android.content.Context
import com.paperlens.app.ai.OpenAiClient
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

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // AI 流式输出可能很长
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://example.invalid/") // 每个接口用 @Url 或独立 builder；此处仅占位，实际基址见下方两个 api
        .build()

    // —— 为两个上游各建独立 Retrofit（基址不同、共享 OkHttp 连接池） ——
    private val hfRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://huggingface.co/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private val arxivRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://export.arxiv.org/")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val hfApi: HfApi = hfRetrofit.create(HfApi::class.java)
    val arxivApi: ArxivApi = arxivRetrofit.create(ArxivApi::class.java)

    val database: AppDatabase = AppDatabase.build(context)
    val settingsStore: SettingsStore = SettingsStore(context)
    val openAiClient: OpenAiClient = OpenAiClient(okHttpClient, json)

    val paperRepository: PaperRepository by lazy {
        PaperRepository(hfApi, arxivApi, database)
    }
    val shelfRepository: ShelfRepository by lazy { ShelfRepository(database) }
    val subscriptionRepository: SubscriptionRepository by lazy { SubscriptionRepository(database) }
    val searchRepository: SearchRepository by lazy { SearchRepository(database) }
    val aiRepository: AiRepository by lazy { AiRepository(openAiClient, database, settingsStore) }
}
