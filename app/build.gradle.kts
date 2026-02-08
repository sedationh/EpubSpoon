plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 从 git tag 提取版本号，统一 tag → versionName → BuildConfig.VERSION_NAME。
 * - CI 由 tag 触发（如 v1.0.0），`git describe --tags --abbrev=0` 返回 "v1.0.0"
 * - 本地开发如果没有 tag 就 fallback 到 "dev"
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
 * 从版本字符串生成 versionCode。
 * "1.0.0" → 1_00_00 = 10000, "1.2.3" → 10203, "dev" → 1
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
    namespace = "com.example.epubspoon"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.epubspoon"
        minSdk = 26
        targetSdk = 35
        versionCode = gitVersionCode()
        versionName = gitVersionName()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // ViewModel + LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // EPUB parsing
    implementation("com.positiondev.epublib:epublib-core:3.1") {
        exclude(group = "org.slf4j")
        exclude(group = "xmlpull")
    }
    implementation("org.slf4j:slf4j-simple:1.7.36")

    // HTML → plain text
    implementation("org.jsoup:jsoup:1.17.2")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")
}
