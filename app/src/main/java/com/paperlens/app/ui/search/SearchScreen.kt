package com.paperlens.app.ui.search

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paperlens.app.di.AppGraph
import com.paperlens.app.ui.components.AppTextField
import com.paperlens.app.ui.components.Corners
import com.paperlens.app.ui.components.EmptyState
import com.paperlens.app.ui.components.PaperCard
import com.paperlens.app.ui.components.SuperellipseShape
import com.paperlens.app.ui.components.TintedIcon
import com.paperlens.app.ui.components.UiIcons
import com.paperlens.app.ui.rememberScrollToHide
import com.paperlens.app.ui.nav.LocalBottomBarHideController
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全屏搜索页（规格二）：自动聚焦、历史 chips、结果复用论文卡可直接收藏。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SearchScreen(
    graph: AppGraph,
    sharedScope: SharedTransitionScope?,
    animScope: AnimatedVisibilityScope?,
    onBack: () -> Unit,
    onOpenPaper: (String) -> Unit,
) {
    val vm: SearchViewModel = viewModel(
        factory = viewModelFactory { initializer { SearchViewModel(graph) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val savedIds by vm.savedIds.collectAsStateWithLifecycle()

    val colors = MiuixTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val scrollController = LocalBottomBarHideController.current
    if (scrollController != null) rememberScrollToHide(listState, scrollController)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 6.dp))
        // —— 搜索框行 ——
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(SuperellipseShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    )
                    .padding(6.dp),
            ) {
                TintedIcon(UiIcons.Back, tint = colors.onSurface, size = 21.dp)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .squircleSurface(colors.surfaceContainer, Corners.medium)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                BasicTextField(
                    value = ui.query,
                    onValueChange = vm::onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TintedIcon(UiIcons.Search, tint = colors.onSurfaceVariantActions, size = 17.dp)
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.weight(1f)) {
                                if (ui.query.isEmpty()) {
                                    Text(
                                        text = "搜索 arXiv 论文（标题 / 摘要）",
                                        fontSize = 14.5.sp,
                                        color = colors.onSurfaceVariantActions,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                            if (ui.query.isNotEmpty()) {
                                TintedIcon(
                                    UiIcons.Close,
                                    tint = colors.onSurfaceVariantActions,
                                    size = 16.dp,
                                    modifier = Modifier
                                        .clip(SuperellipseShape(10.dp))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) { vm.onQueryChange("") }
                                        .padding(2.dp),
                                )
                            }
                        }
                    },
                )
            }
        }

        // —— 历史 chips（未输入时） ——
        val showHistory = ui.query.isEmpty() && history.isNotEmpty()
        if (showHistory) {
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                Text(
                    text = "最近搜索",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "清空",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariantActions,
                    modifier = Modifier
                        .clip(SuperellipseShape(10.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = vm::clearHistory,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(history, key = { it.id }) { h ->
                    Box(
                        modifier = Modifier
                            .squircleSurface(colors.surfaceContainer, Corners.pill)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { vm.onQueryChange(h.query) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = h.query,
                            fontSize = 13.sp,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // —— 结果 ——
        when {
            ui.searching && ui.results.isEmpty() -> {
                Text(
                    text = "正在检索 arXiv…",
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            !ui.searching && ui.searchedOnce && ui.results.isEmpty() -> {
                EmptyState(
                    title = when {
                        ui.errorMessage != null -> "检索没成功"
                        ui.offline -> "离线状态，且本地没有相关缓存"
                        else -> "没有找到相关论文"
                    },
                    subtitle = ui.errorMessage
                        ?: if (ui.offline) "联网后重试即可" else "换个关键词试试，例如 \"graph neural network\"",
                )
            }

            ui.results.isNotEmpty() -> {
                if (ui.offline || ui.errorMessage != null) {
                    Text(
                        text = ui.errorMessage ?: "当前离线，展示本地缓存结果",
                        fontSize = 11.5.sp,
                        color = colors.onSurfaceVariantActions,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(ui.results, key = { it.arxivId }) { paper ->
                        val saved = paper.arxivId in savedIds
                        PaperCard(
                            paper = paper,
                            saved = saved,
                            onOpen = { onOpenPaper(paper.arxivId) },
                            onToggleSave = { vm.toggleSave(paper, saved) },
                            sharedScope = sharedScope,
                            animScope = animScope,
                            sharedKey = "paper-search-${paper.arxivId}",
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}
