# EpubSpoon — AI 协作指南

> 本文件供 AI 编码助手（Copilot / Claude / Cursor 等）在对话中自动读取，确保所有修改符合项目约定。

---

## 项目概况

EpubSpoon 是一个 Android 应用，核心功能：**导入 EPUB 英文书 → 悬浮窗点一下复制一段 → 去 Gemini 粘贴看 AI 分析**。

- **仓库**：`https://github.com/sedationh/EpubSpoon`
- **单人项目**，开发者同时是唯一用户

---

## 技术栈 & 约定

| 项 | 选型 | 注意 |
|---|---|---|
| 语言 | **Kotlin** | 不用 Java |
| 最低版本 | minSdk 26 / targetSdk 35 | |
| 架构 | 单 Activity + **MVVM** | `MainActivity` + `MainViewModel` |
| 状态管理 | **StateFlow** + `UiState` sealed interface | 不用 LiveData |
| 视图绑定 | **ViewBinding** | 不用 DataBinding，不用 Compose |
| 协程 | `lifecycleScope.launch` / `viewModelScope.launch` | |
| EPUB 解析 | epublib-core + Jsoup | |
| JSON | Gson | |
| 存储 | SharedPreferences（进度）+ JSON 文件（分段缓存）| 通过 `StorageManager` 统一管理 |
| 网络 | `HttpURLConnection`（仅更新检查）| 零额外依赖，不用 OkHttp/Retrofit |
| CI/CD | GitHub Actions | tag 触发，见 `.github/workflows/release.yml` |

---

## 项目结构

```
app/src/main/java/com/example/epubspoon/
├── model/          # 数据模型 (BookData)
├── parser/         # EPUB 解析 (EpubParser, SegmentHelper)
├── service/        # 悬浮窗前台服务 (FloatingService)
├── storage/        # 持久化 (StorageManager)
├── ui/             # Activity + Adapter (MainActivity, SegmentAdapter)
├── updater/        # 更新检查 (UpdateChecker)
└── viewmodel/      # ViewModel + UiState
```

---

## 版本管理

- **唯一来源**：git tag（如 `v1.2.3`）
- `build.gradle.kts` 中 `gitVersionName()` 和 `gitVersionCode()` 从 `git describe --tags` 自动读取
- 发版流程：`git tag v1.x.x && git push origin v1.x.x` → CI 自动构建 + 发布 Release
- **版本号语义**：
  - patch（`v1.1.x`）：bug 修复、小优化、UI 微调
  - minor（`v1.x.0`）：新功能
  - major（`vx.0.0`）：重大变更

---

## APK 签名

- release 构建使用 **debug.keystore**（显式配置在 `signingConfigs` 中）
- CI 上通过 GitHub Secret `DEBUG_KEYSTORE`（base64）解码到 `~/.android/debug.keystore`
- ⚠️ **绝对不要**改动签名配置，否则用户无法覆盖安装

---

## 编码规范

1. **中文注释**：代码注释、commit message 的描述部分用中文，commit title 用英文
2. **按钮文案**：使用简短中文（如"详细"、"搜索"、"更多"）
3. **Toast 提示**：中文
4. **新文件命名**：遵循现有 PascalCase 类名、snake_case 资源名
5. **布局**：XML 布局，使用 MaterialComponents 风格按钮
6. **不引入新依赖**：除非确实必要，优先用标准库/Android SDK 实现

---

## 关键设计决策（不要随意更改）

1. **悬浮窗是前台服务**（`FloatingService`），需要 `FOREGROUND_SERVICE_SPECIAL_USE`
2. **进度通过 SharedPreferences 双向同步**：悬浮窗写入 → ViewModel 通过 `OnSharedPreferenceChangeListener` 实时感知
3. **分段缓存用 MD5 做 key**：同一本书重复导入不会重新解析
4. **更新检查走 GitHub Releases API**：公开接口，无需 token，60 次/小时限制
5. **悬浮窗点击复制格式**：`[序号]\n内容`，序号从 1 开始
6. **悬浮窗点击反馈**：变绿 3 秒 + 震动

---

## 常见操作 Cheat Sheet

```bash
# 本地构建
./gradlew assembleDebug
./gradlew assembleRelease

# 发版（CI 自动构建 + 发布）
git tag v1.x.x
git push origin v1.x.x

# 如需重新发版（删旧 tag + release）
git tag -d v1.x.x
git push origin :refs/tags/v1.x.x
gh release delete v1.x.x -y
git tag v1.x.x
git push origin v1.x.x

# 需要代理时
export all_proxy=http://127.0.0.1:7890
```

---

## 参考文档

- [PRD.md](PRD.md) — 产品需求文档
- [TASK.md](TASK.md) — 开发任务清单
- [FLOATING_WINDOW_PITFALLS.md](FLOATING_WINDOW_PITFALLS.md) — 悬浮窗踩坑记录
- [docs/UPDATE_MECHANISM.md](docs/UPDATE_MECHANISM.md) — 更新机制详解
