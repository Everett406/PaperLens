package com.paperlens.app

import android.app.Application
import android.os.Build
import android.util.Log
import com.paperlens.app.di.AppGraph
import java.io.File
import java.time.Instant

class PaperLensApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashCapture()
        graph = AppGraph(this)
    }

    /**
     * 崩溃捕获：任何未捕获异常先把完整堆栈写到 filesDir/crash/last_crash.txt，
     * 再交还系统默认处理器（照常闪退）。
     * 这样「真机闪退」不再是黑盒 —— 版本页会展示最近一次崩溃的堆栈，可一键复制反馈。
     */
    private fun installCrashCapture() {
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val dir = File(filesDir, "crash").apply { mkdirs() }
                File(dir, "last_crash.txt").writeText(
                    buildString {
                        appendLine("time: ${Instant.now()}")
                        appendLine("thread: ${thread.name}")
                        val pkg = packageManager.getPackageInfo(packageName, 0)
                        appendLine("version: ${pkg.versionName} (${pkg.longVersionCode})")
                        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        appendLine()
                        appendLine(Log.getStackTraceString(error))
                        // 闪退根因常在启动链上：带上上一条崩溃的堆栈头，方便对比是否同一问题
                    },
                )
            }
            systemHandler?.uncaughtException(thread, error)
        }
    }
}
