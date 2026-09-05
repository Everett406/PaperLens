package com.paperlens.app.data.remote

import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLException

/**
 * 网络诊断（v1.4）：把每次数据渠道失败的原因归类、留痕、可导出。
 *
 * 背景：真机上「今日页没数据」长期是黑盒 —— 沙盒实测 arXiv 全通，但用户设备到
 * export.arxiv.org（康奈尔自托管、无 CDN）的路径未知。有了这份日志，
 * 用户在版本页一键复制，反馈里就能看到是 DNS / 超时 / TLS / HTTP 哪一层的问题。
 *
 * 实现：
 * - 内存环形缓冲最近 20 条 + 追加写 filesDir/diag/net_log.txt（synchronized 防并发写坏）；
 * - [reason] 把异常翻译成一句话中文原因，同时用于空态文案与日志；
 * - 启动时回读文件尾部，App 重启后诊断卡不丢历史。
 */
class NetDiag(private val dir: File) {

    private val file: File get() = File(dir, "net_log.txt")
    private val buffer = ArrayDeque<String>()
    private var loaded = false

    /** 最近一条失败的一句话原因（供搜索页等直接引用，null = 尚无失败记录）。 */
    @Volatile
    var lastReason: String? = null
        private set

    fun record(tag: String, host: String, error: Throwable) {
        record(tag, host, reason(error))
    }

    fun record(tag: String, host: String, detail: String) {
        synchronized(this) {
            if (!loaded) {
                loadTailLocked()
                loaded = true
            }
            val line = "${Instant.now()} | $tag | $host | $detail"
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            lastReason = detail
            runCatching {
                dir.mkdirs()
                file.appendText(line + "\n")
            }
        }
    }

    /** 诊断卡展示用：最新在前。 */
    fun snapshot(): List<String> = synchronized(this) {
        if (!loaded) {
            loadTailLocked()
            loaded = true
        }
        buffer.toList().asReversed()
    }

    /** 把异常归类成一句可读的中文原因。 */
    fun reason(e: Throwable): String {
        val cause = generateSequence(e as Throwable?) { it.cause }.lastOrNull { it != null } ?: e
        return when (cause) {
            is UnknownHostException ->
                "DNS 解析失败（域名无法解析：可能是无网络、DNS 被污染或域名被屏蔽）"
            is SocketTimeoutException -> "连接/读取超时（网络到论文服务器太慢或不通）"
            is ConnectException -> "连接被拒绝（服务器不可达或端口被拦截）"
            is SSLException -> "TLS 握手失败（网络链路对加密连接有干扰）"
            is java.net.HttpRetryException -> "请求被重定向或重试失败"
            is IOException ->
                if (cause.message?.contains("timeout", ignoreCase = true) == true) "连接/读取超时"
                else "网络 I/O 失败（${cause.message?.take(80) ?: "连接中断"}）"
            is retrofit2.HttpException -> "服务器返回 HTTP ${cause.code()}"
            is kotlinx.serialization.SerializationException -> "响应数据解析失败（返回内容异常）"
            is android.database.sqlite.SQLiteException ->
                "本地数据库写入异常（${cause.message?.take(80)}）"
            is IllegalStateException -> "响应状态异常（${cause.message?.take(80)}）"
            else -> "${cause.javaClass.simpleName}: ${cause.message?.take(80) ?: "未知错误"}"
        }
    }

    private fun loadTailLocked() {
        runCatching {
            if (file.exists()) {
                file.readLines().takeLast(MAX_LINES).forEach { buffer.addLast(it) }
                while (buffer.size > MAX_LINES) buffer.removeFirst()
                buffer.lastOrNull()?.let { last ->
                    lastReason = last.substringAfterLast("|").trim().ifEmpty { null }
                }
            }
        }
    }

    private companion object {
        const val MAX_LINES = 20
    }
}
