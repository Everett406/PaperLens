package com.paperlens.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 轻量旋转弧指示器（排队/生成/翻译等行内等待态用）。 */
@Composable
fun MiniSpinner(
    size: Dp = 15.dp,
    strokeWidth: Dp = 2.dp,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "miniSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(850, easing = LinearEasing)),
        label = "miniSpinAngle",
    )
    Canvas(modifier.size(size)) {
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2 + 0.4.dp.toPx()
        val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        drawArc(
            color = colors.primary,
            startAngle = angle - 90f,
            sweepAngle = 86f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
    }
}
