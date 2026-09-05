package com.paperlens.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.paperlens.app.data.prefs.ThemeMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 全局主题：MiuixTheme + ThemeController（规格：动态取色 seed color 用 Miuix ThemeController）。
 * - 深浅色跟随/亮/暗由设置驱动，映射到 ThemeController 的 MonetSystem/MonetLight/MonetDark；
 * - 用户选定的种子色经 Material 色彩规格（TonalSpot）生成整套 HyperOS 风格调色板；
 * - ThemeController 的构造参数是 mutableState 委托但对外只读（源码验证），
 *   因此设置变化时以 remember(mode, seed) 重建控制器实例。
 */
@Composable
fun PaperLensTheme(
    themeMode: ThemeMode,
    seedColor: Int,
    content: @Composable () -> Unit,
) {
    val mode = when (themeMode) {
        ThemeMode.SYSTEM -> ColorSchemeMode.MonetSystem
        ThemeMode.LIGHT -> ColorSchemeMode.MonetLight
        ThemeMode.DARK -> ColorSchemeMode.MonetDark
    }
    val controller = remember(mode, seedColor) {
        ThemeController(
            colorSchemeMode = mode,
            keyColor = Color(seedColor),
            paletteStyle = ThemePaletteStyle.TonalSpot,
            colorSpec = ThemeColorSpec.Spec2021,
        )
    }
    MiuixTheme(controller = controller, content = content)
}
