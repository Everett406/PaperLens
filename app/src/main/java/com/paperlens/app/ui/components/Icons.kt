package com.paperlens.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paperlens.app.R

/**
 * 极简图标方案：全部使用手写矢量 drawable + ColorFilter 着色。
 * 决策说明：刻意不引 material-icons 依赖（其更新已停滞且体积大），
 * 20 个常用图标自绘 24dp 描边路径，视觉统一为细线 HyperOS 风格。
 */
object UiIcons {
    val NavToday = R.drawable.ic_nav_today
    val NavShelf = R.drawable.ic_nav_shelf
    val NavMine = R.drawable.ic_nav_mine
    val Search = R.drawable.ic_search
    val BookmarkOutline = R.drawable.ic_bookmark_outline
    val BookmarkFilled = R.drawable.ic_bookmark_filled
    val Upvote = R.drawable.ic_upvote
    val Close = R.drawable.ic_close
    val Back = R.drawable.ic_back
    val External = R.drawable.ic_external
    val Trash = R.drawable.ic_trash
    val Plus = R.drawable.ic_plus
    val Refresh = R.drawable.ic_refresh
    val Sparkle = R.drawable.ic_sparkle
    val Moon = R.drawable.ic_moon
    val Sun = R.drawable.ic_sun
    val AutoMode = R.drawable.ic_auto_mode
    val Check = R.drawable.ic_check
    val Link = R.drawable.ic_link
    val Note = R.drawable.ic_note
    val Pause = R.drawable.ic_pause
}

@Composable
fun TintedIcon(
    resId: Int,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 20.dp,
) {
    Image(
        painter = painterResource(resId),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = if (tint == Color.Unspecified) null else ColorFilter.tint(tint),
    )
}
