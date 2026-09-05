package com.paperlens.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.paperlens.app.data.prefs.AppSettings
import com.paperlens.app.data.repo.AiReadManager
import com.paperlens.app.di.AppGraph
import com.paperlens.app.ui.components.AcrylicSurface
import com.paperlens.app.ui.components.Corners
import com.paperlens.app.ui.components.LocalHazeState
import com.paperlens.app.ui.components.MiniSpinner
import com.paperlens.app.ui.components.SuperellipseShape
import com.paperlens.app.ui.components.TintedIcon
import com.paperlens.app.ui.components.UiIcons
import com.paperlens.app.ui.components.appHazeSource
import com.paperlens.app.ui.components.rememberAppHazeState
import com.paperlens.app.ui.detail.DetailScreen
import com.paperlens.app.ui.mine.AiSettingsScreen
import com.paperlens.app.ui.mine.MineScreen
import com.paperlens.app.ui.mine.VersionScreen
import com.paperlens.app.ui.nav.LocalBottomBarHideController
import com.paperlens.app.ui.nav.Routes
import com.paperlens.app.ui.nav.ScrollToHideController
import com.paperlens.app.ui.search.SearchScreen
import com.paperlens.app.ui.shelf.ShelfScreen
import com.paperlens.app.ui.theme.PaperLensTheme
import com.paperlens.app.ui.today.TodayScreen
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用根：SharedTransitionLayout（卡片→详情头 morph）+ NavHost（hazeSource 内容源）+
 * 悬浮胶囊底栏（hazeEffect，下滑隐藏/上滑弹回，弹簧动画）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PaperLensRoot(graph: AppGraph) {
    val settings by graph.settingsStore.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    PaperLensTheme(themeMode = settings.themeMode, seedColor = settings.seedColor) {
        val navController = rememberNavController()
        val hazeState = rememberAppHazeState()
        val bottomBarController = remember { ScrollToHideController() }
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        CompositionLocalProvider(
            LocalHazeState provides hazeState,
            // v1.5：把底栏隐藏控制器下发给各列表页（此前接线断裂，底栏不随滚动隐藏）
            LocalBottomBarHideController provides bottomBarController,
        ) {
            val colors = MiuixTheme.colorScheme
            Box(
                Modifier
                    .fillMaxSize()
                    .background(colors.background)
            ) {
                SharedTransitionLayout {
                    NavHost(
                        navController = navController,
                        startDestination = Routes.TODAY,
                        // Tab 间切换：淡入淡出 + 轻微缩放落差（v1.5 调优：纯交叉淡化时
                        // 两屏文字同框重叠观感差；退出屏缩小退后、进入屏从 98.5% 弹回，拉开层次）
                        enterTransition = {
                            fadeIn(tween(160)) + scaleIn(initialScale = 0.985f, animationSpec = tween(160))
                        },
                        exitTransition = {
                            fadeOut(tween(110)) + scaleOut(targetScale = 0.985f, animationSpec = tween(160))
                        },
                        popEnterTransition = {
                            fadeIn(tween(160)) + scaleIn(initialScale = 0.985f, animationSpec = tween(160))
                        },
                        popExitTransition = {
                            fadeOut(tween(110))
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .appHazeSource(hazeState),
                    ) {
                        composable(Routes.TODAY) {
                            TodayScreen(
                                graph = graph,
                                sharedScope = this@SharedTransitionLayout,
                                animScope = this,
                                onOpenSearch = { navController.navigateSafely(Routes.SEARCH) },
                                onOpenPaper = { id, origin ->
                                    navController.navigateSafely(Routes.detail(id, origin))
                                },
                            )
                        }
                        composable(Routes.SHELF) {
                            ShelfScreen(
                                graph = graph,
                                sharedScope = this@SharedTransitionLayout,
                                animScope = this,
                                onOpenPaper = { id ->
                                    navController.navigateSafely(Routes.detail(id, "shelf"))
                                },
                            )
                        }
                        composable(Routes.MINE) {
                            MineScreen(
                                graph = graph,
                                onOpenVersion = { navController.navigateSafely(Routes.VERSION) },
                                onOpenAiSettings = { navController.navigateSafely(Routes.AI_SETTINGS) },
                            )
                        }
                        composable(
                            Routes.AI_SETTINGS,
                            // 子页转场与 VERSION 等统一：slide-from-bottom + fade，返回反向
                            enterTransition = {
                                slideInVertically(spring(dampingRatio = 0.9f, stiffness = 380f)) { it } +
                                    fadeIn(tween(170))
                            },
                            popExitTransition = {
                                slideOutVertically(spring(dampingRatio = 0.95f, stiffness = 420f)) { it } +
                                    fadeOut(tween(150))
                            },
                        ) {
                            AiSettingsScreen(onBack = { navController.popBackStack() }, graph = graph)
                        }
                        composable(
                            Routes.SEARCH,
                            // 规格六 7：详情/搜索页 slide-from-bottom + fade，返回反向
                            enterTransition = {
                                slideInVertically(spring(dampingRatio = 0.9f, stiffness = 380f)) { it } +
                                    fadeIn(tween(170))
                            },
                            popExitTransition = {
                                slideOutVertically(spring(dampingRatio = 0.95f, stiffness = 420f)) { it } +
                                    fadeOut(tween(150))
                            },
                        ) {
                            SearchScreen(
                                graph = graph,
                                sharedScope = this@SharedTransitionLayout,
                                animScope = this,
                                onBack = { navController.popBackStack() },
                                onOpenPaper = { id ->
                                    navController.navigateSafely(Routes.detail(id, "search"))
                                },
                            )
                        }
                        composable(
                            Routes.DETAIL,
                            enterTransition = {
                                slideInVertically(spring(dampingRatio = 0.9f, stiffness = 380f)) { it } +
                                    fadeIn(tween(170))
                            },
                            popExitTransition = {
                                slideOutVertically(spring(dampingRatio = 0.95f, stiffness = 420f)) { it } +
                                    fadeOut(tween(150))
                            },
                        ) { entry ->
                            val arxivId = entry.arguments?.getString("arxivId").orEmpty()
                            val origin = entry.arguments?.getString("origin").orEmpty().ifEmpty { "all" }
                            DetailScreen(
                                graph = graph,
                                arxivId = arxivId,
                                origin = origin,
                                sharedScope = this@SharedTransitionLayout,
                                animScope = this,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            Routes.VERSION,
                            enterTransition = {
                                slideInVertically(spring(dampingRatio = 0.9f, stiffness = 380f)) { it } +
                                    fadeIn(tween(170))
                            },
                            popExitTransition = {
                                slideOutVertically(spring(dampingRatio = 0.95f, stiffness = 420f)) { it } +
                                    fadeOut(tween(150))
                            },
                        ) {
                            VersionScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }

                // —— AI 生成悬浮指示器（v1.5）：后台队列状态，点按跳回对应论文 ——
                val aiPill by graph.aiReadManager.pill.collectAsStateWithLifecycle()
                GenerationPill(
                    state = aiPill,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onClick = { job ->
                        navController.navigateSafely(Routes.detail(job.arxivId, "ai"))
                    },
                )

                // —— 悬浮胶囊底栏（亚克力三处之一） ——
                val isTopTab = currentRoute in Routes.TOP_TABS
                AnimatedVisibility(
                    visible = isTopTab && bottomBarController.visible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = 380f)) { it / 2 } +
                        fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
                    exit = slideOutVertically(spring(dampingRatio = 0.9f, stiffness = 450f)) { it / 2 } +
                        fadeOut(spring(stiffness = Spring.StiffnessMediumLow)),
                ) {
                    FloatingTabBar(
                        selectedRoute = currentRoute,
                        onSelect = { route ->
                            bottomBarController.reset()
                            if (route != currentRoute) {
                                navController.navigate(route) {
                                    popUpTo(Routes.TODAY) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun NavHostController.navigateSafely(route: String) {
    try {
        navigate(route)
    } catch (_: Exception) {
        // 理论不可达；防御重复点击竞态
    }
}

/**
 * AI 生成悬浮指示器（v1.5）：生成中/排队中/完成/失败四态。
 * 挂在 App 级 —— 生成已交给后台队列，回退/切页都不打断；点按跳回对应论文详情。
 */
@Composable
private fun GenerationPill(
    state: AiReadManager.PillState?,
    modifier: Modifier = Modifier,
    onClick: (AiReadManager.PillState) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val navPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    AnimatedVisibility(
        visible = state != null,
        modifier = modifier,
        enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = 380f)) { it / 2 } +
            fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
        exit = slideOutVertically(spring(dampingRatio = 0.9f, stiffness = 450f)) { it / 2 } +
            fadeOut(spring(stiffness = Spring.StiffnessMediumLow)),
    ) {
        val job = state ?: return@AnimatedVisibility
        AcrylicSurface(
            modifier = Modifier
                .padding(bottom = navPadding + 84.dp)
                .clip(SuperellipseShape(26.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onClick(job) },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                when (job.status) {
                    AiReadManager.Status.RUNNING, AiReadManager.Status.QUEUED ->
                        MiniSpinner(size = 15.dp)
                    AiReadManager.Status.DONE ->
                        TintedIcon(UiIcons.Check, tint = colors.primary, size = 16.dp)
                    AiReadManager.Status.FAILED ->
                        TintedIcon(UiIcons.Close, tint = colors.error, size = 15.dp)
                }
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(
                        text = when (job.status) {
                            AiReadManager.Status.RUNNING ->
                                "正在生成 · ${job.layer.tabLabel}层" +
                                    if (job.queuedCount > 0) "（还有 ${job.queuedCount} 个排队）" else ""
                            AiReadManager.Status.QUEUED ->
                                "排队中 · ${job.layer.tabLabel}层" +
                                    if (job.queuedCount > 0) "（还有 ${job.queuedCount} 个排队）" else ""
                            AiReadManager.Status.DONE -> "已生成${job.layer.tabLabel}层 · 点按查看"
                            AiReadManager.Status.FAILED -> "生成失败 · 点按查看"
                        },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Text(
                        text = job.title,
                        fontSize = 10.5.sp,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 250.dp),
                    )
                }
            }
        }
    }
}

/** 底部悬浮胶囊 Tab：亚克力 + 超椭圆 + 弹簧指示器（规格二/五/六）。 */
@Composable
private fun FloatingTabBar(
    selectedRoute: String?,
    onSelect: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val items = listOf(
        Triple(Routes.TODAY, UiIcons.NavToday, "今日"),
        Triple(Routes.SHELF, UiIcons.NavShelf, "书架"),
        Triple(Routes.MINE, UiIcons.NavMine, "我的"),
    )
    val selectedIndex = items.indexOfFirst { it.first == selectedRoute }.coerceAtLeast(0)
    val barHeight = 64.dp
    val capsuleShape = SuperellipseShape(barHeight / 2)
    val navPadding = WindowInsets.navigationBars.asPaddingValues()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AcrylicSurface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .height(barHeight)
                .clip(capsuleShape)
                .border(0.7.dp, colors.outline.copy(alpha = 0.35f), capsuleShape),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val itemWidth = maxWidth / items.size
                // 弹簧指示器：活动项背后的色块胶囊
                val indicatorX by animateDpAsState(
                    targetValue = itemWidth * selectedIndex,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
                    label = "tabIndicator",
                )
                Box(
                    modifier = Modifier
                        .offset(x = indicatorX + 6.dp, y = 6.dp)
                        .width(itemWidth - 12.dp)
                        .height(barHeight - 12.dp)
                        .background(
                            colors.primaryContainer.copy(alpha = 0.24f),
                            SuperellipseShape(barHeight - 12.dp),
                        ),
                )
                Row(Modifier.fillMaxSize()) {
                    items.forEachIndexed { index, (route, icon, label) ->
                        val active = index == selectedIndex
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onSelect(route) },
                        ) {
                            val iconScale by animateFloatAsState(
                                targetValue = if (active) 1.14f else 1f,
                                animationSpec = spring(dampingRatio = 0.5f, stiffness = 480f),
                                label = "tabIconScale",
                            )
                            TintedIcon(
                                resId = icon,
                                tint = if (active) colors.primary else colors.onSurfaceVariantSummary,
                                size = 22.dp,
                                modifier = Modifier.scale(iconScale),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (active) colors.primary else colors.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
        // 导航条避让
        Spacer(Modifier.height(navPadding.calculateBottomPadding() + 10.dp))
    }
}
