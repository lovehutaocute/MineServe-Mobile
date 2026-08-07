package com.mineserve.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mineserve.mobile.service.McForegroundService
import com.mineserve.mobile.ui.McApp
import com.mineserve.mobile.ui.theme.MineServeMobileTheme

class MainActivity : ComponentActivity() {

    // Android 13+ 通知权限运行时申请
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 用户拒绝仅影响通知可见性，前台服务仍可保活 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureNotificationPermission()
        // 更新通知点击进入：通知带 open_update extra，通知 MainActivity 打开更新对话框
        if (intent?.getBooleanExtra("open_update", false) == true) {
            McApplication.get().requestOpenUpdate()
        }
        setContent {
            MineServeMobileTheme {
                McApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 启动前台服务（必须在 App 处于前台时触发，规避 Android 12+ 后台启动 FGS 限制）
        val intent = Intent(this, McForegroundService::class.java).apply {
            action = McForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
