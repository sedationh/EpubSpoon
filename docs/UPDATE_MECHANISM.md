# Android 应用手动更新机制（基于 GitHub Releases）

> 适用于个人/小团队项目，不上 Google Play，通过 GitHub Releases 分发 APK 的场景。

---

## 架构概览

```
打 tag (v1.2.0)
  ↓
CI 触发 → Gradle 从 git tag 读取版本号 → 构建 APK → 发布到 GitHub Release
  ↓
用户点击「检查更新」→ 请求 GitHub API → 对比版本号 → 弹窗 → 浏览器下载 APK
```

**核心思路**：版本号由 git tag 作为唯一来源，贯穿 Gradle 构建、APK 内嵌版本、客户端更新检查全链路。

---

## 1. 版本号统一管理（git tag → Gradle）

### app/build.gradle.kts

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 从 git tag 提取版本号。
 * CI 由 tag 触发（如 v1.0.0），`git describe --tags --abbrev=0` 返回 "v1.0.0"
 * 本地开发如果没有 tag 就 fallback 到 "dev"
 */
fun gitVersionName(): String {
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val tag = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (process.exitValue() == 0 && tag.isNotEmpty()) tag.removePrefix("v") else "dev"
    } catch (_: Exception) {
        "dev"
    }
}

/**
 * 从版本字符串生成 versionCode（Google Play 要求递增整数）。
 * "1.0.0" → 10000, "1.2.3" → 10203, "dev" → 1
 */
fun gitVersionCode(): Int {
    val name = gitVersionName()
    if (name == "dev") return 1
    val parts = name.split(".").map { it.toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 10000 +
            parts.getOrElse(1) { 0 } * 100 +
            parts.getOrElse(2) { 0 }
}

android {
    defaultConfig {
        versionCode = gitVersionCode()
        versionName = gitVersionName()
    }

    buildFeatures {
        buildConfig = true  // 必须开启，才能在代码中访问 BuildConfig.VERSION_NAME
    }
}
```

**要点**：
- `buildConfig = true` 必须显式开启（AGP 8.0+ 默认关闭）
- `rootDir` 确保在项目根目录执行 git 命令
- fallback 到 `"dev"` 避免无 tag 时构建失败

### versionCode 映射规则

| versionName | versionCode |
|-------------|-------------|
| 1.0.0       | 10000       |
| 1.2.3       | 10203       |
| 2.0.0       | 20000       |
| dev         | 1           |

每位最多支持 99（如 `major.minor.patch`，major×10000 + minor×100 + patch）。

---

## 2. CI 发布流水线（GitHub Actions）

### .github/workflows/release.yml

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'  # 推送 v1.0.0, v1.2.3 等 tag 时触发

jobs:
  release:
    runs-on: ubuntu-latest
    permissions:
      contents: write  # 需要写权限来创建 Release

    steps:
      - name: Checkout
        uses: actions/checkout@v4
        # 注意：默认 checkout 会拿到 tag，git describe 可以正常工作

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Extract version from tag
        id: version
        run: echo "VERSION=${GITHUB_REF#refs/tags/v}" >> $GITHUB_OUTPUT

      # 解码签名 keystore（从 GitHub Secret 中读取 base64 编码的 keystore 文件）
      - name: Decode debug keystore
        run: |
          mkdir -p $HOME/.android
          printf '%s' "${{ secrets.DEBUG_KEYSTORE }}" | base64 -d > $HOME/.android/debug.keystore

      # 验证 keystore 正确性（可选但强烈建议，防止签名不一致的问题）
      - name: Verify debug keystore
        run: |
          keytool -list -keystore $HOME/.android/debug.keystore -storepass android -alias androiddebugkey 2>&1
          echo "Keystore size: $(wc -c < $HOME/.android/debug.keystore) bytes"

      # 不需要 sed 替换 versionName —— Gradle 会自动从 git tag 读取
      - name: Build Release APK
        run: ./gradlew assembleRelease

      - name: Rename APK
        run: |
          APK=$(find app/build/outputs/apk/release -name '*.apk' | head -1)
          mv "$APK" app/build/outputs/apk/release/YourApp-${{ steps.version.outputs.VERSION }}.apk

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          name: YourApp v${{ steps.version.outputs.VERSION }}
          body: |
            ## 更新内容
            ...
          files: |
            app/build/outputs/apk/release/YourApp-${{ steps.version.outputs.VERSION }}.apk
          draft: false
          prerelease: false
```

**要点**：
- `actions/checkout@v4` 默认会 fetch tag，所以 `git describe --tags` 能正常工作
- 不需要 `sed` 替换 `versionName`，Gradle 自己读 tag
- `softprops/action-gh-release@v2` 自动创建 Release 并上传 APK
- ⚠️ keystore 解码必须用 `printf '%s'`，**不要用 `echo`**（见下方踩坑记录）
- 建议加 `Verify debug keystore` 步骤，CI 日志可以确认指纹是否匹配

---

## 3. 客户端更新检查

### 前置条件

**AndroidManifest.xml** 添加网络权限：
```xml
<uses-permission android:name="android.permission.INTERNET" />
```

**依赖**（使用项目已有的 Gson，不需要额外引入 HTTP 库）：
```kotlin
implementation("com.google.code.gson:gson:2.10.1")
```

如果项目没有 Gson，也可以用 `org.json.JSONObject`（Android 自带），完全零依赖。

### UpdateChecker.kt

```kotlin
package com.example.yourapp.updater

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val TAG = "UpdateChecker"

    // ⚠️ 替换成你的仓库地址
    private const val API_URL =
        "https://api.github.com/repos/你的用户名/你的仓库名/releases/latest"

    data class UpdateResult(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val htmlUrl: String
    )

    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("name") val name: String?,
        @SerializedName("body") val body: String?,
        @SerializedName("html_url") val htmlUrl: String,
        @SerializedName("assets") val assets: List<GitHubAsset>?
    )

    data class GitHubAsset(
        @SerializedName("name") val name: String,
        @SerializedName("browser_download_url") val browserDownloadUrl: String,
        @SerializedName("content_type") val contentType: String?
    )

    suspend fun check(currentVersion: String): UpdateResult? = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            if (connection.responseCode != 200) {
                Log.w(TAG, "GitHub API returned ${connection.responseCode}")
                return@withContext null
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val release = Gson().fromJson(json, GitHubRelease::class.java)
            val latestVersion = release.tagName.removePrefix("v")

            val apkAsset = release.assets?.firstOrNull { asset ->
                asset.name.endsWith(".apk", ignoreCase = true)
            }

            UpdateResult(
                hasUpdate = isNewerVersion(latestVersion, currentVersion),
                latestVersion = latestVersion,
                currentVersion = currentVersion,
                releaseNotes = release.body ?: release.name ?: "",
                downloadUrl = apkAsset?.browserDownloadUrl ?: release.htmlUrl,
                htmlUrl = release.htmlUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            null
        }
    }

    /**
     * 语义化版本比较：支持 "1.0", "1.0.1", "2.0" 等格式。
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
```

### Activity 中调用

```kotlin
import com.example.yourapp.BuildConfig
import com.example.yourapp.updater.UpdateChecker

// 触发检查（按钮点击时调用）
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
                .setMessage(buildString {
                    append("当前版本：v${result.currentVersion}\n")
                    append("最新版本：v${result.latestVersion}\n\n")
                    if (result.releaseNotes.isNotBlank()) {
                        append("更新说明：\n${result.releaseNotes}")
                    }
                })
                .setPositiveButton("下载更新") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.downloadUrl)))
                }
                .setNegativeButton("稍后再说", null)
                .show()
        } else {
            Toast.makeText(this@MainActivity, "当前已是最新版本", Toast.LENGTH_SHORT).show()
        }
    }
}
```

### 布局（Toolbar 中添加版本号 + 更新按钮）

```xml
<androidx.appcompat.widget.Toolbar ...>
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="horizontal"
        android:gravity="center_vertical">

        <TextView
            android:layout_width="0dp"
            android:layout_weight="1"
            android:layout_height="wrap_content"
            android:text="YourApp"
            android:textColor="#FFFFFFFF"
            android:textSize="20sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvVersion"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="#99FFFFFF"
            android:textSize="12sp"
            android:layout_marginEnd="12dp" />

        <ImageButton
            android:id="@+id/btnCheckUpdate"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:layout_marginEnd="8dp"
            android:src="@android:drawable/stat_sys_download"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="检查更新" />
    </LinearLayout>
</androidx.appcompat.widget.Toolbar>
```

---

## 4. APK 签名一致性（⚠️ 重要踩坑记录）

Android 覆盖安装要求 **新旧 APK 签名完全一致**，否则会提示「安装包签名不正确」，必须卸载旧版才能装新版。

对个人项目来说，最简单的方案是 release 构建直接用 debug.keystore 签名，但要确保 **本地和 CI 用的是同一个 keystore 文件**。

### 4.1 在 build.gradle.kts 中显式指定 keystore 路径

```kotlin
android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
```

> ⚠️ **必须显式指定 `storeFile` 路径！** 如果只写 `signingConfigs.getByName("debug")`，Gradle 会自己查找 debug.keystore，但在 CI 上它可能找到 Gradle 缓存中自动生成的 keystore（`setup-gradle` action 会缓存 `~/.gradle`），而不是你放在 `~/.android/debug.keystore` 的那个。

### 4.2 将本地 keystore 上传到 GitHub Secrets

```bash
# macOS 上编码并上传（注意 tr -d '\n' 去掉换行）
base64 -i ~/.android/debug.keystore | tr -d '\n' | gh secret set DEBUG_KEYSTORE -R 你的用户名/你的仓库名
```

### 4.3 CI 中解码 keystore

```yaml
- name: Decode debug keystore
  run: |
    mkdir -p $HOME/.android
    printf '%s' "${{ secrets.DEBUG_KEYSTORE }}" | base64 -d > $HOME/.android/debug.keystore
```

### 4.4 踩坑记录

这个问题我们调了很久，踩了 **三个坑**，每个都可能独立导致签名不一致：

#### 坑 1：`echo` vs `printf` 解码 base64

```yaml
# ❌ 错误：echo 可能解释特殊字符，或在某些 shell 中添加额外换行
echo "${{ secrets.DEBUG_KEYSTORE }}" | base64 --decode > keystore

# ✅ 正确：printf '%s' 精确输出原始字符串
printf '%s' "${{ secrets.DEBUG_KEYSTORE }}" | base64 -d > keystore
```

`echo` 的行为在不同 shell 中不一致，面对 base64 中可能出现的 `\n`、`\t` 等字符时可能做转义处理，导致解码出来的 keystore 文件损坏。

#### 坑 2：Gradle 不一定用你放的 keystore

即使你把 keystore 正确放到了 `~/.android/debug.keystore`，如果 `build.gradle.kts` 中只写了：

```kotlin
// ❌ 隐式引用，Gradle 自己决定用哪个 keystore
signingConfig = signingConfigs.getByName("debug")
```

Gradle 的 `debug` signingConfig 默认会查找 debug keystore，但 `gradle/actions/setup-gradle@v4` 这个 action 会缓存 `~/.gradle` 目录。如果缓存中已经有一个之前自动生成的 debug.keystore（路径可能在 `~/.gradle/...` 下），Gradle 可能优先使用它。

**解决**：在 `signingConfigs` 中显式写 `storeFile = file("$HOME/.android/debug.keystore")`。

#### 坑 3：`mkdir -p` 别忘了

CI 的 Ubuntu runner 上 `~/.android/` 目录不存在，直接写文件会报错，但 CI 步骤如果用了 `set +e` 或者 shell 没有 `errexit`，可能会静默失败，然后 Gradle 继续用自动生成的 keystore 构建，不会报错但签名不一致。

```yaml
# ✅ 先创建目录
mkdir -p $HOME/.android
```

### 4.5 如何验证签名一致

```bash
# 本地 keystore 指纹
keytool -list -keystore ~/.android/debug.keystore -storepass android -alias androiddebugkey

# CI 构建的 APK 签名指纹（需要 Android SDK build-tools）
apksigner verify --print-certs your-app.apk

# 两个 SHA-256 指纹必须完全一致！
```

注意：`keytool -printcert -jarfile xxx.apk` 对 APK Signing Scheme v2/v3 签名的 APK 会显示 `Not a signed jar file`，需要用 `apksigner` 工具。

### 4.6 已经签名不一致了怎么办

Android 系统层面**无法绕过签名校验**，这是安全机制。用户必须：
1. 卸载旧版本
2. 安装新版本
3. 之后的版本都能正常覆盖更新（因为签名已统一）

建议在更新对话框中加一行提示：
```
⚠️ 如安装时提示「签名不一致」，请先卸载旧版本再安装。
```

---

## 5. 发版 Checklist

发布新版本只需要两步：

```bash
# 1. 确保代码已 push 到 main
git push origin main

# 2. 打 tag 并推送（CI 自动完成剩余工作）
git tag v1.2.0
git push origin v1.2.0
```

CI 自动完成：
- ✅ Gradle 从 tag 读取 `versionName = "1.2.0"`, `versionCode = 10200`
- ✅ 构建 Release APK
- ✅ 创建 GitHub Release，上传 `YourApp-1.2.0.apk`
- ✅ 用户在 app 内点「检查更新」即可发现新版本

---

## 6. 移植到新项目的步骤

1. **build.gradle.kts**：复制 `gitVersionName()` 和 `gitVersionCode()` 函数，替换 `versionName`/`versionCode`，开启 `buildConfig = true`
2. **build.gradle.kts**：在 `signingConfigs` 中显式指定 `storeFile` 路径（见第 4 节）
3. **AndroidManifest.xml**：添加 `<uses-permission android:name="android.permission.INTERNET" />`
4. **UpdateChecker.kt**：复制文件，修改 `API_URL` 中的仓库地址
5. **Activity**：添加更新按钮 + 调用 `checkForUpdate()`
6. **CI**：复制 `release.yml`，修改 APK 名称，**上传本地 keystore 到 GitHub Secrets**（见第 4.2 节）

---

## 7. 注意事项

| 项目 | 说明 |
|------|------|
| **公开仓库** | GitHub API 无需 token，匿名请求限制 60 次/小时/IP，个人项目完全够用 |
| **私有仓库** | 需要在请求头加 `Authorization: token <PAT>`，或用别的方式分发 |
| **API 限流** | 如果担心限流，可在客户端加 24 小时缓存，避免频繁请求 |
| **下载方式** | 当前实现是跳转浏览器下载，如需应用内下载+安装需要 `REQUEST_INSTALL_PACKAGES` 权限和 FileProvider 配置 |
| **Gson 可替换** | 如果不想引入 Gson，用 Android 自带的 `org.json.JSONObject` 手动解析即可 |
| **签名** | 新旧 APK 签名必须一致，否则无法覆盖安装。**详见第 4 节踩坑记录** |

---

## 8. 零依赖版本（不用 Gson）

如果新项目不想引入 Gson，替换解析部分即可：

```kotlin
import org.json.JSONObject

val json = connection.inputStream.bufferedReader().use { it.readText() }
val obj = JSONObject(json)

val latestVersion = obj.getString("tag_name").removePrefix("v")
val htmlUrl = obj.getString("html_url")
val body = obj.optString("body", "")

val assets = obj.optJSONArray("assets")
var apkUrl = htmlUrl
if (assets != null) {
    for (i in 0 until assets.length()) {
        val asset = assets.getJSONObject(i)
        if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
            apkUrl = asset.getString("browser_download_url")
            break
        }
    }
}
```
