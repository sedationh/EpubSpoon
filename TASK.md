# EpubSpoon — 开发任务清单

> 基于 [PRD.md](PRD.md) 拆解，按开发顺序排列。

---

## Task 1: 初始化 Android 项目

**状态：** ✅ 已完成

**目标：** 搭建项目骨架，所有依赖就位。

**具体内容：**
- 创建 Android 项目，包名 `com.example.epubspoon`
- Kotlin，minSdk 26 / targetSdk 34
- 单 Activity 架构（`MainActivity`）
- `build.gradle` 添加依赖：
  - `nl.siegmann.epublib:epublib-core` — EPUB 解析
  - `org.jsoup:jsoup` — HTML → 纯文本
  - `com.google.code.gson:gson` — JSON 序列化
  - `androidx.lifecycle:lifecycle-viewmodel-ktx` — ViewModel + 协程
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android` — 协程
- AndroidManifest 声明 `SYSTEM_ALERT_WINDOW` 权限

**产出文件：**
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/epubspoon/MainActivity.kt`（空壳）

---

## Task 2: EPUB 解析 + 智能分段

**状态：** ✅ 已完成  
**依赖：** Task 1

**目标：** 输入 EPUB 文件 InputStream，输出分段后的 `List<String>`。

**具体内容：**
- `EpubParser.kt`
  - 用 Epublib 读取 EPUB，逐章提取 Spine 中的 HTML
  - Jsoup 转纯文本，自动过滤 `<img>`/`<table>`/`<svg>` 等非文本标签
  - 跳过空章节、目录页（TOC）、版权页
  - 返回 `List<String>`（每项为一章的纯文本）
- `SegmentHelper.kt`
  - `getSmartSegments(chapterTexts: List<String>, targetWords: Int = 300): List<String>`
  - 逐章分段，**不跨章节拼接**
  - 正则 `(?<=[.!?])\s+(?=[A-Z])` 断句，避免 Mr. Dr. U.S. 误断
  - 每段约 300 词，不足 300 词的章节尾部单独成段

**产出文件：**
- `app/src/main/java/com/example/epubspoon/parser/EpubParser.kt`
- `app/src/main/java/com/example/epubspoon/parser/SegmentHelper.kt`

---

## Task 3: 存储层 — 进度 + 分段缓存

**状态：** ✅ 已完成  
**依赖：** Task 2

**目标：** 解析结果缓存到本地，进度持久化，再次打开秒加载。

**具体内容：**
- `StorageManager.kt`
  - **进度存储**：SharedPreferences，Key = `progress_{file_md5}`，Value = `currentIndex: Int`
  - **分段缓存**：JSON 文件存到 App 内部存储
    - 路径：`/data/data/{pkg}/files/segments_{file_md5}.json`
    - 结构：`{ "bookTitle": "...", "segments": ["段落1", "段落2", ...] }`
  - **MD5 工具**：`fun calcMd5(inputStream: InputStream): String`（挂起函数，Dispatchers.IO）
  - **缓存查询**：导入时先查 JSON 缓存，MD5 命中直接加载，跳过解析
- `BookData.kt`（数据类）
  - `data class BookData(val bookTitle: String, val segments: List<String>)`

**产出文件：**
- `app/src/main/java/com/example/epubspoon/storage/StorageManager.kt`
- `app/src/main/java/com/example/epubspoon/model/BookData.kt`

---

## Task 4: ViewModel + 数据流

**状态：** ✅ 已完成  
**依赖：** Task 2, Task 3

**目标：** 串联解析层和存储层，向 UI 暴露响应式状态。

**具体内容：**
- `MainViewModel.kt`
  - **状态**：`StateFlow<UiState>`（Idle / Loading / Success(bookData, currentIndex) / Error(msg)）
  - **方法**：
    - `importBook(uri: Uri)` — 读取文件 → 算 MD5 → 查缓存 → 缓存未命中则解析+分段+存缓存 → 更新状态
    - `selectSegment(index: Int)` — 更新 currentIndex，持久化
    - `getNextSegment(): String?` — 返回当前段文本，index++，持久化
    - `getCurrentProgress(): Pair<Int, Int>` — (currentIndex, totalSegments)
  - 所有耗时操作在 `viewModelScope.launch(Dispatchers.IO)` 中执行

**产出文件：**
- `app/src/main/java/com/example/epubspoon/viewmodel/MainViewModel.kt`
- `app/src/main/java/com/example/epubspoon/viewmodel/UiState.kt`

---

## Task 5: 主界面 UI

**状态：** ✅ 已完成  
**依赖：** Task 4

**目标：** 单页面实现所有交互。

**具体内容：**
- `activity_main.xml` 布局，从上到下：
  1. **书籍区域**：无书 → 「+ 导入 EPUB」大按钮；有书 → 书名 + 进度 `3/47` + 「重新导入」
  2. **母指令区域**：可折叠 `EditText` 预填默认模板 + 「复制母指令」按钮
  3. **分段列表**：`RecyclerView`，每项 = 序号 + 前 50 字预览，当前段高亮，点击跳转进度
  4. **关闭悬浮窗**按钮
- `MainActivity.kt`：
  - `registerForActivityResult(OpenDocument)` 导入 .epub
  - 观察 ViewModel 状态更新 UI
  - 复制母指令到剪贴板（ClipboardManager）
  - 点击分段项 → `viewModel.selectSegment(index)`
- `SegmentAdapter.kt`：RecyclerView Adapter

**产出文件：**
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/item_segment.xml`
- `app/src/main/java/com/example/epubspoon/ui/MainActivity.kt`
- `app/src/main/java/com/example/epubspoon/ui/SegmentAdapter.kt`

---

## Task 6: 悬浮窗 Service

**状态：** ✅ 已完成  
**依赖：** Task 3, Task 4

**目标：** 悬浮按钮，单击复制+前进，选书后自动启动。

**具体内容：**
- `FloatingService.kt`（前台 Service）：
  - WindowManager 添加悬浮 View（56dp 圆形，半透明背景）
  - 显示文字 `currentIndex/totalSegments`
  - **单击**：复制当前段 → currentIndex++ → 更新显示 → 短震动 → 持久化进度
  - **最后一段**：Toast「已是最后一段」，不再前进
  - **可拖拽**：onTouchListener 处理 ACTION_MOVE
  - 前台通知 Channel（Android 8+ 必需）
- **生命周期**：
  - 选中书籍时 `startForegroundService()` 自动启动
  - 主界面「关闭悬浮窗」按钮 或 App 退出时 `stopService()`
- Service 通过 Intent extras 接收 segments 数据和 currentIndex

**产出文件：**
- `app/src/main/java/com/example/epubspoon/service/FloatingService.kt`
- `app/src/main/res/layout/layout_floating_button.xml`

---

## Task 7: 权限处理

**状态：** ✅ 已完成（已在 Task 5 MainActivity 中实现）  
**依赖：** Task 6

**目标：** 悬浮窗权限检查与引导，流畅不卡。

**具体内容：**
- 启动悬浮窗前检查 `Settings.canDrawOverlays(context)`
- 无权限 → 弹 AlertDialog：
  - 标题：「需要悬浮窗权限」
  - 内容：「EpubSpoon 需要在其他应用上方显示悬浮按钮，请在设置中开启」
  - 确定 → `startActivity(ACTION_MANAGE_OVERLAY_PERMISSION)`
  - 取消 → 不开启悬浮窗
- 从设置页返回后重新检查权限，有权限则自动启动 Service

**产出文件：**
- 逻辑写在 `MainActivity.kt` 中（或抽 `PermissionHelper.kt`）

---

## Task 8: 错误处理 + 收尾

**状态：** ✅ 已完成  
**依赖：** Task 1-7

**目标：** 补全异常路径，确保不崩溃，可以跑通完整流程。

**具体内容：**
- 错误提示：
  - 非 EPUB / 解析失败 → Toast「无法解析此文件，请确认是 .epub 格式」
  - 无文本内容 → Toast「此书无文本内容」
  - 已到最后一段 → Toast「已是最后一段」
- AndroidManifest 检查：
  - `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />`
  - `<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />`
  - `<service android:name=".service.FloatingService" ... />`
  - 通知 Channel 初始化
- 整体联调：导入 → 解析 → 选书 → 母指令 → 悬浮窗 → 复制 → 进度恢复
- 代码清理 & 注释

**产出文件：**
- 修改已有文件，无新文件

---

## Task 9: 按章节复制功能

**状态：** ✅ 已完成
**依赖：** Task 1-8

**目标：** 在主界面增加「章节」Tab，支持按整章复制内容，适用于长文不需要分段复制的场景。

**具体内容：**
- 新增 **TabLayout**（分段 / 章节 两个 Tab）切换两种浏览模式
- **分段 Tab**（原有功能）：小段列表 + 悬浮窗逐段复制，适合短内容逐段喂给 AI
- **章节 Tab**（新功能）：展示 EPUB 原始章节列表，每项显示章节序号、内容预览（前 120 字）、约词数
  - 点击或点「复制」按钮 → 整章复制到剪贴板
  - 复制后绿色高亮 3 秒反馈
  - 章节模式下隐藏悬浮窗按钮、搜索栏、详细/省略等分段专属控件
- `BookData` 新增 `chapters: List<String>?` 字段，存储 EpubParser 解析出的原始章节文本
  - 使用 nullable 类型兼容旧版缓存（Gson 反序列化不走 Kotlin 默认值）
- 新增 `ChapterAdapter` + `item_chapter.xml` 布局

**产出文件：**
- `app/src/main/java/com/example/epubspoon/ui/ChapterAdapter.kt`（新增）
- `app/src/main/res/layout/item_chapter.xml`（新增）
- `app/src/main/res/layout/activity_main.xml`（修改：加 TabLayout + rvChapters）
- `app/src/main/java/com/example/epubspoon/model/BookData.kt`（修改：加 chapters 字段）
- `app/src/main/java/com/example/epubspoon/viewmodel/MainViewModel.kt`（修改：解析时保存 chapters）
- `app/src/main/java/com/example/epubspoon/ui/MainActivity.kt`（修改：Tab 切换 + 章节复制逻辑）
