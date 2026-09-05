package com.paperlens.app.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.paperlens.app.ui.nav.ScrollToHideController

/**
 * 监听 LazyList 滚动方向 → 驱动底栏隐藏/弹回（规格六 2）。
 * 用 firstVisibleItem 快照差值判断方向，足够轻量（无逐帧 nestedScroll 开销）。
 */
@Composable
fun rememberScrollToHide(
    listState: LazyListState,
    controller: ScrollToHideController,
    enabled: Boolean = true,
) {
    LaunchedEffect(listState, enabled) {
        if (!enabled) return@LaunchedEffect
        var lastIndex = 0
        var lastOffset = 0
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                val dy = if (index == lastIndex) (offset - lastOffset).toFloat()
                else if (index > lastIndex) 60f else -60f
                if (dy != 0f) controller.onScroll(dy * 3f)
                lastIndex = index
                lastOffset = offset
            }
    }
}
