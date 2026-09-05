package com.paperlens.app.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** 路由表：单 Activity + Navigation Compose。 */
object Routes {
    const val TODAY = "today"
    const val SHELF = "shelf"
    const val MINE = "mine"
    const val SEARCH = "search"
    const val VERSION = "version"
    const val AI_SETTINGS = "ai_settings"

    /** origin：哪个列表打开的详情（all/sub/search/shelf），用于唯一化 sharedBounds 键。 */
    const val DETAIL = "detail/{arxivId}?origin={origin}"

    fun detail(arxivId: String, origin: String = "all") = "detail/$arxivId?origin=$origin"

    /** encoded: arxivId 形如 2401.12345，无特殊字符，直接拼即可 */
    val TOP_TABS = listOf(TODAY, SHELF, MINE)
}

/**
 * 底栏滚动隐藏控制器（规格六 2）：下滑隐藏、上滑弹回，位移用弹簧驱动
 * （动画本体在 AppRoot 的 AnimatedVisibility 上，这里只负责累计手势方向）。
 * v1.5 修复接线：AppRoot 提供唯一实例，各列表页经 CompositionLocal 驱动
 * （此前各页自建实例，无人观察，底栏隐藏一直是死代码）。
 */
val LocalBottomBarHideController = staticCompositionLocalOf<ScrollToHideController?> { null }

class ScrollToHideController {

    var visible by mutableStateOf(true)
        private set

    private var accumulated = 0f
    private var lastDelta by mutableFloatStateOf(0f)

    fun onScroll(dy: Float) {
        if (dy == lastDelta) return
        lastDelta = dy
        if (dy > 4f) {
            accumulated += dy
            if (accumulated > 90f && visible) {
                visible = false
                accumulated = 0f
            }
        } else if (dy < -4f) {
            if (!visible) visible = true
            accumulated = 0f
        }
    }

    fun reset() {
        visible = true
        accumulated = 0f
        lastDelta = 0f
    }
}
