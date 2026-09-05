package com.paperlens.app.ui.today

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paperlens.app.di.AppGraph
import com.paperlens.app.domain.Paper
import com.paperlens.app.ui.components.AcrylicSurface
import com.paperlens.app.ui.components.Corners
import com.paperlens.app.ui.components.EmptyState
import com.paperlens.app.ui.components.PaperCard
import com.paperlens.app.ui.components.SpringTabs
import com.paperlens.app.ui.components.TintedIcon
import com.paperlens.app.ui.components.UiIcons
import com.paperlens.app.ui.nav.ScrollToHideController
import com.paperlens.app.ui.rememberScrollToHide
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 今日页（规格二 1）：
 * - 亚克力顶栏（三处之一）：标题 + 搜索入口，滚动列表从下方穿过被模糊；
 * - 精选|订阅 SpringTabs + HorizontalPager，内容横向跟随滑入（规格六 3）；
 * - 下拉刷新（Miuix PullToRefresh）+ LazyColumn + animateItem。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TodayScreen(
    graph: AppGraph,
    sharedScope: SharedTransitionScope?,
    animScope: AnimatedVisibilityScope?,
    onOpenSearch: () -> Unit,
    onOpenPaper: (String) -> Unit,
) {
    val vm: TodayViewModel = viewModel(
        factory = viewModelFactory { initializer { TodayViewModel(graph) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(initialPage = ui.feed.ordinal) { TodayFeed.entries.size }
    val scope = rememberCoroutineScope()
    val scrollController = remember { ScrollToHideController() }
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
            val items = if (feed == TodayFeed.FEATURED) ui.featured else ui.subscriptions
            // 每页独立滚动状态；仅当前可见页驱动底栏隐藏
            val listState = rememberLazyListState()
            rememberScrollToHide(
                listState = listState,
                controller = scrollController,
                enabled = page == pagerState.currentPage,
            )
            PullToRefresh(
                isRefreshing = ui.refreshing,
                onRefresh = vm::refreshCurrent,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (items.isEmpty() && !ui.refreshing) {
                    EmptyState(
                        title = if (feed == TodayFeed.FEATURED) "今日榜单还在路上" else "还没有订阅关键词",
                        subtitle = if (feed == TodayFeed.FEATURED)
                            "下拉刷新试试，或稍后再来看看"
                        else
                            "去「我的 → 关键词订阅」添加你感兴趣的方向",
                        modifier = Modifier.fillMaxSize(),
                    )
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
                                onOpen = { onOpenPaper(paper.arxivId) },
                                onToggleSave = { vm.toggleSave(paper) },
                                sharedScope = sharedScope,
                                animScope = animScope,
                                sharedKey = "paper-${paper.arxivId}",
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 顶栏高度（状态栏 + 内容），Pager 顶部避让。 */
@Composable
private fun topBarHeightPx(): androidx.compose.ui.unit.Dp {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return statusBar + 118.dp
}

/** 顶栏底部圆角（胶囊式下缘）。 */
private fun SuperellipseBottom() = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
