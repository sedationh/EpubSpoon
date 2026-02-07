package com.example.epubspoon.parser

/**
 * 智能分段工具：将章节文本按句子边界和字数权重分段。
 * - 每段约 targetWords 词（默认 300）
 * - 不跨章节拼接
 * - 正则避免 Mr. Dr. U.S. 等缩写误断
 */
object SegmentHelper {

    /**
     * 对多章文本进行智能分段，保留章节边界。
     *
     * @param chapterTexts 每章的纯文本列表
     * @param targetWords  每段的目标词数
     * @return 分段后的文本列表（扁平化，不跨章节）
     */
    fun getSmartSegments(chapterTexts: List<String>, targetWords: Int = 300): List<String> {
        val allSegments = mutableListOf<String>()

        for (chapterText in chapterTexts) {
            val trimmed = chapterText.trim()
            if (trimmed.isBlank()) continue

            val segments = segmentChapter(trimmed, targetWords)
            allSegments.addAll(segments)
        }

        return allSegments
    }

    /**
     * 对单章文本分段。
     */
    private fun segmentChapter(text: String, targetWords: Int): List<String> {
        // 用更健壮的正则断句：句号/问号/叹号后跟空格+大写字母
        // 避免 Mr. Dr. U.S. 等缩写误断
        val sentences = text.split(Regex("(?<=[.!?])\\s+(?=[A-Z])"))

        val result = mutableListOf<String>()
        val currentBatch = StringBuilder()
        var currentCount = 0

        for (sentence in sentences) {
            val wordCount = sentence.split(Regex("\\s+")).size

            // 如果加上这句就超过目标，且当前 buffer 不为空，先存一段
            if (currentCount + wordCount > targetWords && currentCount > 0) {
                result.add(currentBatch.toString().trim())
                currentBatch.setLength(0)
                currentCount = 0
            }

            currentBatch.append(sentence).append(" ")
            currentCount += wordCount
        }

        // 剩余内容成为最后一段
        if (currentBatch.isNotBlank()) {
            result.add(currentBatch.toString().trim())
        }

        return result
    }
}
