package com.paperlens.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 亚克力（规格第五节）：全 App 只允许三处 —— 底部悬浮 Tab 胶囊、今日页顶栏、详情页吸顶操作栏。
 * 内容区 hazeSource，三处 hazeEffect：blur 20dp + surface 色 α0.72 tint（规格参数原样落地）。
 * 列表 item 层禁止出现 blur（保帧率），本文件也不提供任何 item 级 blur 入口。
 */

val LocalHazeState = staticCompositionLocalOf<HazeState> {
    error("LocalHazeState 未提供")
}

/** 记录全局唯一的 HazeState（内容源 = NavHost 内容区）。 */
@Composable
fun rememberAppHazeState(): HazeState = rememberHazeState()

/** 内容区：所有可滚动内容经过这里登记为模糊源。 */
fun Modifier.appHazeSource(state: HazeState): Modifier = this.hazeSource(state)

/** 亚克力样式：规格指定 blur≈20dp + surface α≈0.72；微噪点提升质感。 */
@Composable
fun acrylicStyle(): HazeStyle {
    val surface = MiuixTheme.colorScheme.surface
    return HazeStyle(
        backgroundColor = surface,
        tints = listOf(HazeTint(surface.copy(alpha = 0.72f))),
        blurRadius = 20.dp,
        noiseFactor = 0.04f,
    )
}

/** 亚克力容器：底栏/顶栏/详情吸顶栏统一入口。 */
@Composable
fun AcrylicSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = LocalHazeState.current
    Box(
        modifier = modifier.hazeEffect(
            state = state,
            style = acrylicStyle(),
        )
    ) {
        content()
    }
}
