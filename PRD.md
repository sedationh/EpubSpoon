# EpubSpoon — MVP PRD

> 一句话：导入 EPUB 英文书 → 悬浮窗点一下复制一段 → 去 Gemini 粘贴看分析，像刷短视频一样读英文书。

---

## 0. 技术栈

| 项 | 选型 |
|---|---|
| 语言 | Kotlin |
| 最低版本 | minSdk 26 / targetSdk 34 |
| 架构 | 单 Activity + MVVM |
| EPUB 解析 | Epublib |
| HTML → 纯文本 | Jsoup |
| JSON 序列化 | Gson |
| 存储 | SharedPreferences（进度 + 分段缓存） |
| 权限 | `SYSTEM_ALERT_WINDOW`（悬浮窗） |

---

## 1. 核心流程

```
导入 EPUB → 解析 & 分段 → 选中书籍 → （可选）复制母指令到 Gemini 发送 → 悬浮窗自动出现 → 点一下复制+下一段 → 去 Gemini 粘贴
```

1. 用户在 App 里通过**系统文件选择器**导入 `.epub` 文件（可导入多本）。
2. App 解析 EPUB，提取纯文本，按智能分段算法切分，缓存结果。
3. 用户在书架中**选中要读的书**，进入该书的阅读页。
4. （可选）用户点击「复制母指令」按钮，将 System Instruction 复制到剪贴板，手动去 Gemini 粘贴发送。
5. 选中书籍后悬浮窗**自动出现**，用户点击悬浮窗：**复制当前段 + 自动前进到下一段**。
6. 用户切到 Gemini 粘贴，即可看到分析结果。
7. 循环 5-6，直到读完。

---

## 2. 母指令模板（默认，用户可编辑）

```text
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
```

---

## 3. 智能分段算法

基于**句子边界**和**字数权重**，每段约 300 词（可配置）。

```kotlin
fun getSmartSegments(fullText: String, targetWords: Int = 300): List<String> {
    // 用更健壮的正则：句号/问号/叹号后跟空格+大写字母才算断句
    // 避免 Mr. Dr. U.S. 等缩写误断
    val sentences = fullText.split(Regex("(?<=[.!?])\\s+(?=[A-Z])"))
    val result = mutableListOf<String>()
    val currentBatch = StringBuilder()
    var currentCount = 0

    for (sentence in sentences) {
        val wordCount = sentence.split(Regex("\\s+")).size

        if (currentCount + wordCount > targetWords && currentCount > 0) {
            result.add(currentBatch.toString().trim())
            currentBatch.setLength(0)
            currentCount = 0
        }

        currentBatch.append(sentence).append(" ")
        currentCount += wordCount
    }

    if (currentBatch.isNotEmpty()) {
        result.add(currentBatch.toString().trim())
    }
    return result
}
```

**EPUB 解析注意事项：**
- 用 Epublib 逐章提取 HTML，用 `Jsoup` 取纯文本（自动过滤图片/表格/标签）
- **保留章节边界**：不跨章节拼接分段
- 空章节 / 目录页 / 版权页等直接跳过

---

## 4. 悬浮窗

### 4.1 外观
- 一个小圆形按钮，可拖拽，悬浮在任意界面上方
- 显示文字：`3/47`（当前段序号 / 总段数）
- 大小约 56dp，半透明背景

### 4.2 交互
| 操作 | 行为 |
|---|---|
| **单击** | 复制当前段文本到剪贴板 → `currentIndex++` → 更新显示 → 短震动反馈 |
| **到达最后一段** | 单击后 Toast 提示「已到最后一段」，不再前进 |

### 4.3 权限引导
首次开启悬浮窗时，检查 `Settings.canDrawOverlays()`，若无权限则弹 Dialog 引导用户跳转系统设置页授权。

---

## 5. 存储管理

### 5.1 进度存储（SharedPreferences）

```
Key:   "progress_{file_md5}"
Value: currentIndex (Int)
```

- 每次悬浮窗点击后立即持久化
- App 重启 / 切换书籍时自动恢复

### 5.2 分段缓存（内部存储 JSON 文件）

解析 + 分段只在**首次导入时执行一次**，结果以 JSON 文件缓存到 App 内部存储：

```
路径：  /data/data/{pkg}/files/segments_{file_md5}.json
内容：  { "bookTitle": "...", "segments": ["段落1", "段落2", ...] }
```

- 打开 App 时先查缓存，命中则直接加载，**无需重新解析**
- 重新导入同一本书时，MD5 相同则复用缓存
- 导入新书时才触发解析

### 5.3 性能考虑

- MD5 计算 + EPUB 解析 + 分段均在**后台协程**执行，主线程只做 UI 更新
- 解析过程中主界面显示 loading 状态

---

## 6. 主界面（单页面）

从上到下：

1. **书籍区域**
   - 无书时：一个大的「+ 导入 EPUB」按钮
   - 有书时：显示书名、进度（`3/47`）、「重新导入」按钮
2. **母指令区域**
   - 一个可折叠的 `EditText`，预填默认母指令模板
   - 「复制母指令」按钮（复制到剪贴板，Toast 提示"已复制"）
3. **分段列表**
   - `RecyclerView`，每项显示序号 + 前 50 字预览
   - 当前段高亮显示
   - 点击某项 → 更新 `currentIndex`，可从任意位置继续
4. **悬浮窗**
   - 选中书籍后自动开启悬浮窗 Service，无需手动操作
   - App 退出或用户在主界面点击「关闭悬浮窗」时关闭

---

## 7. 错误处理

| 场景 | 处理 |
|---|---|
| 文件不是 EPUB / 解析失败 | Toast「无法解析此文件，请确认是 .epub 格式」 |
| EPUB 内无文本内容 | Toast「此书无文本内容」 |
| 悬浮窗权限未授权 | Dialog 引导跳转设置 |
| 已到最后一段仍点击 | Toast「已是最后一段」 |

---

## 8. 开发清单

1. **[ ] 解析层**：Epublib + Jsoup 提取纯文本，`getSmartSegments` 分段，保留章节边界
2. **[ ] 存储层**：SharedPreferences 存进度 + JSON 文件缓存分段结果，MD5 做 Key
3. **[ ] UI 层**：单 Activity — 导入按钮、母指令编辑区、分段列表、悬浮窗开关
4. **[ ] 服务层**：悬浮窗 `Service`，单击复制+前进+震动反馈+持久化进度
5. **[ ] 权限处理**：`SYSTEM_ALERT_WINDOW` 检查与引导
