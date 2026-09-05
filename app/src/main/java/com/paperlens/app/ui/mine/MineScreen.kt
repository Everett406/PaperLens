package com.paperlens.app.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paperlens.app.data.prefs.AppSettings
import com.paperlens.app.data.prefs.ThemeMode
import com.paperlens.app.di.AppGraph
import com.paperlens.app.ui.components.AppTextField
import com.paperlens.app.ui.components.Corners
import com.paperlens.app.ui.components.PaperDialog
import com.paperlens.app.ui.components.PrimaryButton
import com.paperlens.app.ui.components.SecondaryButton
import com.paperlens.app.ui.components.SuperellipseShape
import com.paperlens.app.ui.components.SpringTabs
import com.paperlens.app.ui.components.TintedIcon
import com.paperlens.app.ui.components.UiIcons
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 动态取色种子色预设（HyperOS 风格高饱和系）。 */
private val SeedColors = listOf(
    0xFF3D7EFF.toInt(), // 蓝
    0xFF00B4C5.toInt(), // 青
    0xFF7B5BFF.toInt(), // 紫
    0xFFFF5B8D.toInt(), // 粉
    0xFFE63946.toInt(), // 红
    0xFFFF9500.toInt(), // 橙
    0xFF34C759.toInt(), // 绿
    0xFF6D6D72.toInt(), // 石墨
)

/**
 * 「我的」页（规格二 3）：AI 服务入口 / 关键词订阅 / 外观 / 数据。
 * v1.1 起 AI 配置独立到 [AiSettingsScreen]，此处仅展示状态入口。
 */
@Composable
fun MineScreen(
    graph: AppGraph,
    onOpenVersion: () -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val vm: MineViewModel = viewModel(
        factory = viewModelFactory { initializer { MineViewModel(graph) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val subs by vm.subscriptions.collectAsStateWithLifecycle()

    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var newKeyword by remember { mutableStateOf("") }

    val colors = MiuixTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp))
        Text(
            text = "我的",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(14.dp))

        SectionCard(title = "AI 服务") {
            SettingRow(
                title = if (ui.settings.aiConfigured) "已连接 · ${ui.settings.aiModel}" else "未配置",
                subtitle = if (ui.settings.aiConfigured)
                    "${ui.settings.aiProtocol.label} · 点按管理协议 / 密钥 / 模型"
                else
                    "支持 OpenAI 兼容 / Anthropic / Gemini 三种协议，点按配置",
                onClick = onOpenAiSettings,
            )
        }

        SectionCard(title = "关键词订阅") {
            Text(
                text = "每个关键词都会拉取 arXiv 最新论文并合并进「今日 · 订阅」",
                fontSize = 12.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            if (subs.isEmpty()) {
                Text(
                    text = "还没有订阅，试试 \"LLM agent\" 或 \"diffusion model\"",
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariantActions,
                )
            } else {
                Column {
                    subs.forEachIndexed { index, sub ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = sub.keyword,
                                    fontSize = 15.sp,
                                    color = if (sub.enabled) colors.onSurface else colors.onSurfaceVariantActions,
                                )
                            }
                            Switch(
                                checked = sub.enabled,
                                onCheckedChange = { vm.setSubscriptionEnabled(sub.id, it) },
                            )
                            Spacer(Modifier.width(8.dp))
                            TintedIcon(
                                UiIcons.Trash,
                                tint = colors.onSurfaceVariantActions,
                                size = 19.dp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { vm.removeSubscription(sub.id) }
                                    .padding(4.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(
                    value = newKeyword,
                    onValueChange = { newKeyword = it },
                    placeholder = "输入关键词，如 LLM agent",
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .squircleSurface(colors.primary, Corners.medium)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (newKeyword.isNotBlank()) {
                                vm.addSubscription(newKeyword) { added ->
                                    // 重复关键词时输入框不清空，便于修改
                                    if (added) newKeyword = ""
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    TintedIcon(UiIcons.Plus, tint = colors.onPrimary, size = 20.dp)
                }
            }
        }

        SectionCard(title = "外观") {
            Text(text = "深浅色", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
            Spacer(Modifier.height(8.dp))
            SpringTabs(
                tabs = listOf("跟随系统", "浅色", "深色"),
                selected = ui.settings.themeMode.ordinal,
                onSelect = { vm.setThemeMode(ThemeMode.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Text(text = "主题色（动态取色种子）", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SeedColors.forEach { seed ->
                    val selected = seed == ui.settings.seedColor
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color(seed))
                            .border(
                                width = if (selected) 2.5.dp else 0.5.dp,
                                color = if (selected) colors.onSurface else colors.outline,
                                shape = CircleShape,
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { vm.setSeedColor(seed) },
                    )
                }
            }
        }

        SectionCard(title = "数据") {
            SettingRow(title = "清空缓存", subtitle = "清除论文缓存与 AI 生成记录，收藏与订阅保留") {
                showClearDialog = true
            }
            SettingRow(title = "版本", subtitle = "纸镜 PaperLens · 查看版本信息") {
                onOpenVersion()
            }
        }
        Spacer(Modifier.height(140.dp))
    }

    if (showClearDialog) {
        PaperDialog(
            visible = true,
            title = "清空缓存？",
            onDismiss = { showClearDialog = false },
        ) {
            Text(
                text = "将删除论文缓存、搜索历史与 AI 阅读记录；书架收藏和关键词订阅会保留。",
                fontSize = 13.5.sp,
                lineHeight = 21.sp,
                color = colors.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    text = "取消",
                    onClick = { showClearDialog = false },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = "清空",
                    onClick = {
                        vm.clearCache()
                        showClearDialog = false
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .fillMaxWidth()
            .squircleSurface(colors.surfaceContainer, Corners.large)
            .padding(20.dp),
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(SuperellipseShape(Corners.medium))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, color = colors.onSurface)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = subtitle, fontSize = 11.5.sp, color = colors.onSurfaceVariantSummary)
            }
        }
        TintedIcon(
            UiIcons.Back,
            tint = colors.onSurfaceVariantActions,
            size = 16.dp,
            modifier = Modifier.rotate(180f), // 左箭头旋转为右箭头
        )
    }
}
