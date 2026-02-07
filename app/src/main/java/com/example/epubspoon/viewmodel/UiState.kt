package com.example.epubspoon.viewmodel

import com.example.epubspoon.model.BookData

/**
 * UI 状态
 */
sealed class UiState {
    /** 初始状态，未导入书籍 */
    object Idle : UiState()

    /** 正在解析 EPUB */
    object Loading : UiState()

    /** 解析成功 */
    data class Success(
        val bookData: BookData,
        val currentIndex: Int,
        val md5: String
    ) : UiState()

    /** 解析失败 */
    data class Error(val message: String) : UiState()
}
