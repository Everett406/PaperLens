package com.paperlens.app.ui.mine

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
import androidx.compose.runtime.remember
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
            InfoRow("数据来源", "Hugging Face Daily Papers · arXiv")
            InfoRow("开源协议", "MIT License")
        }
        Spacer(Modifier.height(16.dp))
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
