package com.paperlens.app.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.paperlens.app.data.prefs.AiProtocol
import com.paperlens.app.di.AppGraph
import com.paperlens.app.ui.components.AppTextField
import com.paperlens.app.ui.components.Corners
import com.paperlens.app.ui.components.PrimaryButton
import com.paperlens.app.ui.components.SuperellipseShape
import com.paperlens.app.ui.components.SpringTabs
import com.paperlens.app.ui.components.TintedIcon
import com.paperlens.app.ui.components.UiIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * AI 服务设置页（v1.1）：协议 / 预设 / 连接配置 / 连通性测试。
 * 从「我的 → AI 服务配置」进入；配置即改即存，测试前也会强制落库。
 */
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    graph: AppGraph,
) {
    val vm: AiSettingsViewModel = viewModel(
        factory = viewModelFactory { initializer { AiSettingsViewModel(graph) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val colors = MiuixTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintedIcon(
                UiIcons.Back,
                tint = colors.onSurface,
                size = 21.dp,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    )
                    .padding(18.dp),
            )
            Text(
                text = "AI 服务",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        }
        Spacer(Modifier.height(8.dp))

        // —— 协议 ——
        SectionCard(title = "服务协议") {
            SpringTabs(
                tabs = AiProtocol.entries.map { it.label },
                selected = ui.draft.protocol.ordinal,
                onSelect = { vm.setProtocol(AiProtocol.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = protocolDescription(ui.draft.protocol),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.onSurfaceVariantSummary,
            )
        }

        // —— 常用预设 ——
        val presetList = vm.presets[ui.draft.protocol].orEmpty()
        if (presetList.isNotEmpty()) {
            SectionCard(title = "常用服务商") {
                PresetChips(
                    presets = presetList,
                    activeBaseUrl = ui.draft.baseUrl,
                    onSelect = vm::applyPreset,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "点按自动填入接口地址；标注「国内直连」的服务无需代理。也可改用任意中转地址。",
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    color = colors.onSurfaceVariantActions,
                )
            }
        }

        // —— 连接配置 ——
        SectionCard(title = "连接配置") {
            AppTextField(
                value = ui.draft.baseUrl,
                onValueChange = { v -> vm.updateDraft { it.copy(baseUrl = v) } },
                placeholder = "Base URL（默认 ${ui.draft.protocol.defaultBaseUrl}）",
            )
            Spacer(Modifier.height(8.dp))
            AppTextField(
                value = ui.draft.apiKey,
                onValueChange = { v -> vm.updateDraft { it.copy(apiKey = v) } },
                placeholder = "API Key",
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(8.dp))
            AppTextField(
                value = ui.draft.model,
                onValueChange = { v -> vm.updateDraft { it.copy(model = v) } },
                placeholder = "模型名（${ui.draft.protocol.modelHint}）",
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(
                text = if (ui.testState == AiSettingsViewModel.TestState.TESTING) "测试中…" else "测试连通性",
                onClick = vm::test,
                enabled = ui.testState != AiSettingsViewModel.TestState.TESTING,
            )
            ui.testMessage?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = msg,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = when (ui.testState) {
                        AiSettingsViewModel.TestState.OK -> colors.primary
                        AiSettingsViewModel.TestState.FAIL -> colors.error
                        else -> colors.onSurfaceVariantSummary
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "配置即改即存，无需手动保存；测试通过后回到论文详情即可生成三层阅读。",
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = colors.onSurfaceVariantActions,
            )
        }

        SectionCard(title = "思考模型") {
            Text(
                text = "推理型模型（DeepSeek-R1、Claude 思考、Gemini 思考、Qwen 等）的思考过程会被自动过滤，" +
                    "只把最终正文渲染进阅读卡片；思考内容不消耗你的注意力，也不入库。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = colors.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(140.dp))
    }
}

@Composable
private fun protocolDescription(protocol: AiProtocol): String = when (protocol) {
    AiProtocol.OPENAI ->
        "所有兼容 OpenAI Chat Completions 协议的服务都能接：DeepSeek、Kimi、智谱 GLM、OpenRouter、各类中转站等。"
    AiProtocol.ANTHROPIC ->
        "Claude Messages API（x-api-key 鉴权）。官方接口国内需自备代理，也可填中转服务地址。"
    AiProtocol.GEMINI ->
        "Google Generative Language API。官方接口国内需自备代理，也可填中转地址。"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetChips(
    presets: List<AiSettingsViewModel.Preset>,
    activeBaseUrl: String,
    onSelect: (AiSettingsViewModel.Preset) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            val active = preset.baseUrl.trimEnd('/') == activeBaseUrl.trimEnd('/')
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        if (active) colors.primaryContainer.copy(alpha = 0.32f)
                        else colors.surfaceContainerHigh.copy(alpha = 0.6f),
                        SuperellipseShape(100.dp),
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(preset) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    text = preset.label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) colors.primary else colors.onSurface,
                )
                if (preset.domestic) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "国内直连",
                        fontSize = 10.sp,
                        color = colors.onTertiaryContainer,
                        modifier = Modifier
                            .background(colors.tertiaryContainer.copy(alpha = 0.7f), SuperellipseShape(100.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
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
