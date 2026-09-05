package com.paperlens.app.data.repo

import com.paperlens.app.domain.AiLayer
import com.paperlens.app.domain.Paper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AI 阅读后台队列（v1.5）：
 * - 生成协程挂在 App 级 scope（不再挂在 DetailViewModel）——**返回退出详情页不再中断生成**；
 * - 同一时刻只跑一个任务，其余排队（FIFO），完成一个自动开跑下一个；
 * - 任务状态/流式文本/思考文本都在这里集中管理，详情页与全局悬浮指示器共同订阅；
 * - DONE 任务保留 6s（悬浮窗提示「点按查看」），FAILED 保留 15s（来得及看错误），
 *   之后自动隐藏；详情页此时回退渲染 Room 缓存的正式内容。
 */
class AiReadManager(private val aiRepository: AiRepository) {

    enum class Status { QUEUED, RUNNING, DONE, FAILED }

    data class AiJob(
        val key: String,
        val arxivId: String,
        val title: String,
        val layer: AiLayer,
        val status: Status,
        val text: String = "",
        val thinking: String = "",
        val thinkingSeconds: Int = 0,
        val error: String? = null,
        val hidden: Boolean = false,
    )

    /** 全局悬浮指示器的最小状态（不随逐字流变化，避免整页重组）。 */
    data class PillState(
        val arxivId: String,
        val title: String,
        val layer: AiLayer,
        val status: Status,
        val queuedCount: Int,
    )

    private data class Spec(val paper: Paper, val layer: AiLayer) {
        val key: String get() = "${paper.arxivId}|${layer.name}"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel = Channel<Spec>(Channel.UNLIMITED)
    private val startedAt = mutableMapOf<String, Long>()

    private val _jobs = MutableStateFlow<List<AiJob>>(emptyList())
    val jobs: StateFlow<List<AiJob>> = _jobs

    val pill: StateFlow<PillState?> = _jobs
        .map { list ->
            val running = list.firstOrNull { it.status == Status.RUNNING }
            val queued = list.filter { it.status == Status.QUEUED }
            when {
                running != null -> PillState(running.arxivId, running.title, running.layer, Status.RUNNING, queued.size)
                queued.isNotEmpty() -> PillState(queued[0].arxivId, queued[0].title, queued[0].layer, Status.QUEUED, queued.size - 1)
                else -> list.firstOrNull { (it.status == Status.DONE || it.status == Status.FAILED) && !it.hidden }
                    ?.let { PillState(it.arxivId, it.title, it.layer, it.status, 0) }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // 单消费者：channel 天然 FIFO，保证「排队」语义
        scope.launch {
            for (spec in channel) runJob(spec)
        }
    }

    /**
     * 入队一个生成任务。若同 (论文, 层) 已在排队/运行中则忽略（返回 false）。
     * 已有缓存再入队 = 重新生成，完成时覆盖 Room 缓存。
     */
    fun enqueue(paper: Paper, layer: AiLayer): Boolean {
        val spec = Spec(paper, layer)
        val active = _jobs.value.any { it.key == spec.key && (it.status == Status.QUEUED || it.status == Status.RUNNING) }
        if (active) return false
        _jobs.update { list ->
            list.filterNot { it.key == spec.key } +
                AiJob(
                    key = spec.key,
                    arxivId = paper.arxivId,
                    title = paper.title,
                    layer = layer,
                    status = Status.QUEUED,
                )
        }
        channel.trySend(spec)
        return true
    }

    fun statusOf(arxivId: String, layer: AiLayer): Status? =
        _jobs.value.firstOrNull { it.arxivId == arxivId && it.layer == layer }
            ?.takeIf { !it.hidden }?.status

    fun hide(key: String) {
        _jobs.update { list -> list.map { if (it.key == key) it.copy(hidden = true) else it } }
    }

    private suspend fun runJob(spec: Spec) {
        update(spec.key) { it.copy(status = Status.RUNNING, text = "", thinking = "", thinkingSeconds = 0, error = null) }
        startedAt[spec.key] = System.currentTimeMillis()
        var firstContentAt = 0L

        val result = aiRepository.generate(
            paper = spec.paper,
            layer = spec.layer,
            onDelta = { delta ->
                if (firstContentAt == 0L) firstContentAt = System.currentTimeMillis()
                tickThinking(spec.key, firstContentAt)
                update(spec.key) { it.copy(text = it.text + delta) }
            },
            onThinking = { delta ->
                tickThinking(spec.key, firstContentAt)
                update(spec.key) { it.copy(thinking = it.thinking + delta) }
            },
        )

        startedAt.remove(spec.key)
        result.onSuccess {
            update(spec.key) { it.copy(status = Status.DONE) }
            scheduleHide(spec.key, DONE_VISIBLE_MS)
        }.onFailure { e ->
            update(spec.key) { it.copy(status = Status.FAILED, error = e.message ?: "生成失败") }
            scheduleHide(spec.key, FAILED_VISIBLE_MS)
        }
    }

    /** 思考计时：收到首个正文增量后冻结；思考期间每秒走表。 */
    private fun tickThinking(key: String, firstContentAt: Long) {
        val start = startedAt[key] ?: return
        val anchor = if (firstContentAt > 0L) firstContentAt else System.currentTimeMillis()
        val seconds = ((anchor - start) / 1000L).toInt().coerceAtLeast(0)
        update(key) { if (it.thinkingSeconds != seconds) it.copy(thinkingSeconds = seconds) else it }
    }

    private fun scheduleHide(key: String, delayMs: Long) {
        scope.launch {
            delay(delayMs)
            hide(key)
            prune()
        }
    }

    private fun prune() {
        _jobs.update { list ->
            if (list.size <= MAX_JOBS) list else list.drop(list.size - MAX_JOBS)
        }
    }

    private fun update(key: String, transform: (AiJob) -> AiJob) {
        _jobs.update { list -> list.map { if (it.key == key) transform(it) else it } }
    }

    private companion object {
        const val DONE_VISIBLE_MS = 6_000L
        const val FAILED_VISIBLE_MS = 15_000L
        const val MAX_JOBS = 8
    }
}
