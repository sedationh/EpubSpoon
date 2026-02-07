package com.example.epubspoon.model

/**
 * 书籍数据：书名 + 分段后的文本列表
 */
data class BookData(
    val bookTitle: String,
    val segments: List<String>
)
