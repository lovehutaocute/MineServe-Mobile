package com.mineserve.mobile

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 一像素无声保活 Activity：
 * 透明 1×1px 窗口常驻，配合前台服务提升进程存活率（主流 ROM 效果有限，尽力而为）。
 * - 开启保活时由 McViewModel 启动（NEW_TASK，1px 透明，不抢焦点）
 * - 关闭保活时发送 PIXEL_FINISH 广播使其自我销毁
 * - onStop 不 finish，保持常驻
 */
class KeepAlivePixelActivity : Activity() {

    companion object {
        const val ACTION_FINISH = "com.mineserve.mobile.PIXEL_FINISH"

        /** 发送关闭广播，让保活 Activity 自我销毁 */
        fun stop(context: Context) {
            try {
                context.sendBroadcast(Intent(ACTION_FINISH))
            } catch (_: Exception) {}
        }
    }

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_FINISH) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1px 透明窗口
        val view = View(this)
        view.setBackgroundColor(Color.TRANSPARENT)
        setContentView(view)
        window.setLayout(1, 1)
        window.setGravity(Gravity.START or Gravity.TOP)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
        try {
            registerReceiver(finishReceiver, IntentFilter(ACTION_FINISH))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(finishReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
