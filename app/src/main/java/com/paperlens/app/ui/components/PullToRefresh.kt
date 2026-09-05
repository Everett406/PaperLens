package com.paperlens.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 自研下拉刷新（v1.5）。
 *
 * 为什么替换 Miuix PullToRefresh：反编译 miuix-ui 0.9.3 实锤，其
 * NestedScrollConnection.onPreScroll 在 Refreshing/RefreshComplete 状态下
 * 直接返回 available（吞掉全部滚动量），导致刷新期间整个列表被「定死」，
 * 无法边加载边滑动。自研版本的核心差异：**刷新期间不消费任何滚动**。
 *
 * 行为规格：
 * - 手势：列表在顶部继续下拉 → 阻尼增长偏移；上滑先收回偏移、再滚动列表；
 * - 松手：达到阈值 → 触发 onRefresh，指示器弹簧停在悬浮高度；否则弹回 0；
 * - 刷新中：列表可以随便滑（本组件不拦截）；刷新结束偏移弹回 0。
 */
@Composable
fun PaperPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 68.dp.toPx() }
    val restingOffset = with(density) { 52.dp.toPx() }
    val maxPull = with(density) { 150.dp.toPx() }

    val scope = rememberCoroutineScope()
    var offset by remember { mutableFloatStateOf(0f) }
    var animJob by remember { mutableStateOf<Job?>(null) }
    val triggered = remember { mutableStateOf(false) }

    val currentRefreshing by rememberUpdatedState(isRefreshing)
    val currentOnRefresh by rememberUpdatedState(onRefresh)

    fun stopAnim() {
        animJob?.cancel()
        animJob = null
    }

    fun animateTo(target: Float) {
        stopAnim()
        animJob = scope.launch {
            animate(
                initialValue = offset,
                targetValue = target,
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 360f),
            ) { value, _ -> offset = value }
            if (target <= 0f) offset = 0f
        }
    }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            animateTo(restingOffset)
        } else {
            triggered.value = false
            if (offset > 0.5f) animateTo(0f) else { stopAnim(); offset = 0f }
        }
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 上滑（y<0）且当前有下拉偏移：先收回偏移，剩余量交给列表
                if (available.y < 0f && offset > 0f) {
                    stopAnim()
                    val consumed = available.y.coerceAtLeast(-offset)
                    offset += consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // 列表已到顶仍在下拉 → 阻尼增长偏移（不刷新态才允许拉）
                if (available.y > 0f && !currentRefreshing) {
                    stopAnim()
                    val progress = (offset / maxPull).coerceIn(0f, 1f)
                    val damped = available.y * (0.55f * (1f - 0.75f * progress))
                    offset = (offset + damped).coerceAtMost(maxPull)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
        }
    }

    Box(
        modifier
            .nestedScroll(connection)
            .pointerInput(Unit) {
                // 监听抬手（无论是否产生 fling）：决定触发刷新还是弹回
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    if (offset >= threshold && !currentRefreshing && !triggered.value) {
                        triggered.value = true
                        currentOnRefresh()
                        animateTo(restingOffset)
                    } else if (offset > 0f && !currentRefreshing) {
                        triggered.value = false
                        animateTo(0f)
                    }
                }
            },
    ) {
        content()
        // —— 指示器层（不拦截任何指针事件） ——
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.TopCenter) {
            val heightDp = with(density) { offset.toDp() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(heightDp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (heightDp > 3.dp) {
                    RefreshSpinner(
                        progress = (offset / threshold).coerceIn(0f, 1f),
                        spinning = currentRefreshing,
                    )
                }
            }
        }
    }
}

/** 拉动阶段按进度画弧，刷新阶段换成旋转弧（两分支避免空闲时跑无限动画）。 */
@Composable
private fun RefreshSpinner(progress: Float, spinning: Boolean) {
    if (spinning) {
        val transition = rememberInfiniteTransition(label = "refreshSpin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "refreshAngle",
        )
        SpinnerCanvas(startAngle = angle - 90f, sweepAngle = 80f)
    } else {
        SpinnerCanvas(startAngle = -90f, sweepAngle = (300f * progress).coerceAtLeast(8f))
    }
}

@Composable
private fun SpinnerCanvas(startAngle: Float, sweepAngle: Float) {
    val colors = MiuixTheme.colorScheme
    Canvas(Modifier.size(26.dp)) {
        val stroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2 + 0.5.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = colors.onSurfaceVariantActions.copy(alpha = 0.18f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = colors.primary,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
    }
}
