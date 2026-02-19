package com.example.epubspoon.model

/**
 * 书籍数据：书名 + 分段后的文本列表 + 原始章节列表
 */
data class BookData(
    val bookTitle: String,
    val segments: List<String>,
    /** 按章节划分的完整文本列表（每个元素是一整章），旧缓存可能为 null */
    val chapters: List<String>? = null
)
