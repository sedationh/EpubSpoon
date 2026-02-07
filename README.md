# 🥄 EpubSpoon

> 导入 EPUB 英文书 → 悬浮窗点一下复制一段 → 去 Gemini 粘贴看分析，像刷短视频一样读英文书。

## 它解决什么问题

读英文原版书的时候，遇到不懂的句子想让 AI 帮忙分析，但来回切换 app、复制粘贴太麻烦。

EpubSpoon 把这个流程简化到极致：**点一下悬浮窗按钮 → 自动复制当前段落 → 切到 Gemini 粘贴**，就这么简单。

## 功能

- 📖 **导入 EPUB** — 通过系统文件选择器导入 `.epub` 英文书
- 🔪 **智能分段** — 自动将书籍内容按段落切分，每段适合 AI 分析的长度
- 🫧 **悬浮窗** — 浮在任何 app 上方的小按钮，显示当前进度（如 `42/386`）
- 👆 **一键复制** — 点击悬浮窗：复制当前段 + 震动反馈 + 自动前进到下一段
- 📋 **母指令** — 内置优化过的 System Instruction，复制给 Gemini 即可开始
- 🔍 **搜索跳转** — 输入序号直接跳转，或关键词搜索定位段落
- 💾 **进度记忆** — 阅读进度自动保存，下次打开继续

## 截图

<!-- TODO: 添加截图 -->

## 使用流程

1. 打开 EpubSpoon，点击「导入 EPUB」选择书籍
2. 点击「复制母指令」，粘贴到 Gemini 对话中发送
3. 点击「启动悬浮窗」，授予悬浮窗权限
4. 切到 Gemini，点击悬浮窗按钮 → 自动复制当前段
5. 在 Gemini 输入框长按粘贴 → 发送 → 看 AI 逐句翻译 + 词汇解析 + 文化背景
6. 继续点悬浮窗，下一段，循环

## 母指令特色

内置的 System Instruction 会让 AI 做到：

- **逐句翻译** — 英文原文 + 中文翻译，一句一句来
- **内嵌词汇注释** — 每句翻译后紧跟重点词/词组的释义和用法
- **💡 文化解析** — 涉及西方文化、历史典故、宗教神话、文学修辞时，额外补充背景知识
- **段落总结** — 最后用中文概括段落大意

## 技术栈

| 项 | 选型 |
|---|---|
| 语言 | Kotlin |
| 最低版本 | minSdk 26 (Android 8.0) |
| 目标版本 | targetSdk 35 |
| 架构 | 单 Activity + MVVM (StateFlow) |
| EPUB 解析 | epublib-core 3.1 |
| HTML → 纯文本 | Jsoup 1.17.2 |
| JSON 序列化 | Gson 2.10.1 |
| 存储 | SharedPreferences + JSON 文件缓存 |
| CI/CD | GitHub Actions |

## 构建

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease
```

APK 输出在 `app/build/outputs/apk/` 目录。

## 发布

推送 `v*` 格式的 tag 会自动触发 GitHub Actions 构建并创建 Release：

```bash
git tag v1.0.0
git push origin v1.0.0
```

## 权限说明

| 权限 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | 在其他 app 上方显示悬浮窗按钮 |
| `FOREGROUND_SERVICE` | 保持悬浮窗服务运行 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ 前台服务类型声明 |
| `POST_NOTIFICATIONS` | Android 13+ 显示前台服务通知 |
| `VIBRATE` | 点击悬浮窗时的震动反馈 |

## License

MIT
