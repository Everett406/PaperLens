package com.paperlens.app.data.remote

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime

/**
 * arXiv Atom XML 手写解析器（XmlPullParser，流式、零依赖）。
 *
 * 解析范围：entry 下的 id / title / summary / author>name / published / link(rel=alternate)。
 * 容错点：
 * - id 形如 http://arxiv.org/abs/2401.12345v2 → 截取末段并去掉版本号，得到全局唯一 arxivId；
 * - 标题/摘要中的换行与多余空白折叠为单个空格（arXiv 原文常带硬换行）；
 * - published 兼容 Instant（…Z）与 OffsetDateTime（…-05:00）两种格式，失败回退 now；
 * - namespaces 关闭匹配，直接按 local 名取标签，避免命名空间前缀差异。
 */
object ArxivAtomParser {

    data class Entry(
        val arxivId: String,
        val title: String,
        val summary: String,
        val authors: List<String>,
        val publishedAt: Long,
        val paperUrl: String?,
    )

    fun parse(xml: String): List<Entry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val entries = mutableListOf<Entry>()
        var current: MutableEntry? = null
        var currentAuthors: MutableList<String>? = null
        var textTarget: StringBuilder? = null
        var linkRel: String? = null
        var linkHref: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "entry" -> {
                            current = MutableEntry()
                            currentAuthors = mutableListOf()
                        }
                        "id" -> if (current != null) textTarget = StringBuilder()
                        "title" -> if (current != null) textTarget = StringBuilder()
                        "summary" -> if (current != null) textTarget = StringBuilder()
                        "published" -> if (current != null) textTarget = StringBuilder()
                        "author" -> if (current != null) textTarget = null
                        "name" -> if (current != null) textTarget = StringBuilder()
                        "link" -> {
                            linkRel = parser.getAttributeValue(null, "rel")
                            linkHref = parser.getAttributeValue(null, "href")
                        }
                    }
                }

                XmlPullParser.TEXT -> textTarget?.append(parser.text ?: "")

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "entry" -> {
                            current?.let { e ->
                                val id = e.id.substringAfterLast('/').replace(Regex("v\\d+$"), "")
                                if (id.isNotBlank()) {
                                    entries.add(
                                        Entry(
                                            arxivId = id,
                                            title = e.title,
                                            summary = e.summary,
                                            authors = currentAuthors.orEmpty().toList(),
                                            publishedAt = parseTime(e.published),
                                            paperUrl = e.alternateLink ?: "https://arxiv.org/abs/$id",
                                        )
                                    )
                                }
                            }
                            current = null
                            currentAuthors = null
                            textTarget = null
                        }
                        "id" -> { current?.id = textTarget?.toString()?.trim().orEmpty(); textTarget = null }
                        "title" -> { current?.title = collapse(textTarget?.toString()); textTarget = null }
                        "summary" -> { current?.summary = collapse(textTarget?.toString()); textTarget = null }
                        "published" -> { current?.published = textTarget?.toString()?.trim().orEmpty(); textTarget = null }
                        "name" -> {
                            val n = textTarget?.toString()?.trim().orEmpty()
                            if (n.isNotEmpty()) currentAuthors?.add(n)
                            textTarget = null
                        }
                        "link" -> {
                            // feed 级 link（rel=self）在 entry 外，current 为空时忽略
                            if (current != null && (linkRel == "alternate" || linkRel == null)) {
                                current.alternateLink = linkHref
                            }
                            linkRel = null
                            linkHref = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        return entries
    }

    private class MutableEntry {
        var id: String = ""
        var title: String = ""
        var summary: String = ""
        var published: String = ""
        var alternateLink: String? = null
    }

    private fun collapse(raw: String?): String =
        raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    private fun parseTime(raw: String, fallback: Long = System.currentTimeMillis()): Long =
        runCatching { Instant.parse(raw).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .getOrDefault(fallback)
}
