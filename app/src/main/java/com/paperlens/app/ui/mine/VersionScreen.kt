package com.paperlens.app.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperlens.app.BuildConfig
import com.paperlens.app.R
import com.paperlens.app.ui.components.Corners
import com.paperlens.app.ui.components.TintedIcon
import com.paperlens.app.ui.components.UiIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 版本页（规格二 3「数据 → 版本页」）。 */
@Composable
fun VersionScreen(onBack: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val buildTime = remember {
        val dt = Instant.ofEpochMilli(BuildConfig.BUILD_TIME).atZone(ZoneId.systemDefault())
        dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
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
        }
        Spacer(Modifier.height(46.dp))
        TintedIcon(
            R.drawable.ic_launcher_foreground,
            tint = colors.primary,
            size = 92.dp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "纸镜",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface,
        )
        Text(
            text = "PaperLens · 把论文读成人话",
            fontSize = 13.sp,
            color = colors.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(22.dp))
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .squircleSurface(colors.surfaceContainer, Corners.large),
        ) {
            InfoRow("版本", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            InfoRow("构建时间", buildTime)
            InfoRow("数据来源", "arXiv 直连优先 · GitHub 镜像兜底")
            InfoRow("开源协议", "MIT License")
        }
        Spacer(Modifier.height(16.dp))
        NetDiagCard()
        Spacer(Modifier.height(16.dp))
        CrashLogCard()
        Text(
            text = "GitHub 仓库：github.com/Everett406/PaperLens",
            fontSize = 12.sp,
            color = colors.primary,
            modifier = Modifier.padding(8.dp),
        )
        Text(
            text = "感谢 Miuix / Haze / Coil / Retrofit / OkHttp 等开源项目",
            fontSize = 11.sp,
            color = colors.onSurfaceVariantActions,
            modifier = Modifier.padding(top = 20.dp, bottom = 48.dp),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.padding(horizontal = 18.dp, vertical = 9.dp)) {
        Text(text = label, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
        Spacer(Modifier.height(2.dp))
        Text(text = value, fontSize = 14.5.sp, color = colors.onSurface)
    }
}

/**
 * 网络诊断卡：数据渠道每次失败都会在 NetDiag 留痕（filesDir/diag/net_log.txt）。
 * 如果「全部/订阅/搜索」一直没数据，复制这段日志发到 GitHub Issues，
 * 我就能看到是 DNS / 超时 / TLS / HTTP 哪一层的问题，不再是黑盒猜。无记录时不占位。
 */
@Composable
private fun NetDiagCard() {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    var logText by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        logText = runCatching {
            val f = java.io.File(context.filesDir, "diag/net_log.txt")
            if (f.exists()) f.readLines().takeLast(20).joinToString("\n").take(4000) else null
        }.getOrNull()
    }

    val text = logText ?: return
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .squircleSurface(colors.surfaceContainer, Corners.large)
            .padding(18.dp),
    ) {
        Text(
            text = "网络诊断",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "检测到论文数据拉取失败。如果列表一直没内容，点「复制日志」发到 GitHub Issues，我来对症修。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = text,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            color = colors.onSurfaceVariantActions,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .squircleSurface(colors.secondaryContainer, Corners.medium)
                    .clickable {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("paperlens_netlog", text))
                        copied = true
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (copied) "已复制 ✓" else "复制日志",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .squircleSurface(colors.surfaceContainer, Corners.medium)
                    .clickable {
                        runCatching {
                            java.io.File(context.filesDir, "diag/net_log.txt").delete()
                            logText = null
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "清除",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * 崩溃记录卡：应用闪退时 PaperLensApp 会把堆栈写入 filesDir/crash/last_crash.txt。
 * 此处展示最近一次崩溃，支持一键复制反馈给开发者；无记录时不占位。
 */
@Composable
private fun CrashLogCard() {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    var crashText by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        crashText = runCatching {
            val f = java.io.File(context.filesDir, "crash/last_crash.txt")
            if (f.exists()) f.readText().take(4000) else null
        }.getOrNull()
    }

    val text = crashText ?: return
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .squircleSurface(colors.surfaceContainer, Corners.large)
            .padding(18.dp),
    ) {
        Text(
            text = "检测到一次闪退记录",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "如果闪退反复出现，点「复制日志」后到 GitHub Issues 粘贴给我，我来修。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = text,
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            color = colors.onSurfaceVariantActions,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .squircleSurface(colors.secondaryContainer, Corners.medium)
                    .clickable {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("paperlens_crash", text))
                        copied = true
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (copied) "已复制 ✓" else "复制日志",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .squircleSurface(colors.surfaceContainer, Corners.medium)
                    .clickable {
                        runCatching {
                            java.io.File(context.filesDir, "crash/last_crash.txt").delete()
                            crashText = null
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "清除",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}
