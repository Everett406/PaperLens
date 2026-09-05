package com.paperlens.app.ui.shelf

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paperlens.app.di.AppGraph
import com.paperlens.app.domain.ShelfStatus
import com.paperlens.app.ui.shelf.ShelfViewModel.ShelfItemUi
import com.paperlens.app.ui.components.AppTextField
import com.paperlens.app.ui.components.EmptyState
import com.paperlens.app.ui.components.PaperActionSheet
import com.paperlens.app.ui.components.PaperCard
import com.paperlens.app.ui.components.PaperDialog
import com.paperlens.app.ui.components.PrimaryButton
import com.paperlens.app.ui.components.SecondaryButton
import com.paperlens.app.ui.components.SpringTabs
import com.paperlens.app.ui.components.TintedIcon
import com.paperlens.app.ui.components.UiIcons
import com.paperlens.app.ui.rememberScrollToHide
import com.paperlens.app.ui.nav.ScrollToHideController
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 书架页（规格二 2）：状态 chips + 收藏卡片（切换状态/一句话笔记/移除）。
 * 移除入口 = 长按卡片弹出操作面板（规格允许「滑动或长按」，选长按：
 * 滑动移除与 LazyColumn 垂直滚动手势在小屏易误触，长按更稳）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ShelfScreen(
    graph: AppGraph,
    sharedScope: SharedTransitionScope?,
    animScope: AnimatedVisibilityScope?,
    onOpenPaper: (String) -> Unit,
) {
    val vm: ShelfViewModel = viewModel(
        factory = viewModelFactory { initializer { ShelfViewModel(graph) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    val scrollController = remember { ScrollToHideController() }
    val listState = rememberLazyListState()
    rememberScrollToHide(listState, scrollController)

    // 操作面板状态
    var actionTarget by remember { mutableStateOf<ShelfItemUi?>(null) }
    var noteTarget by remember { mutableStateOf<ShelfItemUi?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    var removeTarget by remember { mutableStateOf<ShelfItemUi?>(null) }

    val colors = MiuixTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp))
        Text(
            text = "书架",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))
        SpringTabs(
            tabs = ShelfFilter.entries.map { it.label },
            selected = ui.filter.ordinal,
            onSelect = { vm.setFilter(ShelfFilter.entries[it]) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))

        if (ui.items.isEmpty()) {
            EmptyState(
                title = "书架空空如也",
                subtitle = "在今日或搜索里点一下书签，论文就会出现在这里",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(ui.items, key = { it.paper.arxivId }) { item ->
                    val saved = true
                    PaperCard(
                        paper = item.paper,
                        saved = saved,
                        onOpen = { onOpenPaper(item.paper.arxivId) },
                        onToggleSave = { vm.toggleSave(item.paper, saved) },
                        note = item.note,
                        statusLabel = if (item.status == ShelfStatus.NONE) null else item.status.label,
                        onLongClick = { actionTarget = item },
                        sharedScope = sharedScope,
                        animScope = animScope,
                        sharedKey = "paper-${item.paper.arxivId}",
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    // —— 长按操作面板 ——
    actionTarget?.let { target ->
        PaperActionSheet(
            visible = true,
            onDismiss = { actionTarget = null },
        ) {
            Text(
                text = target.paper.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                maxLines = 2,
            )
            Spacer(Modifier.height(14.dp))
            SpringTabs(
                tabs = listOf("未分类", "稍后读", "已读"),
                selected = when (target.status) {
                    ShelfStatus.NONE -> 0
                    ShelfStatus.LATER -> 1
                    ShelfStatus.READ -> 2
                },
                onSelect = { index ->
                    val status = when (index) {
                        0 -> ShelfStatus.NONE
                        1 -> ShelfStatus.LATER
                        else -> ShelfStatus.READ
                    }
                    vm.setStatus(target.paper.arxivId, status)
                    actionTarget = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = "写一句话笔记",
                    onClick = {
                        noteTarget = target
                        noteDraft = target.note.orEmpty()
                        actionTarget = null
                    },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "移出书架",
                    onClick = {
                        removeTarget = target
                        actionTarget = null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // —— 笔记编辑对话框 ——
    noteTarget?.let { target ->
        PaperDialog(
            visible = true,
            title = "一句话笔记",
            onDismiss = { noteTarget = null },
        ) {
            AppTextField(
                value = noteDraft,
                onValueChange = { noteDraft = it },
                placeholder = "这篇论文为什么值得读？",
                singleLine = false,
                minLines = 3,
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = "保存",
                onClick = {
                    vm.setNote(target.paper.arxivId, noteDraft)
                    noteTarget = null
                },
            )
        }
    }

    // —— 移除确认 ——
    removeTarget?.let { target ->
        PaperDialog(
            visible = true,
            title = "移出书架？",
            onDismiss = { removeTarget = null },
        ) {
            Text(
                text = "「${target.paper.title}」将从书架移除，收藏笔记一并清除。",
                fontSize = 13.5.sp,
                lineHeight = 21.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = "取消",
                    onClick = { removeTarget = null },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = "移除",
                    onClick = {
                        vm.remove(target.paper.arxivId)
                        removeTarget = null
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
