package com.paperlens.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** 圆角档位（规格第五节）：大卡片 24dp，小组件 16dp。 */
object Corners {
    val large: Dp = 24.dp
    val medium: Dp = 16.dp
    val small: Dp = 12.dp
    val pill: Dp = 100.dp
}

/**
 * 真超椭圆 Shape（|x/r|^n + |y/r|^n = 1，n≈4.2）：
 * 用于必须传 Shape 的场合（SharedTransition 的 sharedBounds、Dialog 容器）。
 *
 * 决策说明：miuix-squircle 模块只提供 Modifier（squircleSurface/squircleClip 等），
 * 未暴露 Shape 对象（源码验证）；静态容器统一用其 Modifier 获得 GPU 友好的 SDF 超椭圆，
 * 需要 Shape 的少数场景用这里的解析超椭圆兜底，二者视觉基本一致。
 */
class SuperellipseShape(
    private val cornerRadius: Dp,
    /** 超椭圆指数：4 ≈ iOS 圆角连续性；2 退化为普通圆角 */
    private val exponent: Float = 4.2f,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { cornerRadius.toPx() }
            .coerceAtMost(min(size.width, size.height) / 2f)
        if (r <= 0f) return Outline.Rectangle(size.let { androidx.compose.ui.geometry.Rect(0f, 0f, it.width, it.height) })

        val path = Path()
        val steps = 16
        val n2 = 2f / exponent

        fun cornerPoint(cx: Float, cy: Float, sx: Float, sy: Float, t: Float): Offset {
            // t ∈ [0,1] 扫过该象限
            val theta = (t / steps) * (Math.PI / 2)
            val c = cos(theta).toFloat()
            val s = sin(theta).toFloat()
            val x = r * sign(c) * c.abs().pow(n2)
            val y = r * sign(s) * s.abs().pow(n2)
            return Offset(cx + sx * x, cy + sy * y)
        }

        // 顺时针：右上 → 右下 → 左下 → 左上
        path.moveTo(r, 0f)
        path.lineTo(size.width - r, 0f)
        repeat(steps + 1) { t ->
            val p = cornerPoint(size.width - r, r, 1f, 1f, t.toFloat())
            path.lineTo(p.x, p.y)
        }
        path.lineTo(size.width, size.height - r)
        repeat(steps + 1) { t ->
            val p = cornerPoint(size.width - r, size.height - r, 1f, -1f, t.toFloat())
            path.lineTo(p.x, p.y)
        }
        path.lineTo(r, size.height)
        repeat(steps + 1) { t ->
            val p = cornerPoint(r, size.height - r, -1f, -1f, t.toFloat())
            path.lineTo(p.x, p.y)
        }
        path.lineTo(0f, r)
        repeat(steps + 1) { t ->
            val p = cornerPoint(r, r, -1f, 1f, t.toFloat())
            path.lineTo(p.x, p.y)
        }
        path.close()
        return Outline.Generic(path)
    }

    private fun Float.abs(): Float = kotlin.math.abs(this)
}
