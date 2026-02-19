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
import android.widget.PopupMenu
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
import com.example.epubspoon.BuildConfig
import com.example.epubspoon.R
import com.example.epubspoon.databinding.ActivityMainBinding
import com.example.epubspoon.service.FloatingService
import com.example.epubspoon.storage.StorageManager
import com.example.epubspoon.updater.UpdateChecker
import com.example.epubspoon.viewmodel.MainViewModel
import com.example.epubspoon.viewmodel.UiState
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var segmentAdapter: SegmentAdapter
    private lateinit var chapterAdapter: ChapterAdapter
    private lateinit var storage: StorageManager

    private var floatingServiceRunning = false
    /** 当前选中的 Tab：0=分段，1=章节 */
    private var currentTab = 0

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

        // 显示当前版本号
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"

        setupRecyclerView()
        setupListeners()
        observeState()

        // 应用存储的详细/省略模式
        val detailed = storage.isDetailMode()
        segmentAdapter.setDetailMode(detailed)
        binding.btnToggleDetail.text = if (detailed) "省略" else "详细"

        // 处理从外部打开的 EPUB 文件（Google Drive、文件管理器等）
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 从 storage 同步进度（悬浮窗可能已修改）
        viewModel.syncProgress()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                // 尝试获取持久化权限（部分来源可能不支持）
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                viewModel.importBook(uri)
            }
        }
    }

    private fun setupRecyclerView() {
        segmentAdapter = SegmentAdapter { index ->
            viewModel.selectSegment(index)
        }
        binding.rvSegments.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = segmentAdapter
        }

        chapterAdapter = ChapterAdapter { index, text ->
            copyChapterToClipboard(index, text)
        }
        binding.rvChapters.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = chapterAdapter
        }

        // 设置 Tab
        setupTabLayout()
    }

    private fun setupTabLayout() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("分段"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("章节"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                switchTab(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    /**
     * 切换分段/章节 Tab
     */
    private fun switchTab(tabIndex: Int) {
        val state = viewModel.uiState.value
        if (state !is UiState.Success) return

        when (tabIndex) {
            0 -> {
                // 分段 Tab
                binding.rvSegments.visibility = View.VISIBLE
                binding.rvChapters.visibility = View.GONE
                binding.btnStartFloat.visibility = if (floatingServiceRunning) View.GONE else View.VISIBLE
                binding.btnStopFloat.visibility = if (floatingServiceRunning) View.VISIBLE else View.GONE
                // 搜索栏和详细按钮在分段模式下可用
                binding.btnToggleDetail.visibility = View.VISIBLE
                binding.btnToggleSearch.visibility = View.VISIBLE
                binding.tvProgress.text = "${state.currentIndex + 1}/${state.bookData.segments.size}"
            }
            1 -> {
                // 章节 Tab
                binding.rvSegments.visibility = View.GONE
                binding.rvChapters.visibility = View.VISIBLE
                binding.btnStartFloat.visibility = View.GONE
                binding.btnStopFloat.visibility = View.GONE
                binding.searchBar.visibility = View.GONE
                // 章节模式下隐藏分段相关按钮
                binding.btnToggleDetail.visibility = View.GONE
                binding.btnToggleSearch.visibility = View.GONE
                binding.tvProgress.text = "${state.bookData.chapters.orEmpty().size} 章"
            }
        }
    }

    /**
     * 复制章节到剪贴板，并显示反馈
     */
    private fun copyChapterToClipboard(index: Int, text: String) {
        copyToClipboard(text)
        chapterAdapter.setCopiedIndex(index)
        Toast.makeText(this, "已复制第 ${index + 1} 章", Toast.LENGTH_SHORT).show()

        // 3 秒后恢复
        binding.rvChapters.postDelayed({
            chapterAdapter.setCopiedIndex(-1)
        }, 3000)
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
        // 检查更新
        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdate()
        }

        // 导入按钮
        binding.btnImport.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("application/epub+zip"))
        }

        // 更多菜单
        binding.btnMore.setOnClickListener { view ->
            showMoreMenu(view)
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
        binding.searchBar.visibility = View.GONE
        binding.tabLayout.visibility = View.GONE
        binding.rvSegments.visibility = View.GONE
        binding.rvChapters.visibility = View.GONE
        binding.btnStartFloat.visibility = View.GONE
        binding.btnStopFloat.visibility = View.GONE
    }

    private fun showLoadingState() {
        binding.bookEmptyArea.visibility = View.GONE
        binding.bookInfoArea.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.searchBar.visibility = View.GONE
        binding.tabLayout.visibility = View.GONE
        binding.rvSegments.visibility = View.GONE
        binding.rvChapters.visibility = View.GONE
        binding.btnStartFloat.visibility = View.GONE
        binding.btnStopFloat.visibility = View.GONE
    }

    private fun showSuccessState(state: UiState.Success) {
        binding.bookEmptyArea.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.bookInfoArea.visibility = View.VISIBLE
        // searchBar 默认隐藏，用户点"搜索"按钮展开
        binding.tabLayout.visibility = View.VISIBLE

        binding.tvBookTitle.text = state.bookData.bookTitle

        // 更新分段数据
        segmentAdapter.updateData(state.bookData.segments, state.currentIndex)

        // 更新章节数据（旧缓存可能无 chapters 字段）
        chapterAdapter.updateData(state.bookData.chapters.orEmpty())

        // 根据当前 Tab 切换显示
        switchTab(currentTab)

        // 分段模式下自动滚动到当前段
        if (currentTab == 0) {
            binding.rvSegments.scrollToPosition(state.currentIndex)
        }
    }

    private fun showErrorState(message: String) {
        binding.bookEmptyArea.visibility = View.VISIBLE
        binding.bookInfoArea.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.searchBar.visibility = View.GONE
        binding.tabLayout.visibility = View.GONE
        binding.rvSegments.visibility = View.GONE
        binding.rvChapters.visibility = View.GONE
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

    private fun checkForUpdate() {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = UpdateChecker.check(BuildConfig.VERSION_NAME)
            if (result == null) {
                Toast.makeText(this@MainActivity, "检查更新失败，请检查网络连接", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (result.hasUpdate) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("发现新版本 v${result.latestVersion}")
                    .setMessage(
                        buildString {
                            append("当前版本：v${result.currentVersion}\n")
                            append("最新版本：v${result.latestVersion}\n\n")
                            if (result.releaseNotes.isNotBlank()) {
                                append("更新说明：\n${result.releaseNotes}\n\n")
                            }
                            append("⚠️ 如安装时提示「签名不一致」，请先卸载旧版本再安装。\n（阅读进度不会丢失）")
                        }
                    )
                    .setPositiveButton("下载更新") { _, _ ->
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl))
                        startActivity(intent)
                    }
                    .setNegativeButton("稍后再说", null)
                    .show()
            } else {
                Toast.makeText(this@MainActivity, "当前已是最新版本 v${result.currentVersion}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_more, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reimport -> {
                    openDocumentLauncher.launch(arrayOf("application/epub+zip"))
                    true
                }
                R.id.action_copy_context -> {
                    copyContextSegments()
                    true
                }
                R.id.action_clear -> {
                    if (floatingServiceRunning) {
                        stopService(Intent(this, FloatingService::class.java))
                        floatingServiceRunning = false
                    }
                    viewModel.clearBook()
                    Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun copyContextSegments() {
        val state = viewModel.uiState.value
        if (state !is UiState.Success) return

        val segments = state.bookData.segments
        val endIndex = state.currentIndex

        val contextText = buildString {
            // 已读段落
            for (i in 0..endIndex) {
                append("[${i + 1}]")
                append("\n")
                append(segments[i])
                if (i < endIndex) append("\n\n")
            }
            // 标记当前进度
            append("\n\n---\n")
            append("以上是我目前读到的内容（第 1~${endIndex + 1} 段，共 ${segments.size} 段），请基于这些内容继续协助我。")
        }

        copyToClipboard(contextText)
        Toast.makeText(
            this,
            "已复制第 1~${endIndex + 1} 段",
            Toast.LENGTH_SHORT
        ).show()
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
