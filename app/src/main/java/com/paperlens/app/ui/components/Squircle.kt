package com.paperlens.app.ui.components

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

        val w = size.width
        val h = size.height
        val path = Path()
        val steps = 24
        val n2 = 2f / exponent
        val halfPi = (Math.PI / 2)

        /** sin^(2/n)：0 → 1，先快后慢 */
        fun easeIn(theta: Double): Float = sin(theta).toFloat().pow(n2)
        /** cos^(2/n)：1 → 0，先慢后快 */
        fun easeOut(theta: Double): Float = cos(theta).toFloat().pow(n2)

        // 顶边
        path.moveTo(r, 0f)
        path.lineTo(w - r, 0f)
        // 右上角：圆心 (w-r, r)，从切点 (w-r, 0) 扫到切点 (w, r)，弧向外凸
        for (i in 1..steps) {
            val t = i * halfPi / steps
            path.lineTo(w - r + r * easeIn(t), r - r * easeOut(t))
        }
        // 右边
        path.lineTo(w, h - r)
        // 右下角：圆心 (w-r, h-r)，从 (w, h-r) 扫到 (w-r, h)
        for (i in 1..steps) {
            val t = i * halfPi / steps
            path.lineTo(w - r + r * easeOut(t), h - r + r * easeIn(t))
        }
        // 底边
        path.lineTo(r, h)
        // 左下角：圆心 (r, h-r)，从 (r, h) 扫到 (0, h-r)
        for (i in 1..steps) {
            val t = i * halfPi / steps
            path.lineTo(r - r * easeIn(t), h - r + r * easeOut(t))
        }
        // 左边
        path.lineTo(0f, r)
        // 左上角：圆心 (r, r)，从 (0, r) 扫到 (r, 0)
        for (i in 1..steps) {
            val t = i * halfPi / steps
            path.lineTo(r - r * easeOut(t), r - r * easeIn(t))
        }
        path.close()
        // 路径严格凸（超椭圆圆角矩形），RenderNode 可用凸路径做抗锯齿裁剪
        return Outline.Generic(path)
    }
}
