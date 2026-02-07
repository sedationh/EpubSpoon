package com.example.epubspoon.storage

import android.content.Context
import com.example.epubspoon.model.BookData
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 存储管理器：进度持久化 + 分段结果缓存
 */
class StorageManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("epubspoon_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ==================== 进度存储 ====================

    /**
     * 保存阅读进度
     */
    fun saveProgress(md5: String, currentIndex: Int) {
        prefs.edit().putInt("progress_$md5", currentIndex).apply()
    }

    /**
     * 读取阅读进度，默认为 0
     */
    fun loadProgress(md5: String): Int {
        return prefs.getInt("progress_$md5", 0)
    }

    // ==================== 分段缓存 ====================

    /**
     * 缓存分段结果到 JSON 文件
     */
    fun saveSegmentsCache(md5: String, bookData: BookData) {
        val file = getSegmentsFile(md5)
        file.writeText(gson.toJson(bookData))
    }

    /**
     * 读取缓存的分段结果，未命中返回 null
     */
    fun loadSegmentsCache(md5: String): BookData? {
        val file = getSegmentsFile(md5)
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), BookData::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 检查缓存是否存在
     */
    fun hasCachedSegments(md5: String): Boolean {
        return getSegmentsFile(md5).exists()
    }

    private fun getSegmentsFile(md5: String): File {
        return File(context.filesDir, "segments_$md5.json")
    }

    // ==================== 当前书籍记录 ====================

    /**
     * 保存当前选中的书籍 MD5
     */
    fun saveCurrentBookMd5(md5: String) {
        prefs.edit().putString("current_book_md5", md5).apply()
    }

    /**
     * 获取当前选中的书籍 MD5
     */
    fun getCurrentBookMd5(): String? {
        return prefs.getString("current_book_md5", null)
    }

    // ==================== 列表显示设置 ====================

    fun saveDetailMode(detailed: Boolean) {
        prefs.edit().putBoolean("list_detail_mode", detailed).apply()
    }

    fun isDetailMode(): Boolean {
        return prefs.getBoolean("list_detail_mode", false)
    }

    // ==================== MD5 工具 ====================

    companion object {
        /**
         * 计算 InputStream 的 MD5（挂起函数，在 IO 线程执行）
         */
        suspend fun calcMd5(inputStream: InputStream): String = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            var bytesRead: Int
            inputStream.use { stream ->
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
