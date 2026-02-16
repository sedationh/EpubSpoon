# EpubSpoon 油猴插件版

> 在 Gemini 网页上直接导入 EPUB → 悬浮按钮点一下自动填充并发送，无需手动粘贴和点确定。

## 功能

- **导入 EPUB**：在 Gemini 页面直接导入 `.epub` 文件，浏览器端解析分段
- **悬浮按钮**：页面右上角蓝色圆形按钮，显示当前进度 `3/47`
- **一键发送**：点击悬浮按钮 → 自动填入当前段到 Gemini 输入框 → 自动点击发送 → 自动前进到下一段
- **母指令**：可编辑的 System Instruction，一键发送到 Gemini
- **进度持久化**：进度和分段结果保存在 `localStorage`，刷新页面不丢失
- **可拖拽**：悬浮按钮支持拖拽移动位置
- **智能分段**：与 Android 版完全一致的分段算法（约 300 词/段，不跨章节）

## 安装

### 前置条件

浏览器安装 [Tampermonkey](https://www.tampermonkey.net/) 扩展。

### 安装脚本

1. 打开 Tampermonkey → 管理面板 → 新建脚本
2. 清空默认内容，粘贴 [epubspoon.user.js](epubspoon.user.js) 的全部内容
3. `Ctrl+S` 保存

## 使用

1. 打开 [gemini.google.com](https://gemini.google.com/)
2. 页面右上角出现蓝色 📖 悬浮按钮
3. **首次使用**：点击按钮 → 打开面板 → 导入 EPUB 文件
4. （可选）点击「发送母指令到 Gemini」，让 Gemini 进入阅读助手模式
5. 点击悬浮按钮 → 自动填入当前段并发送 → 按钮变绿 3 秒反馈
6. 循环第 5 步，Gemini 自动分析每段内容

### 面板操作

- **右键**悬浮按钮打开管理面板
- 面板内可：跳转到指定段、上下翻段、预览当前段、清除书籍、重新导入

## 与 Android 版的区别

| 特性 | Android 版 | 油猴插件版 |
|---|---|---|
| 运行平台 | Android 手机 | 桌面浏览器 |
| EPUB 解析 | epublib + Jsoup | JSZip + DOMParser |
| 发送方式 | 复制到剪贴板，手动粘贴 | **自动填入 + 自动点发送** |
| 存储 | SharedPreferences + JSON 文件 | localStorage |
| 悬浮窗 | 系统级 Overlay | 网页内 fixed 定位 |

## 注意事项

- 仅在 `gemini.google.com` 上运行
- Gemini 页面结构可能更新，如发送按钮无法自动点击，插件会提示「已填入内容，请手动点击发送」
- 大型 EPUB 文件解析可能需要几秒，请耐心等待
- 数据保存在浏览器 localStorage 中，清除浏览器数据会丢失进度
