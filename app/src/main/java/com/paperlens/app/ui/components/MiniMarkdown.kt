package com.paperlens.app.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 */
@Composable
fun MiniMarkdown(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        text.lines().forEach { raw ->
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
                    }
                    MarkdownText(rest, 15.sp, colors.onSurface, Modifier.weight(1f))
                }

                else -> MarkdownText(line, 15.sp, colors.onSurface)
            }
        }
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
