package com.paperlens.app.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paperlens.app.data.repo.AiReadManager
import com.paperlens.app.di.AppGraph
import com.paperlens.app.domain.AiLayer
import com.paperlens.app.domain.PaperSource
import com.paperlens.app.ui.components.AcrylicSurface
import com.paperlens.app.ui.components.Corners
import com.paperlens.app.ui.components.LocalHazeState
import com.paperlens.app.ui.components.MiniMarkdown
import com.paperlens.app.ui.components.MiniSpinner
import com.paperlens.app.ui.components.SourceBadge
import com.paperlens.app.ui.components.SpringTabs
import com.paperlens.app.ui.components.StreamingMarkdown
import com.paperlens.app.ui.components.SuperellipseShape
import com.paperlens.app.ui.components.TintedIcon
import com.paperlens.app.ui.components.UiIcons
import com.paperlens.app.ui.components.SaveButton
import com.paperlens.app.ui.components.appHazeSource
import com.paperlens.app.util.TimeFormat
import com.paperlens.app.util.openInCustomTab
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 论文详情页：
 * - 头部：标题/作者/日期/来源徽章/赞数 + 收藏/Custom Tab(arXiv)/alphaXiv；
 * - 摘要默认收起 3 行可展开；v1.5 新增「AI 翻译」流式翻译标题+摘要（带缓存）；
 * - AI 三层阅读：故事/细节/第一性原理 SpringTabs；v1.5 生成走后台队列 ——
 *   思考过程折叠卡实时可见、正文流式 markdown 逐行淡入、返回不中断、支持排队与一次生成三层；
 * - 吸顶操作栏（亚克力三处之一）：v1.5 定高 64dp + 底部发丝线，修复标题与正文透叠。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DetailScreen(
    graph: AppGraph,
    arxivId: String,
    origin: String,
    sharedScope: SharedTransitionScope?,
    animScope: AnimatedVisibilityScope?,
    onBack: () -> Unit,
) {
    val vm: DetailViewModel = viewModel(
        key = "detail-$arxivId",
        factory = viewModelFactory { initializer { DetailViewModel(graph, arxivId) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme

    val listState = rememberLazyListState()
    var abstractExpanded by remember { mutableStateOf(false) }

    // 滚过头部后吸顶栏标题浮现
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .appHazeSource(LocalHazeState.current),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 68.dp,
                bottom = 60.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // —— 头部（sharedBounds 目标：与列表卡片 morph） ——
            item(key = "header") {
                val paper = ui.paper
                val headerModifier = if (sharedScope != null && animScope != null) {
                    with(sharedScope) {
                        Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "paper-$origin-$arxivId"),
                            animatedVisibilityScope = animScope,
                            enter = fadeIn(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)),
                            exit = fadeOut(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)),
                            boundsTransform = { _, _ ->
                                spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
                            },
                        )
                    }
                } else Modifier

                Column(
                    modifier = headerModifier
                        .fillMaxWidth()
                        .squircleSurface(colors.surfaceContainer, Corners.large)
                        .padding(20.dp),
                ) {
                    Text(
                        text = paper?.title ?: "加载中…",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 29.sp,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = paper?.authors?.joinToString(" · ").orEmpty(),
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        paper?.let { SourceBadge(it) }
                        paper?.let {
                            Text(
                                text = TimeFormat.friendly(it.publishedAt),
                                fontSize = 11.5.sp,
                                color = colors.onSurfaceVariantSummary,
                            )
                        }
                        if ((paper?.upvotes ?: 0) > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TintedIcon(UiIcons.Upvote, size = 12.dp, tint = colors.onSurfaceVariantSummary)
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = paper?.upvotes?.toString().orEmpty(),
                                    fontSize = 11.5.sp,
                                    color = colors.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    // 操作行：收藏 / arXiv / alphaXiv（Custom Tabs）
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillAction(
                            text = if (ui.saved) "已收藏" else "收藏",
                            icon = if (ui.saved) UiIcons.BookmarkFilled else UiIcons.BookmarkOutline,
                            tint = if (ui.saved) colors.primary else colors.onSurface,
                            onClick = vm::toggleSave,
                            modifier = Modifier.weight(1f),
                        )
                        PillAction(
                            text = "arXiv 页",
                            icon = UiIcons.External,
                            tint = colors.onSurface,
                            onClick = { paper?.let { openInCustomTab(context, it.absUrl) } },
                            modifier = Modifier.weight(1f),
                        )
                        PillAction(
                            text = "alphaXiv",
                            icon = UiIcons.External,
                            tint = colors.onSurface,
                            onClick = { paper?.let { openInCustomTab(context, it.alphaXivUrl) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // —— 摘要（默认收起） + AI 翻译 ——
            item(key = "abstract") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .squircleSurface(colors.surfaceContainer, Corners.large)
                        .animateContentSize(
                            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
                        )
                        .padding(20.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { abstractExpanded = !abstractExpanded },
                    ) {
                        Text(
                            text = "摘要",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (abstractExpanded) "收起" else "展开全文",
                            fontSize = 12.sp,
                            color = colors.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = ui.paper?.abstract ?: "",
                        fontSize = 14.sp,
                        lineHeight = 24.sp,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = if (abstractExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { abstractExpanded = !abstractExpanded },
                    )
                    Spacer(Modifier.height(10.dp))
                    // —— AI 翻译（v1.5） ——
                    TranslationSection(ui = ui, onToggle = vm::toggleTranslation)
                }
            }

            // —— AI 三层阅读 ——
            item(key = "ai-tabs") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TintedIcon(UiIcons.Sparkle, tint = colors.primary, size = 17.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "AI 三层阅读",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        if (ui.job == null && ui.activeCount == 0) {
                            ui.reading?.let { reading ->
                                Text(
                                    text = "${TimeFormat.withTime(reading.generatedAt)} · ${reading.model ?: ""}",
                                    fontSize = 10.sp,
                                    color = colors.onSurfaceVariantActions,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SpringTabs(
                        tabs = AiLayer.entries.map { it.tabLabel },
                        selected = ui.layer.ordinal,
                        onSelect = { vm.setLayer(AiLayer.entries[it]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (ui.job == null && ui.activeCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiniSpinner(size = 12.dp, strokeWidth = 1.6.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "AI 正在阅读这篇论文（${ui.activeCount} 个任务进行中），切到对应层可看进度",
                                fontSize = 11.sp,
                                color = colors.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            item(key = "ai-content") {
                AiLayerCard(
                    ui = ui,
                    onGenerate = vm::generate,
                    onGenerateAll = vm::generateAll,
                    onRegenerate = vm::generate,
                    onGoConfigure = onBack, // 引导至「我的」：先回退，由用户再进入我的（保持导航栈干净）
                )
            }
        }

        // —— 吸顶操作栏（亚克力三处之一） ——
        AcrylicSurface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .clip(RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)),
        ) {
            Column(
                Modifier.padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            ) {
                // v1.5：定高 64dp —— 旧版内容自适应高度小于列表顶部避让（68dp），
                // 标题下方漏出一条未模糊缝隙，视觉上与正文「重叠」
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onBack,
                            )
                            .padding(8.dp),
                    ) {
                        TintedIcon(UiIcons.Back, tint = colors.onSurface, size = 21.dp)
                    }
                    AnimatedVisibility(
                        visible = scrolled,
                        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
                        exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = ui.paper?.title ?: "",
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = colors.onSurface,
                        )
                    }
                    if (!scrolled) Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = vm::toggleSave,
                            )
                            .padding(8.dp),
                    ) {
                        val saveScale by animateFloatAsState(
                            targetValue = if (ui.saved) 1.15f else 1f,
                            animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
                            label = "detailSaveScale",
                        )
                        TintedIcon(
                            resId = if (ui.saved) UiIcons.BookmarkFilled else UiIcons.BookmarkOutline,
                            tint = if (ui.saved) colors.primary else colors.onSurface,
                            size = 20.dp,
                            modifier = Modifier.scale(saveScale),
                        )
                    }
                }
                // 发丝分隔线：顶栏与正文之间多一层「挡」的边界
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.7.dp)
                        .background(colors.outline.copy(alpha = 0.22f)),
                )
            }
        }
    }
}

/** 摘要卡内的 AI 翻译区（v1.5）：翻译/隐藏切换、流式渲染、错误重试。 */
@Composable
private fun TranslationSection(
    ui: DetailViewModel.UiState,
    onToggle: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryPill(
                text = when {
                    ui.translationStreaming -> "翻译中…"
                    ui.translation != null -> "隐藏翻译"
                    else -> "AI 翻译成中文"
                },
                icon = UiIcons.Sparkle,
                onClick = onToggle,
            )
            if (ui.translationStreaming) {
                Spacer(Modifier.width(8.dp))
                MiniSpinner(size = 13.dp, strokeWidth = 1.6.dp)
            }
        }
        ui.translationError?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(text = "翻译失败：$err", fontSize = 12.sp, color = colors.error)
        }
        val translation = ui.translation
        if (translation != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceContainerHigh.copy(alpha = 0.45f), SuperellipseShape(Corners.medium))
                    .padding(14.dp),
            ) {
                if (ui.translationStreaming) {
                    StreamingMarkdown(text = translation)
                } else {
                    MiniMarkdown(text = translation)
                }
            }
        }
    }
}

@Composable
private fun PillAction(
    text: String,
    icon: Int,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(38.dp)
            .squircleSurface(colors.secondaryContainer, Corners.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        TintedIcon(icon, tint = tint, size = 15.dp)
        Spacer(Modifier.width(6.dp))
        Text(text = text, fontSize = 12.5.sp, color = tint, fontWeight = FontWeight.Medium)
    }
}

/** AI 层内容卡：排队/流式（思考折叠卡+淡入 markdown）/缓存/未配置引导/错误重试。 */
@Composable
private fun AiLayerCard(
    ui: DetailViewModel.UiState,
    onGenerate: () -> Unit,
    onGenerateAll: () -> Unit,
    onRegenerate: () -> Unit,
    onGoConfigure: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(colors.surfaceContainer, Corners.large)
            .padding(20.dp),
    ) {
        val job = ui.job
        val cached = ui.reading?.content
        when {
            job != null -> when (job.status) {
                AiReadManager.Status.QUEUED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MiniSpinner(size = 14.dp, strokeWidth = 1.8.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "排队中 · ${job.layer.tabLabel}层马上开始",
                            fontSize = 13.sp,
                            color = colors.onSurface,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "前面的层生成完会自动开始，不用等着这个页面",
                        fontSize = 11.5.sp,
                        color = colors.onSurfaceVariantActions,
                    )
                }

                AiReadManager.Status.RUNNING -> {
                    if (job.thinking.isNotEmpty() || job.text.isEmpty()) {
                        ThinkingCard(job = job)
                    }
                    if (job.text.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        StreamingMarkdown(text = job.text)
                    } else if (job.thinking.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiniSpinner(size = 14.dp, strokeWidth = 1.8.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(text = "正在连接模型…", fontSize = 13.sp, color = colors.onSurface)
                        }
                    }
                }

                else -> {
                    // FAILED：重新生成失败时保留旧缓存内容，错误信息放底部
                    if (cached != null) MiniMarkdown(text = cached)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "生成失败：${job.error ?: "未知错误"}",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    SecondaryPill(text = "重试", icon = UiIcons.Refresh, onClick = onRegenerate)
                }
            }

            cached != null -> {
                MiniMarkdown(text = cached)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryPill(text = "重新生成", icon = UiIcons.Refresh, onClick = onRegenerate)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "由 AI 生成，仅供参考",
                        fontSize = 10.sp,
                        color = colors.onSurfaceVariantActions,
                    )
                }
            }

            !ui.aiConfigured -> {
                Text(
                    text = "还没有配置 AI 服务",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = ui.layer.hint,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = colors.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "到「我的 → AI 服务」配置任意 OpenAI 兼容 / Anthropic / Gemini 接口（DeepSeek、Kimi、Claude、Gemini 等），即可一键把论文读成人话。",
                    fontSize = 12.5.sp,
                    lineHeight = 20.sp,
                    color = colors.onSurfaceVariantActions,
                )
                Spacer(Modifier.height(14.dp))
                SecondaryPill(text = "去「我的」配置", icon = UiIcons.Sparkle, onClick = onGoConfigure)
            }

            else -> {
                Text(
                    text = ui.layer.tabLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = ui.layer.hint,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = colors.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryPill(text = "生成${ui.layer.tabLabel}层阅读", icon = UiIcons.Sparkle, onClick = onGenerate)
                    SecondaryPill(text = "一次生成三层", icon = UiIcons.Note, onClick = onGenerateAll)
                }
                ui.translationError?.let { err ->
                    Spacer(Modifier.height(10.dp))
                    Text(text = err, fontSize = 12.sp, color = colors.error)
                }
            }
        }
    }
}

/**
 * 思考折叠卡（v1.5）：推理型模型的思考过程实时可见——
 * 思考阶段自动展开并滚到最底（「至少能看到你在动」）；正文开始输出后自动收起。
 */
@Composable
private fun ThinkingCard(job: AiReadManager.AiJob) {
    val colors = MiuixTheme.colorScheme
    var expanded by remember { mutableStateOf(true) }
    // 正文一开始输出就自动收起，把注意力还给正文（可手动再展开）
    LaunchedEffect(job.text.isNotEmpty()) {
        if (job.text.isNotEmpty()) expanded = false
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) -90f else -180f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
        label = "thinkChevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh.copy(alpha = 0.45f), SuperellipseShape(Corners.medium))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded },
        ) {
            if (job.text.isEmpty()) {
                MiniSpinner(size = 12.dp, strokeWidth = 1.6.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = if (job.text.isEmpty()) "正在思考…（${job.thinkingSeconds}s）" else "已思考 ${job.thinkingSeconds}s",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.weight(1f))
            TintedIcon(
                UiIcons.Back,
                tint = colors.onSurfaceVariantActions,
                size = 13.dp,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
        AnimatedVisibility(visible = expanded && job.thinking.isNotEmpty()) {
            val scrollState = rememberScrollState()
            // 思考流推进时跟随滚动到底
            LaunchedEffect(job.thinking.length) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            Text(
                text = job.thinking,
                fontSize = 11.5.sp,
                lineHeight = 18.sp,
                color = colors.onSurfaceVariantActions,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .height(190.dp)
                    .verticalScroll(scrollState),
            )
        }
    }
}

@Composable
private fun PrimaryPill(text: String, icon: Int, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(SuperellipseShape(20.dp))
            .background(colors.primary)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        TintedIcon(icon, tint = colors.onPrimary, size = 15.dp)
        Spacer(Modifier.width(6.dp))
        Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onPrimary)
    }
}

@Composable
private fun SecondaryPill(text: String, icon: Int, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(SuperellipseShape(20.dp))
            .background(colors.secondaryContainer)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        TintedIcon(icon, tint = colors.onSurface, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(text = text, fontSize = 12.5.sp, color = colors.onSurface)
    }
}
