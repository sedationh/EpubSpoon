package com.example.epubspoon.parser

import nl.siegmann.epublib.epub.EpubReader
import org.jsoup.Jsoup
import java.io.InputStream

/**
 * EPUB 解析器：读取 EPUB 文件，逐章提取纯文本。
 */
object EpubParser {

    data class ParseResult(
        val title: String,
        val chapterTexts: List<String>
    )

    /**
     * 解析 EPUB 文件，返回书名和每章的纯文本列表。
     * - 用 Epublib 读取 EPUB Spine（阅读顺序）
     * - 用 Jsoup 将 HTML 转为纯文本，自动过滤图片/表格/SVG
     * - 跳过空章节、目录页、版权页等无效内容
     */
    fun parse(inputStream: InputStream): ParseResult {
        val book = EpubReader().readEpub(inputStream)
        val title = book.title ?: "Unknown"

        val chapterTexts = mutableListOf<String>()

        for (spineRef in book.spine.spineReferences) {
            val resource = spineRef.resource ?: continue
            val html = String(resource.data, Charsets.UTF_8)

            // Jsoup 解析 HTML，移除非文本元素
            val doc = Jsoup.parse(html)
            doc.select("img, table, svg, script, style, nav").remove()

            val text = doc.body()?.text()?.trim() ?: ""

            // 跳过空章节和疑似目录/版权页（内容过短且包含关键词）
            if (text.isBlank()) continue
            if (text.length < 100 && isBoilerplate(text)) continue

            chapterTexts.add(text)
        }

        return ParseResult(title = title, chapterTexts = chapterTexts)
    }

    /**
     * 判断是否为目录页/版权页等无效内容
     */
    private fun isBoilerplate(text: String): Boolean {
        val lower = text.lowercase()
        val keywords = listOf(
            "table of contents", "contents", "copyright",
            "all rights reserved", "published by", "isbn",
            "cover", "title page"
        )
        return keywords.any { lower.contains(it) }
    }
}
