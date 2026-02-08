# Android 应用手动更新机制（基于 GitHub Releases）

> 适用于个人/小团队项目，不上 Google Play，通过 GitHub Releases 分发 APK。
> 本文档既是给人的说明，也是给 AI 的参考——下次要做类似功能，把这个文档丢给 AI 就行。

---

## 先理解整体流程

```
你打一个 git tag (v1.2.0)
  ↓
GitHub Actions 被触发
  ↓
Gradle 从 tag 读版本号 → 用你的 keystore 签名 → 构建 APK → 发布到 GitHub Release
  ↓
用户在 app 里点「检查更新」→ 请求 GitHub API 拿最新版本号 → 跟本地对比 → 弹窗下载
```

**一句话**：版本号由 git tag 驱动全链路，CI 构建并发布，客户端请求 GitHub API 检查更新。

---

## ⚠️ APK 签名（最大的坑，先看）

Android 覆盖安装要求新旧 APK **签名完全一致**。你本地构建用的是 `~/.android/debug.keystore`，CI 上没有这个文件，会自动生成一个不同的 → 签名不一致 → 用户装不上。

### 解决方案（三步走）

**1. `build.gradle.kts` 里显式指定 keystore 路径**（不写的话 Gradle 可能用缓存里自动生成的）

```kotlin
signingConfigs {
    getByName("debug") {
        storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
}
buildTypes {
    release { signingConfig = signingConfigs.getByName("debug") }
}
```

**2. 把本地 keystore 上传到 GitHub Secrets**

```bash
base64 -i ~/.android/debug.keystore | tr -d '\n' | gh secret set DEBUG_KEYSTORE -R 用户名/仓库名
```

**3. CI 里用 `printf` 解码**（不要用 `echo`，会损坏文件）

```yaml
- name: Decode debug keystore
  run: |
    mkdir -p $HOME/.android
    printf '%s' "${{ secrets.DEBUG_KEYSTORE }}" | base64 -d > $HOME/.android/debug.keystore
```

### 踩过的三个坑

| 坑 | 现象 | 原因 |
|---|---|---|
| 用 `echo` 解码 | keystore 文件损坏 | echo 可能对 `\n`、`\t` 做转义，用 `printf '%s'` 代替 |
| Gradle 用错 keystore | 签名指纹对不上 | 没有显式指定 `storeFile`，Gradle 用了缓存里自动生成的 |
| `~/.android/` 不存在 | 写文件静默失败 | CI 的 Ubuntu 上没这个目录，加 `mkdir -p` |

三个坑的共同特点：**CI 不报错，APK 正常产出，但签名就是不对**。

### 验证方法

```bash
# 本地 keystore 指纹
keytool -list -keystore ~/.android/debug.keystore -storepass android

# CI 构建的 APK 指纹（需要 Android SDK build-tools）
apksigner verify --print-certs your-app.apk

# 两个 SHA-256 必须完全一致
```

注意：`keytool -printcert -jarfile` 对 v2/v3 签名的 APK 会显示 "Not a signed jar file"，必须用 `apksigner`。

### 已经不一致了怎么办

无法绕过，只能：卸载旧版 → 安装新版 → 之后就一直一致了。建议在更新弹窗里加提示。

---

## 版本号管理

在 `build.gradle.kts` 中加两个函数，从 git tag 自动读版本号：

```kotlin
fun gitVersionName(): String {
    return try {
        val process = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
            .directory(rootDir).redirectErrorStream(true).start()
        val tag = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (process.exitValue() == 0 && tag.isNotEmpty()) tag.removePrefix("v") else "dev"
    } catch (_: Exception) { "dev" }
}

fun gitVersionCode(): Int {
    val name = gitVersionName()
    if (name == "dev") return 1
    val parts = name.split(".").map { it.toIntOrNull() ?: 0 }
    return parts.getOrElse(0){0} * 10000 + parts.getOrElse(1){0} * 100 + parts.getOrElse(2){0}
}

android {
    defaultConfig {
        versionCode = gitVersionCode()   // 1.2.3 → 10203
        versionName = gitVersionName()   // "1.2.3"
    }
    buildFeatures { buildConfig = true } // AGP 8.0+ 默认关闭，必须开启
}
```

---

## CI 发布（GitHub Actions）

```yaml
name: Release
on:
  push:
    tags: ['v*']

jobs:
  release:
    runs-on: ubuntu-latest
    permissions: { contents: write }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - uses: gradle/actions/setup-gradle@v4
      - run: chmod +x gradlew

      - id: version
        run: echo "VERSION=${GITHUB_REF#refs/tags/v}" >> $GITHUB_OUTPUT

      - name: Decode debug keystore
        run: |
          mkdir -p $HOME/.android
          printf '%s' "${{ secrets.DEBUG_KEYSTORE }}" | base64 -d > $HOME/.android/debug.keystore

      - name: Verify keystore  # 可选，建议保留
        run: keytool -list -keystore $HOME/.android/debug.keystore -storepass android -alias androiddebugkey

      - run: ./gradlew assembleRelease

      - name: Rename & Release
        run: |
          APK=$(find app/build/outputs/apk/release -name '*.apk' | head -1)
          mv "$APK" app/build/outputs/apk/release/YourApp-${{ steps.version.outputs.VERSION }}.apk

      - uses: softprops/action-gh-release@v2
        with:
          name: v${{ steps.version.outputs.VERSION }}
          files: app/build/outputs/apk/release/YourApp-${{ steps.version.outputs.VERSION }}.apk
```

---

## 客户端更新检查

核心就是请求 `https://api.github.com/repos/用户名/仓库名/releases/latest`，拿到 `tag_name` 和 APK 下载链接。

```kotlin
object UpdateChecker {
    private const val API_URL = "https://api.github.com/repos/用户名/仓库名/releases/latest"

    data class UpdateResult(
        val hasUpdate: Boolean, val latestVersion: String, val currentVersion: String,
        val releaseNotes: String, val downloadUrl: String
    )

    suspend fun check(currentVersion: String): UpdateResult? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            if (conn.responseCode != 200) return@withContext null
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            // 用 Gson 或 org.json.JSONObject 解析都行
            val obj = org.json.JSONObject(json)
            val latest = obj.getString("tag_name").removePrefix("v")
            val body = obj.optString("body", "")
            val assets = obj.optJSONArray("assets")
            var apkUrl = obj.getString("html_url")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk", true)) {
                        apkUrl = a.getString("browser_download_url"); break
                    }
                }
            }
            UpdateResult(isNewer(latest, currentVersion), latest, currentVersion, body, apkUrl)
        } catch (_: Exception) { null }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i){0}; val b = c.getOrElse(i){0}
            if (a > b) return true; if (a < b) return false
        }
        return false
    }
}
```

调用：`UpdateChecker.check(BuildConfig.VERSION_NAME)` → 有更新弹 AlertDialog → 点"下载"跳浏览器。

---

## 移植 Checklist

给新项目加这个功能：

1. `build.gradle.kts`：复制 `gitVersionName()` / `gitVersionCode()`，开启 `buildConfig = true`
2. `build.gradle.kts`：显式配置 `signingConfigs`（指定 keystore 路径）
3. `AndroidManifest.xml`：加 `INTERNET` 权限
4. 复制 `UpdateChecker.kt`，改 `API_URL`
5. Activity 加按钮 + 调用 `checkForUpdate()`
6. 复制 `release.yml`，改 APK 名
7. 上传 keystore：`base64 -i ~/.android/debug.keystore | tr -d '\n' | gh secret set DEBUG_KEYSTORE`

## 发版

```bash
git push origin main && git tag v1.2.0 && git push origin v1.2.0
# CI 自动构建 + 发布，完事
```

## 备注

- 公开仓库无需 token，匿名 60 次/小时/IP，够用
- 零额外网络依赖（`HttpURLConnection` + `org.json.JSONObject` 都是 Android 自带）
- 如果项目已有 Gson，也可以用 Gson 解析