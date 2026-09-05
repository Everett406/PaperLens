package com.paperlens.app.domain

/**
 * 领域模型层：UI 只认这里的类型，数据层负责与 Room/网络实体互转。
 */

/** 论文来源。列表角标与排序策略依赖它； papers.source 列存 [name]。 */
enum class PaperSource(val displayName: String) {
    /** Hugging Face Daily Papers 当日榜 */
    HF_DAILY("HF 榜单"),

    /** 「全部」流：arXiv AI 相关类目最新提交（国内可直连，今日页兜底信息源） */
    ARXIV_ALL("arXiv"),

    /** 关键词订阅的 arXiv 最新结果 */
    ARXIV("订阅"),

    /** 搜索页命中的结果 */
    SEARCH("搜索"),
}

/** 书架状态：NONE 表示已收藏但未分类（UI chips 的「全部」包含它）。 */
enum class ShelfStatus(val dbValue: String?) {
    NONE(null),
    LATER("LATER"),
    READ("READ");

    val label: String
        get() = when (this) {
            NONE -> "未分类"
            LATER -> "稍后读"
            READ -> "已读"
        }

    companion object {
        fun fromDb(value: String?): ShelfStatus = entries.firstOrNull { it.dbValue == value } ?: NONE
    }
}

/** AI 三层阅读的层。标题面向普通读者，避免术语。 */
enum class AiLayer(val tabLabel: String, val hint: String) {
    STORY("故事", "用大白话讲清这篇论文在干什么"),
    DETAILS("细节", "方法流程、关键数字与作者承认的短板"),
    FIRST_PRINCIPLES("第一性原理", "结论凭什么成立，承继谁、反对谁"),
}

data class Paper(
    val arxivId: String,
    val title: String,
    val authors: List<String>,
    val abstract: String,
    val upvotes: Int,
    val source: PaperSource,
    val sourceKeyword: String?,
    val publishedAt: Long,
    val fetchedAt: Long,
    val paperUrl: String?,
) {
    val absUrl: String get() = paperUrl?.takeIf { it.isNotBlank() } ?: "https://arxiv.org/abs/$arxivId"
    val alphaXivUrl: String get() = "https://www.alphaxiv.org/abs/$arxivId"

    /** 短 id 展示：2401.12345 */
    val shortId: String get() = arxivId.substringAfterLast('/')
}
