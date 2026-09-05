package com.paperlens.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperlens.app.domain.Paper
import com.paperlens.app.domain.PaperSource
import com.paperlens.app.util.TimeFormat
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 论文卡片（列表通用）：Miuix 卡片质感 + 超椭圆 24dp + 按压 0.96 弹回 +
 * SharedTransition sharedBounds（卡片容器 morph 成详情头部）+ 收藏图标弹性缩放微旋转。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PaperCard(
    paper: Paper,
    saved: Boolean,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    statusLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null,
    sharedKey: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 规格六 4：卡片按压 scale 0.96 弹回（spring，无补间）
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "cardPressScale",
    )

    val colors = MiuixTheme.colorScheme

    val cardModifier = modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .squircleSurface(colors.surfaceContainer, Corners.large)
        .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
        .let { base ->
            if (onLongClick != null) {
                // 长按手势独立挂载（书架操作面板入口）；与 clickable 的按压反馈共存
                base.pointerInput(onLongClick) {
                    detectTapGestures(
                        onLongPress = { onLongClick() },
                    )
                }
            } else base
        }

    val content: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = paper.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 23.sp,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = paper.authors.joinToString(" · ").ifBlank { paper.shortId },
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SourceBadge(paper)
                        Text(
                            text = TimeFormat.friendly(paper.publishedAt),
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariantSummary,
                        )
                        if (paper.upvotes > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TintedIcon(UiIcons.Upvote, size = 11.dp, tint = colors.onSurfaceVariantSummary)
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = paper.upvotes.toString(),
                                    fontSize = 11.sp,
                                    color = colors.onSurfaceVariantSummary,
                                )
                            }
                        }
                        if (saved) {
                            SavedPill()
                        }
                        if (statusLabel != null) {
                            Row(
                                modifier = Modifier
                                    .background(colors.primaryContainer.copy(alpha = 0.18f), SuperellipseShape(100.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = statusLabel,
                                    fontSize = 10.5.sp,
                                    color = colors.primary,
                                )
                            }
                        }
                    }
                    if (note != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            TintedIcon(UiIcons.Note, size = 13.dp, tint = colors.onSurfaceVariantSummary)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = note,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = colors.onSurfaceVariantSummary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                SaveButton(saved = saved, onClick = onToggleSave)
            }
        }
    }

    if (sharedScope != null && animScope != null && sharedKey != null) {
        with(sharedScope) {
            Box(
                modifier = cardModifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(key = sharedKey),
                    animatedVisibilityScope = animScope,
                    enter = fadeIn(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)),
                    exit = fadeOut(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)),
                    boundsTransform = { _, _ ->
                        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
                    },
                )
            ) { content() }
        }
    } else {
        Box(cardModifier) { content() }
    }
}

/** 来源徽章：HF 榜单 / 订阅·关键词 / 搜索。 */
@Composable
fun SourceBadge(paper: Paper) {
    val colors = MiuixTheme.colorScheme
    val label = when (paper.source) {
        PaperSource.HF_DAILY -> "HF 榜单"
        PaperSource.ARXIV -> paper.sourceKeyword?.let { "订阅 · $it" } ?: "订阅"
        PaperSource.SEARCH -> "搜索"
    }
    Row(
        modifier = Modifier
            .background(colors.tertiaryContainer.copy(alpha = 0.7f), SuperellipseShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = colors.onTertiaryContainer,
            maxLines = 1,
        )
    }
}

@Composable
private fun SavedPill() {
    val colors = MiuixTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(colors.primaryContainer.copy(alpha = 0.16f), SuperellipseShape(100.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        TintedIcon(UiIcons.BookmarkFilled, size = 10.dp, tint = colors.primary)
        Spacer(Modifier.width(3.dp))
        Text(text = "已收藏", fontSize = 10.sp, color = colors.primary)
    }
}

/**
 * 收藏按钮（规格六 6）：书签图标弹性缩放 + 轻微旋转。
 * 首次组合不播动画（避免滚动列表时全体弹跳）。
 */
@Composable
fun SaveButton(
    saved: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    var animatedBefore by remember { mutableStateOf(false) }

    LaunchedEffect(saved) {
        if (!animatedBefore) {
            animatedBefore = true
            return@LaunchedEffect
        }
        scale.snapTo(0.62f)
        rotation.snapTo(if (saved) -16f else 16f)
        scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f))
        rotation.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 520f))
    }

    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(38.dp)
            .scale(scale.value)
            .rotate(rotation.value)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TintedIcon(
            resId = if (saved) UiIcons.BookmarkFilled else UiIcons.BookmarkOutline,
            tint = if (saved) colors.primary else colors.onSurfaceVariantActions,
            size = 20.dp,
        )
    }
}
