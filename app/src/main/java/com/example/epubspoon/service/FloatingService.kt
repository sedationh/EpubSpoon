package com.example.epubspoon.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.example.epubspoon.R
import com.example.epubspoon.model.BookData
import com.example.epubspoon.storage.StorageManager

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var storage: StorageManager

    private var md5: String = ""
    private var segments: List<String> = emptyList()
    private var currentIndex: Int = 0

    /**
     * 监听 SharedPreferences 变化，Activity 侧修改进度时实时同步到悬浮窗
     */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && md5.isNotBlank() && key == "progress_$md5") {
            val newIndex = storage.loadProgress(md5)
            if (newIndex != currentIndex && newIndex in segments.indices) {
                currentIndex = newIndex
                updateProgressText()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "epubspoon_floating"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        storage = StorageManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("EpubSpoon", "FloatingService onStartCommand called")
        // 启动前台通知
        try {
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("EpubSpoon")
                .setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("EpubSpoon", "Failed to start foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d("EpubSpoon", "Foreground started OK")

        // 读取数据
        md5 = intent?.getStringExtra("md5") ?: ""
        if (md5.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val bookData = storage.loadSegmentsCache(md5)
        if (bookData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        segments = bookData.segments
        currentIndex = storage.loadProgress(md5)

        // 创建悬浮窗
        Log.d("EpubSpoon", "About to setupFloatingView, segments=${segments.size}, index=$currentIndex")
        setupFloatingView()
        Log.d("EpubSpoon", "FloatingView added to WindowManager")

        // 监听进度变化（Activity 侧点击列表/搜索跳转时同步）
        storage.registerChangeListener(prefsListener)

        return START_NOT_STICKY
    }

    private fun setupFloatingView() {
        // 如果已有悬浮窗，先移除
        if (::floatingView.isInitialized) {
            try { windowManager.removeView(floatingView) } catch (_: Exception) {}
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_button, null)
        updateProgressText()

        // 获取屏幕宽度，默认放右上角
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - (72 * displayMetrics.density + 16 * displayMetrics.density).toInt()
            y = 300
        }

        // 拖拽 + 点击处理
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100) {
                        isDragging = true
                    }
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onFloatingClick()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, params)
    }

    private fun onFloatingClick() {
        if (segments.isEmpty()) return

        if (currentIndex >= segments.size) {
            Toast.makeText(this, "已是最后一段", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. 复制当前段到剪贴板（带序号）
        val text = "[${currentIndex + 1}]\n${segments[currentIndex]}"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("EpubSpoon", text))

        // 2. 视觉反馈：变绿 3 秒
        floatingView.setBackgroundResource(R.drawable.floating_button_bg_green)
        Handler(Looper.getMainLooper()).postDelayed({
            if (::floatingView.isInitialized) {
                floatingView.setBackgroundResource(R.drawable.floating_button_bg)
            }
        }, 3000)

        // 3. 震动反馈
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))

        // 4. 前进到下一段
        if (currentIndex < segments.size - 1) {
            currentIndex++
            storage.saveProgress(md5, currentIndex)
            updateProgressText()
        } else {
            Toast.makeText(this, "已是最后一段", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateProgressText() {
        val tv = floatingView.findViewById<TextView>(R.id.tvFloatingProgress)
        tv.text = "${currentIndex + 1}/${segments.size}"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "EpubSpoon 悬浮窗",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "悬浮窗运行通知"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        storage.unregisterChangeListener(prefsListener)
        if (::floatingView.isInitialized) {
            try { windowManager.removeView(floatingView) } catch (_: Exception) {}
        }
    }
}
