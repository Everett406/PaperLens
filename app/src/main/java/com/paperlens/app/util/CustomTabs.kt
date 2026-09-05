package com.paperlens.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Chrome Custom Tabs 打开外部页面（规格二：详情页用 Custom Tab 打开 arXiv / alphaXiv）。 */
fun openInCustomTab(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, Uri.parse(url))
    } catch (_: Exception) {
        // 无 Custom Tabs 提供方时回退系统浏览器
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
