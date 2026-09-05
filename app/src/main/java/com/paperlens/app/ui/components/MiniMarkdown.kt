package com.paperlens.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 迷你 Markdown 渲染器（AI 输出用）。
 * 决策说明：规格未要求 markdown，但 LLM 输出几乎必带 **加粗/列表/小标题**，
 * 引入完整 markdown 库过重，这里手写覆盖 AI 三层提示词约定的子集：
 * #/##/### 标题、短横线与星号列表、1. 有序列表、双星号加粗、反引号行内代码。
 *
 * v1.5：抽出 [MarkdownLine] 行级渲染器供 [StreamingMarkdown] 复用 ——
 * 生成过程中直接渲染 markdown（旧版流式渲染裸文本、完成后突然切换格式，观感割裂）。
 */
@Composable
fun MiniMarkdown(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        text.lines().forEach { MarkdownLine(it) }
    }
}

/**
 * 流式渲染：逐行淡入 + 行尾细光标呼吸（替代旧版整段裸文本 + 大光标块）。
 * 每行首次出现时 alpha 0→1 弹入；已有行内容增长不重播动画。
 */
@Composable
fun StreamingMarkdown(
    text: String,
    modifier: Modifier = Modifier,
) {
    val lines = remember(text) { text.split('\n') }
    Column(modifier = modifier.fillMaxWidth()) {
        lines.forEachIndexed { index, raw ->
            key(index) {
                val alpha = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    alpha.animateTo(1f, tween(240))
                }
                Box(Modifier.graphicsLayer { this.alpha = alpha.value }) {
                    MarkdownLine(raw)
                }
            }
        }
        val transition = rememberInfiniteTransition(label = "streamCaret")
        val pulse by transition.animateFloat(
            initialValue = 0.15f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(620, easing = LinearEasing), RepeatMode.Reverse),
            label = "streamCaretAlpha",
        )
        Caret(pulse)
    }
}

@Composable
private fun Caret(alpha: Float) {
    val colors = MiuixTheme.colorScheme
    Box(Modifier.padding(top = 3.dp)) {
        Box(
            Modifier
                .graphicsLayer { this.alpha = alpha }
                .size(width = 2.6.dp, height = 15.dp)
                .background(colors.primary, RoundedCornerShape(2.dp)),
        )
    }
}

/** 单行渲染（MiniMarkdown / StreamingMarkdown 共用）。 */
@Composable
fun MarkdownLine(raw: String) {
    val colors = MiuixTheme.colorScheme
    val line = raw.trimEnd()
    when {
        line.isBlank() -> Spacer(Modifier.height(10.dp))
        line.startsWith("### ") -> SectionTitle(line.removePrefix("### "), 14.5.sp)
        line.startsWith("## ") -> SectionTitle(line.removePrefix("## "), 15.5.sp)
        line.startsWith("# ") -> SectionTitle(line.removePrefix("# "), 15.5.sp)
        line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") ->
            BulletRow(colors.onSurface) {
                MarkdownText(
                    line.substring(2).trim(),
                    15.sp,
                    colors.onSurface,
                )
            }

        Regex("^\\d+([.、)．])\\s*").containsMatchIn(line) -> {
            val num = line.takeWhile { it.isDigit() }
            val rest = line.dropWhile { it.isDigit() }.dropWhile { it in ".、)． " }
            BulletRow(colors.primary) {
                Text(
                    text = num,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                )
                Spacer(Modifier.width(2.dp))
                MarkdownText(rest, 15.sp, colors.onSurface, Modifier.weight(1f))
            }
        }

        else -> MarkdownText(line, 15.sp, colors.onSurface)
    }
}

@Composable
private fun SectionTitle(text: String, fontSize: androidx.compose.ui.unit.TextUnit) {
    val colors = MiuixTheme.colorScheme
    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurface,
        modifier = Modifier.padding(top = 4.dp),
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun BulletRow(dotColor: Color, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.padding(vertical = 2.dp)) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(5.dp)
                .background(dotColor.copy(alpha = 0.75f), RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(9.dp))
        content()
    }
}

/** 行内渲染：双星号加粗 与 反引号行内代码。 */
@Composable
fun MarkdownText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    lineHeightSp: androidx.compose.ui.unit.TextUnit = 26.sp,
) {
    val colors = MiuixTheme.colorScheme
    val annotated = rememberInlineStyled(text, color, colors.primary)
    BasicText(
        text = annotated,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(
            fontSize = fontSize,
            lineHeight = lineHeightSp,
            color = color,
        ),
    )
}

@Composable
private fun rememberInlineStyled(text: String, baseColor: Color, accent: Color): AnnotatedString =
    androidx.compose.runtime.remember(text, baseColor, accent) {
        buildAnnotatedString {
            val codeRegex = Regex("`([^`]+)`")
            val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
            val tokens = mutableListOf<Triple<IntRange, Boolean, String?>>() // range, isBold, codeContent
            boldRegex.findAll(text).forEach { tokens.add(Triple(it.range, true, it.groupValues[1])) }
            // 将 `code` 也作为 token，但先剔除已被 ** 占据的区间简化处理（AI 输出中两者极少重叠）
            codeRegex.findAll(text).forEach { m ->
                val overlaps = tokens.any { it.first.overlaps(m.range) }
                if (!overlaps) tokens.add(Triple(m.range, false, m.groupValues[1]))
            }
            tokens.sortBy { it.first.first }
            if (tokens.isEmpty()) {
                append(text)
            } else {
                var cursor = 0
                tokens.forEach { (range, isBold, content) ->
                    if (range.first > cursor) append(text.substring(cursor, range.first))
                    if (isBold) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = baseColor))
                        append(content)
                        pop()
                    } else {
                        pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = accent))
                        append(content)
                        pop()
                    }
                    cursor = range.last + 1
                }
                if (cursor < text.length) append(text.substring(cursor))
            }
        }
    }

private fun IntRange.overlaps(other: IntRange): Boolean =
    first <= other.last && other.first <= last
