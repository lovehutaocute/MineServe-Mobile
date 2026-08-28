package com.mineserve.mobile

import android.view.WindowManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mineserve.mobile.ui.McApp
import com.mineserve.mobile.ui.theme.MineServeMobileTheme
import com.mineserve.mobile.service.McForegroundService

class MainActivity : ComponentActivity() {

    // Android 13+ 通知权限运行时申请
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 用户拒绝仅影响通知可见性，前台服务仍可保活 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 提高 UI 线程调度优先级：后台大游戏（如原神）会把 CPU 吃满，
        // 默认优先级下主线程时间片被挤占导致页面卡顿；与前台游戏体验保持一致的提权。
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
        } catch (_: Exception) {}
        lifecycleScope.launch {
            val repo = McApplication.get(this@MainActivity).repository
            combine(repo.configFlow, repo.serverState) { config, server ->
                config.keepScreenOnWhileRunning && server.isRunning
            }.collect { keepOn ->
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
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

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val config = McApplication.get(this@MainActivity).repository.configFlow.first()
            if (config.keepStatusOverlay) {
                // 授权页返回时服务可能还没完成启动，确保服务存在并让它重新挂载悬浮窗。
                if (McForegroundService.isRunning) {
                    startService(Intent(this@MainActivity, McForegroundService::class.java).apply {
                        action = McForegroundService.ACTION_REFRESH_KEEP_ALIVE
                    })
                } else {
                    McApplication.get(this@MainActivity).startForegroundService(
                        Intent(this@MainActivity, McForegroundService::class.java).apply {
                            action = McForegroundService.ACTION_START
                        }
                    )
                }
            } else if (McForegroundService.isRunning) {
                startService(Intent(this@MainActivity, McForegroundService::class.java).apply {
                    action = McForegroundService.ACTION_REFRESH_KEEP_ALIVE
                })
            }
        }
    }
}
