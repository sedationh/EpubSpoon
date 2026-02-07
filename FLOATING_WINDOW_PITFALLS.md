# Android 悬浮窗（Foreground Service + Overlay）踩坑记录

> 项目：EpubSpoon | targetSdk 35 | minSdk 26

---

## 坑 1：MissingForegroundServiceTypeException（Android 14+ 必现闪退）

**现象**：调用 `startForeground()` 直接崩溃，报 `MissingForegroundServiceTypeException: Starting FGS without a type`。

**原因**：Android 14（API 34）起，所有前台服务必须声明 `foregroundServiceType`，否则系统直接抛异常。

**解决**：三处都要改——

```xml
<!-- 1. AndroidManifest.xml 加权限 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<!-- 2. Service 声明加 foregroundServiceType + property -->
<service
    android:name=".service.FloatingService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="overlay_floating_window" />
</service>
```

```kotlin
// 3. startForeground() 调用时传类型
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    )
} else {
    startForeground(NOTIFICATION_ID, notification)
}
```

**要点**：Manifest 声明的类型、权限名、代码传的类型，三者必须一致。

---

## 坑 2：Android 13+ 通知权限（POST_NOTIFICATIONS）

**现象**：`startForegroundService()` 调用成功但通知发不出来，服务可能被系统杀掉。

**原因**：Android 13（API 33 TIRAMISU）起，发通知需要运行时权限 `POST_NOTIFICATIONS`。前台服务依赖通知，没权限 = 通知静默失败。

**解决**：

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

```kotlin
// 启动服务前检查
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        return // 等回调再启动
    }
}
```

---

## 坑 3：悬浮窗权限（SYSTEM_ALERT_WINDOW）不会自动弹窗

**现象**：悬浮窗不显示，没有任何报错。

**原因**：`SYSTEM_ALERT_WINDOW` 不是普通运行时权限，不能用 `requestPermissions()`，必须跳设置页让用户手动开。

**解决**：

```kotlin
if (!Settings.canDrawOverlays(this)) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName")
    )
    overlayPermissionLauncher.launch(intent)
}
```

**注意**：用户从设置页返回后要重新检查 `Settings.canDrawOverlays()`，不能假定用户一定开了。

---

## 坑 4：onDestroy 会杀死悬浮窗

**现象**：用户切出 app 或系统回收 Activity 后悬浮窗消失。

**原因**：如果在 `onDestroy()` 里写了 `stopService()`，Activity 销毁时悬浮窗就没了。前台服务本身是独立于 Activity 的，不应该跟着 Activity 一起死。

**解决**：不要在 `onDestroy` 里停止服务。只通过用户点击"关闭悬浮窗"按钮来停止。

---

## 坑 5：悬浮窗自动启动时机不对

**现象**：用户打开 app（恢复上次书籍）时，悬浮窗自动弹出但实际上权限还没给，或者用户不想要。

**原因**：恢复书籍状态走的是 `UiState.Success`，如果在这里无条件启动悬浮窗，每次打开 app 都会触发。

**解决**：悬浮窗改为手动启动（提供明确的"启动悬浮窗"按钮），不要在状态恢复时自动启动。

---

## 坑 6：foregroundServiceType="specialUse" 之前的尝试——直接去掉 type

**现象**：去掉 `foregroundServiceType` 后在 Android 14+ 设备上仍然崩溃。

**原因**：以为不声明 type 就是"无类型"可以绕过，但实际上 Android 14+ 强制要求有 type。没有 type = 直接异常。

**教训**：`foregroundServiceType` 在 targetSdk 34+ 不是可选的，是必须的。

---

## 坑 7：startForeground 要包 try-catch

**现象**：各种边缘情况（权限被撤销、通知通道被禁用、系统资源不足）都可能导致 `startForeground()` 抛异常。

**解决**：

```kotlin
try {
    startForeground(NOTIFICATION_ID, notification, serviceType)
} catch (e: Exception) {
    Log.e("TAG", "startForeground failed", e)
    stopSelf()
    return START_NOT_STICKY
}
```

---

## 总结：启动悬浮窗的完整检查清单

启动一个悬浮窗前台服务，按顺序检查：

1. ✅ `FOREGROUND_SERVICE` 权限（Manifest 静态声明）
2. ✅ `FOREGROUND_SERVICE_SPECIAL_USE` 权限（Manifest 静态声明，Android 14+）
3. ✅ `SYSTEM_ALERT_WINDOW` 权限（运行时跳设置页）
4. ✅ `POST_NOTIFICATIONS` 权限（Android 13+ 运行时请求）
5. ✅ Service 声明 `foregroundServiceType="specialUse"` + `<property>` 标签
6. ✅ `startForeground()` 传 `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
7. ✅ 所有 `startForeground()` / `startForegroundService()` 包 try-catch
8. ✅ 不在 `onDestroy` 停止服务
9. ✅ 不自动启动，提供手动按钮
