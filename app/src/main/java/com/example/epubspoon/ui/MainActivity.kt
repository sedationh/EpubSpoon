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
import com.example.epubspoon.viewmodel.MainViewModel
import com.example.epubspoon.viewmodel.UiState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var segmentAdapter: SegmentAdapter

    private var instructionExpanded = false
    private var floatingServiceRunning = false
    private var shouldAutoStartFloat = false

    private val defaultInstruction = """
You are my English reading assistant. I will send you passages from an English book one at a time. For each passage, please respond in the following format:

## Translation
Translate every sentence into Chinese, keeping the original sentence order. Place the English sentence first, followed by the Chinese translation on the next line, with a blank line between each pair.

## Key Vocabulary
List 5-10 important or difficult words/phrases from this passage in a table:
| Word/Phrase | Meaning (Chinese) | Example from text |

## Summary
Summarize the main idea of this passage in 2-3 sentences in Chinese.

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
            shouldAutoStartFloat = true
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

        setupRecyclerView()
        setupListeners()
        observeState()
    }

    private fun setupRecyclerView() {
        segmentAdapter = SegmentAdapter { index ->
            viewModel.selectSegment(index)
        }
        binding.rvSegments.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = segmentAdapter
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

        // 关闭悬浮窗
        binding.btnStopFloat.setOnClickListener {
            stopService(Intent(this, FloatingService::class.java))
            floatingServiceRunning = false
            binding.btnStopFloat.visibility = View.GONE
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
        binding.rvSegments.visibility = View.GONE
        binding.btnStopFloat.visibility = View.GONE
    }

    private fun showLoadingState() {
        binding.bookEmptyArea.visibility = View.GONE
        binding.bookInfoArea.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.cardInstruction.visibility = View.GONE
        binding.rvSegments.visibility = View.GONE
        binding.btnStopFloat.visibility = View.GONE
    }

    private fun showSuccessState(state: UiState.Success) {
        binding.bookEmptyArea.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.bookInfoArea.visibility = View.VISIBLE
        binding.cardInstruction.visibility = View.VISIBLE
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

        // 仅导入时自动启动悬浮窗（恢复状态不自动启动）
        if (shouldAutoStartFloat) {
            shouldAutoStartFloat = false
            checkAndStartFloatingService()
        }
    }

    private fun showErrorState(message: String) {
        binding.bookEmptyArea.visibility = View.VISIBLE
        binding.bookInfoArea.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.cardInstruction.visibility = View.GONE
        binding.rvSegments.visibility = View.GONE
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

    override fun onDestroy() {
        super.onDestroy()
        stopService(Intent(this, FloatingService::class.java))
        floatingServiceRunning = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startFloatingService()
        }
    }
}
