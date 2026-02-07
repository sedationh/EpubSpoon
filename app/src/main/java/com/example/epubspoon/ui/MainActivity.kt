package com.example.epubspoon.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.epubspoon.R
import com.example.epubspoon.databinding.ActivityMainBinding
import com.example.epubspoon.service.FloatingService
import com.example.epubspoon.storage.StorageManager
import com.example.epubspoon.viewmodel.MainViewModel
import com.example.epubspoon.viewmodel.UiState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var segmentAdapter: SegmentAdapter
    private lateinit var storage: StorageManager

    private var instructionExpanded = false
    private var floatingServiceRunning = false

    private val defaultInstruction = """
You are my English reading assistant and cultural guide. I will send you passages from an English book one at a time.

For each passage, please go through it **sentence by sentence** in order. For each sentence, provide:

1. **English original** — the sentence as-is.
2. **Chinese translation** — natural, fluent Chinese translation.
3. **Inline notes** — right after the translation, annotate as needed:
   - **word/phrase** — Chinese meaning；usage note or nuance if helpful.
   - If a sentence involves **cultural references, historical allusions, religious/mythological context, social customs, literary devices, or implied meanings** that a Chinese reader might not immediately grasp, add a 💡 note explaining the cultural/contextual background in Chinese.
   - Not every sentence needs a 💡 note — only add when there's genuine cultural or contextual depth worth explaining.

After all sentences are done, add:

## Summary
Summarize the main idea of this passage in 2-3 sentences in Chinese.

---

### Example output format:

**① He felt like a modern-day Sisyphus, endlessly pushing the boulder uphill.**
他觉得自己像一个现代的西西弗斯，永无止境地把巨石推上山坡。
- **Sisyphus** — 西西弗斯；希腊神话人物
- **boulder** — 巨石，大圆石
- 💡 西西弗斯是希腊神话中被宙斯惩罚的人物，必须永远将巨石推上山顶，但每次快到顶时巨石就会滚落。后来常用来比喻徒劳无功、永无尽头的努力。法国哲学家加缪在《西西弗斯的神话》中将其重新解读为荒诞英雄。

**② "Well, that's just not cricket," she muttered under her breath.**
"好吧，这太不像话了，"她小声嘟囔道。
- **not cricket** — 不公平的，不正当的；英式口语
- **mutter under one's breath** — 低声嘟囔，小声抱怨
- 💡 "not cricket" 是一个英国特有的表达，源自板球运动（cricket）中对公平竞赛精神的强调。在英国文化中，板球被视为"绅士运动"，违反其精神就意味着不光彩、不公正。这个表达在美式英语中几乎不用。

**③ She raised an eyebrow, unimpressed.**
她挑了挑眉，并不为所动。
- **raise an eyebrow** — 挑眉；表示怀疑或不以为然
- **unimpressed** — 不为所动的，没有被打动的

---
Keep this format consistent for every passage I send. No need to confirm or repeat instructions. Just wait for my first passage.
    """.trimIndent()

    // 文件选择器
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // 获取持久化权限
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.importBook(it)
        }
    }

    // 悬浮窗权限回调
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startFloatingService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = StorageManager(this)

        setupRecyclerView()
        setupListeners()
        observeState()

        // 应用存储的详细/省略模式
        val detailed = storage.isDetailMode()
        segmentAdapter.setDetailMode(detailed)
        binding.btnToggleDetail.text = if (detailed) "省略" else "详细"
    }

    private fun setupRecyclerView() {
        segmentAdapter = SegmentAdapter { index ->
            viewModel.selectSegment(index)
            // 如果悬浮窗正在运行，重启以同步进度
            if (floatingServiceRunning) {
                restartFloatingService()
            }
        }
        binding.rvSegments.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = segmentAdapter
        }
    }

    private fun performSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isBlank()) return

        val state = viewModel.uiState.value
        if (state !is UiState.Success) return

        // 先尝试当作序号
        val asNumber = query.toIntOrNull()
        if (asNumber != null) {
            val targetIndex = (asNumber - 1).coerceIn(0, state.bookData.segments.size - 1)
            viewModel.selectSegment(targetIndex)
            binding.rvSegments.scrollToPosition(targetIndex)
            if (floatingServiceRunning) restartFloatingService()
            Toast.makeText(this, "已跳转到第 ${targetIndex + 1} 段", Toast.LENGTH_SHORT).show()
            return
        }

        // 关键词搜索：从当前位置往后找
        val segments = state.bookData.segments
        val startFrom = state.currentIndex + 1
        for (i in segments.indices) {
            val idx = (startFrom + i) % segments.size
            if (segments[idx].contains(query, ignoreCase = true)) {
                viewModel.selectSegment(idx)
                binding.rvSegments.scrollToPosition(idx)
                if (floatingServiceRunning) restartFloatingService()
                Toast.makeText(this, "找到：第 ${idx + 1} 段", Toast.LENGTH_SHORT).show()
                return
            }
        }

        Toast.makeText(this, "未找到 \"$query\"", Toast.LENGTH_SHORT).show()
    }

    private fun restartFloatingService() {
        val state = viewModel.uiState.value
        if (state !is UiState.Success) return
        try {
            val intent = Intent(this, FloatingService::class.java).apply {
                putExtra("md5", state.md5)
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e("EpubSpoon", "Failed to restart floating service", e)
        }
    }

    private fun setupListeners() {
        // 导入按钮
        binding.btnImport.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/epub+zip"))
        }

        // 重新导入
        binding.btnReimport.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/epub+zip"))
        }

        // 详细/省略切换
        binding.btnToggleDetail.setOnClickListener {
            val newMode = !storage.isDetailMode()
            storage.saveDetailMode(newMode)
            segmentAdapter.setDetailMode(newMode)
            binding.btnToggleDetail.text = if (newMode) "省略" else "详细"
        }

        // 搜索栏展开/收起
        binding.btnToggleSearch.setOnClickListener {
            val visible = binding.searchBar.visibility == View.VISIBLE
            binding.searchBar.visibility = if (visible) View.GONE else View.VISIBLE
        }

        // 展开/折叠母指令
        binding.btnToggleInstruction.setOnClickListener {
            instructionExpanded = !instructionExpanded
            binding.etInstruction.visibility = if (instructionExpanded) View.VISIBLE else View.GONE
        }

        // 复制母指令
        binding.btnCopyInstruction.setOnClickListener {
            val text = binding.etInstruction.text.toString().ifBlank { defaultInstruction }
            copyToClipboard(text)
            Toast.makeText(this, "已复制母指令", Toast.LENGTH_SHORT).show()
        }

        // 搜索/跳转
        binding.etSearch.setOnClickListener {
            binding.etSearch.isFocusable = true
            binding.etSearch.isFocusableInTouchMode = true
            binding.etSearch.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(binding.etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        binding.btnSearch.setOnClickListener { performSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        // 启动悬浮窗
        binding.btnStartFloat.setOnClickListener {
            checkAndStartFloatingService()
        }

        // 关闭悬浮窗
        binding.btnStopFloat.setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            floatingServiceRunning = false
            binding.btnStopFloat.visibility = View.GONE
            binding.btnStartFloat.visibility = View.VISIBLE
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is UiState.Idle -> showIdleState()
                        is UiState.Loading -> showLoadingState()
                        is UiState.Success -> showSuccessState(state)
                        is UiState.Error -> showErrorState(state.message)
                    }
                }
            }
        }
    }

    private fun showIdleState() {
        binding.bookEmptyArea.visibility = View.VISIBLE
        binding.bookInfoArea.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.cardInstruction.visibility = View.GONE
        binding.searchBar.visibility = View.GONE
        binding.rvSegments.visibility = View.GONE
        binding.btnStartFloat.visibility = View.GONE
        binding.btnStopFloat.visibility = View.GONE
    }

    private fun showLoadingState() {
        binding.bookEmptyArea.visibility = View.GONE
        binding.bookInfoArea.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.cardInstruction.visibility = View.GONE
        binding.searchBar.visibility = View.GONE
        binding.rvSegments.visibility = View.GONE
        binding.btnStartFloat.visibility = View.GONE
        binding.btnStopFloat.visibility = View.GONE
    }

    private fun showSuccessState(state: UiState.Success) {
        binding.bookEmptyArea.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.bookInfoArea.visibility = View.VISIBLE
        binding.cardInstruction.visibility = View.VISIBLE
        // searchBar 默认隐藏，用户点"搜索"按钮展开
        binding.rvSegments.visibility = View.VISIBLE
        binding.btnStopFloat.visibility = View.VISIBLE

        binding.tvBookTitle.text = state.bookData.bookTitle
        binding.tvProgress.text = "${state.currentIndex + 1}/${state.bookData.segments.size}"

        // 预填母指令
        if (binding.etInstruction.text.isNullOrBlank()) {
            binding.etInstruction.setText(defaultInstruction)
        }

        segmentAdapter.updateData(state.bookData.segments, state.currentIndex)

        // 自动滚动到当前段
        binding.rvSegments.scrollToPosition(state.currentIndex)

        // 显示启动/关闭悬浮窗按钮
        if (floatingServiceRunning) {
            binding.btnStartFloat.visibility = View.GONE
            binding.btnStopFloat.visibility = View.VISIBLE
        } else {
            binding.btnStartFloat.visibility = View.VISIBLE
            binding.btnStopFloat.visibility = View.GONE
        }
    }

    private fun showErrorState(message: String) {
        binding.bookEmptyArea.visibility = View.VISIBLE
        binding.bookInfoArea.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.cardInstruction.visibility = View.GONE
        binding.searchBar.visibility = View.GONE
        binding.rvSegments.visibility = View.GONE
        binding.btnStartFloat.visibility = View.GONE
        binding.btnStopFloat.visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun checkAndStartFloatingService() {
        if (floatingServiceRunning) return
        if (Settings.canDrawOverlays(this)) {
            startFloatingService()
        } else {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("EpubSpoon 需要在其他应用上方显示悬浮按钮，请在设置中开启")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayPermissionLauncher.launch(intent)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun startFloatingService() {
        val state = viewModel.uiState.value
        if (state !is UiState.Success) return

        // Android 13+ 需要通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
                return
            }
        }

        try {
            val intent = Intent(this, FloatingService::class.java).apply {
                putExtra("md5", state.md5)
            }
            startForegroundService(intent)
            floatingServiceRunning = true
            binding.btnStartFloat.visibility = View.GONE
            binding.btnStopFloat.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e("EpubSpoon", "Failed to start floating service", e)
            Toast.makeText(this, "悬浮窗启动失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("EpubSpoon", text))
    }

    // 不在 onDestroy 停止服务，让悬浮窗在 Activity 销毁后继续运行

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startFloatingService()
        }
    }
}
