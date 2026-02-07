package com.example.epubspoon.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.epubspoon.model.BookData
import com.example.epubspoon.parser.EpubParser
import com.example.epubspoon.parser.SegmentHelper
import com.example.epubspoon.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val storage = StorageManager(application)

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // App 启动时尝试恢复上次选中的书籍
        restoreLastBook()
    }

    /**
     * 导入 EPUB 文件
     */
    fun importBook(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val contentResolver = getApplication<Application>().contentResolver

                // 1. 计算 MD5
                val md5 = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { StorageManager.calcMd5(it) }
                        ?: throw Exception("无法读取文件")
                }

                // 2. 查缓存
                val cached = storage.loadSegmentsCache(md5)
                if (cached != null) {
                    val progress = storage.loadProgress(md5)
                    storage.saveCurrentBookMd5(md5)
                    _uiState.value = UiState.Success(cached, progress, md5)
                    return@launch
                }

                // 3. 缓存未命中，解析 EPUB
                val bookData = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val parseResult = EpubParser.parse(inputStream)
                        val segments = SegmentHelper.getSmartSegments(parseResult.chapterTexts)

                        if (segments.isEmpty()) {
                            throw Exception("此书无文本内容")
                        }

                        BookData(
                            bookTitle = parseResult.title,
                            segments = segments
                        )
                    } ?: throw Exception("无法读取文件")
                }

                // 4. 缓存分段结果
                storage.saveSegmentsCache(md5, bookData)
                storage.saveCurrentBookMd5(md5)

                val progress = storage.loadProgress(md5)
                _uiState.value = UiState.Success(bookData, progress, md5)

            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("无文本内容") == true -> "此书无文本内容"
                    else -> "无法解析此文件，请确认是 .epub 格式"
                }
                _uiState.value = UiState.Error(msg)
            }
        }
    }

    /**
     * 跳转到指定分段
     */
    fun selectSegment(index: Int) {
        val state = _uiState.value
        if (state is UiState.Success) {
            val clampedIndex = index.coerceIn(0, state.bookData.segments.size - 1)
            storage.saveProgress(state.md5, clampedIndex)
            _uiState.value = state.copy(currentIndex = clampedIndex)
        }
    }

    /**
     * 获取当前段文本并前进到下一段
     * @return 当前段文本，若已到最后一段返回 null
     */
    fun copyAndAdvance(): String? {
        val state = _uiState.value
        if (state !is UiState.Success) return null

        val segments = state.bookData.segments
        val currentIndex = state.currentIndex

        if (currentIndex >= segments.size) return null

        val text = segments[currentIndex]

        // 如果不是最后一段，前进
        if (currentIndex < segments.size - 1) {
            val newIndex = currentIndex + 1
            storage.saveProgress(state.md5, newIndex)
            _uiState.value = state.copy(currentIndex = newIndex)
        }

        return text
    }

    /**
     * 是否已到最后一段
     */
    fun isLastSegment(): Boolean {
        val state = _uiState.value
        if (state !is UiState.Success) return true
        return state.currentIndex >= state.bookData.segments.size - 1
    }

    /**
     * 恢复上次选中的书籍
     */
    private fun restoreLastBook() {
        try {
            val md5 = storage.getCurrentBookMd5() ?: return
            val cached = storage.loadSegmentsCache(md5) ?: return
            val progress = storage.loadProgress(md5)
            _uiState.value = UiState.Success(cached, progress, md5)
        } catch (_: Exception) {
            // 缓存损坏，忽略
        }
    }
}
