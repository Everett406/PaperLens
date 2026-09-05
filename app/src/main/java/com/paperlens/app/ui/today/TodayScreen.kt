package com.paperlens.app.ui.today

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paperlens.app.data.repo.CuratedRepository
import com.paperlens.app.di.AppGraph
import com.paperlens.app.ui.components.AcrylicSurface
import com.paperlens.app.ui.components.EmptyState
import com.paperlens.app.ui.components.PaperCard
import com.paperlens.app.ui.components.PaperPullToRefresh
import com.paperlens.app.ui.components.SpringTabs
import com.paperlens.app.ui.components.TintedIcon
import com.paperlens.app.ui.components.UiIcons
import com.paperlens.app.ui.nav.LocalBottomBarHideController
import com.paperlens.app.ui.rememberScrollToHide
import com.paperlens.app.util.TimeFormat
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 今日页（v1.5 三板块）：
 * - 亚克力顶栏（三处之一）：标题 + 搜索入口，滚动列表从下方穿过被模糊；
 * - 全部|精选|订阅 SpringTabs + HorizontalPager：点按 Tab 弹簧滑动高亮，
 *   左右滑动内容页时高亮跟随；
 * - 下拉刷新（v1.5 自研 PaperPullToRefresh）：修复 Miuix 在刷新态吞掉全部滚动、
 *   页面被「定死」的问题 —— 现在可以边加载边滑动；
 * - 空态文案区分：无数据 / 网络失败 / 未配置 / 未配置 AI（精选）；
 * - 共享键带板块来源前缀（paper-all-xxx / paper-feat-xxx / paper-sub-xxx）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TodayScreen(
    graph: AppGraph,
    sharedScope: SharedTransitionScope?,
    animScope: AnimatedVisibilityScope?,
    onOpenSearch: () -> Unit,
    onOpenPaper: (arxivId: String, origin: String) -> Unit,
) {
    val vm: TodayViewModel = viewModel(
        factory = viewModelFactory { initializer { TodayViewModel(graph) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(initialPage = ui.feed.ordinal) { TodayFeed.entries.size }
    // 左右滑动内容页 → 高亮滑块跟随落定页（点按 Tab 时 setFeed 后再滚动，此处同值回写无副作用）
    LaunchedEffect(pagerState.currentPage) {
        vm.setFeed(TodayFeed.entries[pagerState.currentPage])
    }
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colorScheme

    Box(Modifier.fillMaxSize()) {
        // —— 顶栏（亚克力） ——
        AcrylicSurface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .clip(SuperellipseBottom()),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "今日",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onOpenSearch,
                            )
                            .padding(6.dp),
                    ) {
                        TintedIcon(UiIcons.Search, tint = colors.onBackground, size = 22.dp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                SpringTabs(
                    tabs = TodayFeed.entries.map { it.label },
                    selected = ui.feed.ordinal,
                    onSelect = { index ->
                        vm.setFeed(TodayFeed.entries[index])
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // —— 内容 ——
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topBarHeightPx()),
        ) { page ->
            val feed = TodayFeed.entries[page]
            when (feed) {
                TodayFeed.FEATURED -> FeaturedPage(
                    vm = vm,
                    sharedScope = sharedScope,
                    animScope = animScope,
                    onOpenPaper = onOpenPaper,
                )
                else -> FeedPage(
                    feed = feed,
                    vm = vm,
                    ui = ui,
                    sharedScope = sharedScope,
                    animScope = animScope,
                    onOpenPaper = onOpenPaper,
                )
            }
        }
    }
}

/** 全部 / 订阅两板块：Room 缓存直渲 + 下拉强制刷新。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FeedPage(
    feed: TodayFeed,
    vm: TodayViewModel,
    ui: TodayViewModel.UiState,
    sharedScope: SharedTransitionScope?,
    animScope: AnimatedVisibilityScope?,
    onOpenPaper: (arxivId: String, origin: String) -> Unit,
) {
    val items = if (feed == TodayFeed.ALL) ui.all else ui.subscriptions
    // 每页独立滚动状态；驱动全局底栏隐藏（v1.5 接线修复）
    val listState = rememberLazyListState()
    LocalBottomBarHideController.current?.let { rememberScrollToHide(listState, it) }
    PaperPullToRefresh(
        isRefreshing = ui.refreshing,
        onRefresh = vm::refreshCurrent,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (items.isEmpty() && !ui.refreshing) {
            // 空态放进 LazyColumn：可滚动 → 嵌套下拉手势才生效
            val (title, subtitle) = emptyText(feed, ui)
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item {
                    EmptyState(
                        title = title,
                        subtitle = subtitle,
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 120.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.arxivId }) { paper ->
                    PaperCard(
                        paper = paper,
                        saved = paper.arxivId in ui.savedIds,
                        onOpen = { onOpenPaper(paper.arxivId, feed.origin) },
                        onToggleSave = { vm.toggleSave(paper) },
                        sharedScope = sharedScope,
                        animScope = animScope,
                        sharedKey = "paper-${feed.origin}-${paper.arxivId}",
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/** 精选板块：AI 每日精选（Embedding 匹配），各状态诚实呈现。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FeaturedPage(
    vm: TodayViewModel,
    sharedScope: SharedTransitionScope?,
    animScope: AnimatedVisibilityScope?,
    onOpenPaper: (arxivId: String, origin: String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val curated by vm.curated.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LocalBottomBarHideController.current?.let { rememberScrollToHide(listState, it) }

    PaperPullToRefresh(
        isRefreshing = curated is CuratedRepository.State.Loading,
        onRefresh = vm::refreshCurrent,
        modifier = Modifier.fillMaxSize(),
    ) {
        when (val state = curated) {
            is CuratedRepository.State.Ready -> {
                if (state.items.isEmpty()) {
                    EmptyColumn("今天没有匹配度高的论文", "多收藏几篇让画像更准，或下拉重新计算")
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = 120.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "caption") { FeaturedCaption(state) }
                        items(state.items, key = { it.paper.arxivId }) { scored ->
                            PaperCard(
                                paper = scored.paper,
                                saved = scored.paper.arxivId in ui.savedIds,
                                onOpen = { onOpenPaper(scored.paper.arxivId, "feat") },
                                onToggleSave = { vm.toggleSave(scored.paper) },
                                matchScore = (scored.score * 100).toInt(),
                                sharedScope = sharedScope,
                                animScope = animScope,
                                sharedKey = "paper-feat-${scored.paper.arxivId}",
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }

            CuratedRepository.State.Loading ->
                EmptyColumn("正在计算匹配度…", "把你的书架口味画像与今日论文做 Embedding 比对，大约需要几秒")
            CuratedRepository.State.Unconfigured ->
                EmptyColumn("精选需要 AI 服务", "到「我的 → AI 服务」配置后，这里会按你的书架口味，从当天抓到的论文里挑最匹配的几篇")
            CuratedRepository.State.NeedTaste ->
                EmptyColumn("先在书架收藏几篇论文", "收藏满 3 篇后，纸镜会用 Embedding 学习你的口味，再从当日论文里挑出最对味的")
            CuratedRepository.State.Unsupported ->
                EmptyColumn("当前协议不支持精选", "Embedding 暂只支持 OpenAI 兼容协议（DeepSeek、Kimi、GLM、OpenRouter 等），可在「我的 → AI 服务」切换")
            is CuratedRepository.State.Error ->
                EmptyColumn("精选计算失败", "${state.reason}；下拉重试")
            CuratedRepository.State.Idle ->
                EmptyColumn("AI 精选", "下拉开始计算：根据书架收藏的口味画像，从今日论文里挑出和你最匹配的几篇")
        }
    }
}

@Composable
private fun FeaturedCaption(state: CuratedRepository.State.Ready) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintedIcon(UiIcons.Sparkle, tint = colors.primary, size = 14.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                text = "根据书架 ${state.profileSize} 篇收藏计算 · ${TimeFormat.withTime(state.generatedAt)}",
                fontSize = 11.5.sp,
                color = colors.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "收藏越多，画像越准；下拉可重新计算",
            fontSize = 11.sp,
            color = colors.onSurfaceVariantActions,
        )
    }
}

/** 精选页各状态的空态（放进 LazyColumn 保住下拉手势链）。 */
@Composable
private fun EmptyColumn(title: String, subtitle: String) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            EmptyState(
                title = title,
                subtitle = subtitle,
                modifier = Modifier.fillParentMaxSize(),
            )
        }
    }
}

/** 空态文案：按板块 + 数据态 + 网络态给出诚实、可行动的提示（v1.4 附带具体原因）。 */
private fun emptyText(feed: TodayFeed, ui: TodayViewModel.UiState): Pair<String, String> = when (feed) {
    TodayFeed.ALL ->
        if (ui.allError) {
            "arXiv 暂时连不上" to
                buildString {
                    append("检查网络后下拉重试；已自动尝试 GitHub 镜像仍未成功")
                    ui.allReason?.let { append("。原因：$it") }
                }
        } else "还没有内容" to "下拉刷新试试"
    TodayFeed.FEATURED -> "AI 精选" to "下拉开始计算"
    TodayFeed.SUBSCRIPTIONS ->
        if (!ui.hasKeywords) "还没有订阅关键词" to "去「我的 → 关键词订阅」添加你感兴趣的方向"
        else if (ui.subscriptionsError) {
            "订阅刷新失败" to
                buildString {
                    append("arXiv 暂时连不上，检查网络后下拉重试")
                    ui.subscriptionsReason?.let { append("。原因：$it") }
                }
        } else "还没抓到相关论文" to "已订阅 ${ui.keywordsCount} 个关键词，下拉刷新试试，或稍后再来看看"
}

/** 顶栏高度（状态栏 + 内容），Pager 顶部避让。 */
@Composable
private fun topBarHeightPx(): androidx.compose.ui.unit.Dp {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return statusBar + 118.dp
}

/** 顶栏底部圆角（胶囊式下缘）。 */
private fun SuperellipseBottom() = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
