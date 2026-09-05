package com.paperlens.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 弹簧分段切换（规格第六节 3：指示器弹簧滑动）。
 *
 * 决策说明：Miuix TabRow 的指示器内部用 tween(200) 补间（源码验证），不符合规格
 * 「全局弹簧物理、禁止 duration 补间」的硬性要求，因此分段控件自绘：
 * Animatable + spring(dampingRatio = 0.75f) 驱动胶囊指示器位移。
 */
@Composable
fun SpringTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 38.dp,
) {
    val colors = MiuixTheme.colorScheme
    val track = colors.secondaryContainer
    val thumb = colors.surfaceContainer
    val density = LocalDensity.current

    val indicator = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        indicator.animateTo(
            selected.toFloat(),
            spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .background(track, SuperellipseShape(height / 2))
    ) {
        val itemWidth = maxWidth / tabs.size
        val itemWidthPx = with(density) { itemWidth.toPx() }
        val thumbX = with(density) { (indicator.value * itemWidthPx).toDp() } + 3.dp

        // 弹簧指示器（胶囊）
        Box(
            modifier = Modifier
                .offset(x = thumbX)
                .width(itemWidth - 6.dp)
                .fillMaxHeight()
                .padding(3.dp)
                .shadow(3.dp, SuperellipseShape(height))
                .background(thumb, SuperellipseShape(height))
        )

        Row(Modifier.height(height)) {
            tabs.forEachIndexed { index, label ->
                val isSelected = index == selected
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) colors.onSurface else colors.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
