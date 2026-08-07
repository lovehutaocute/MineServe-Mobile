package com.mineserve.mobile.data

import android.annotation.SuppressLint
import android.content.Context

/**
 * 下载设置（SharedPreferences 持久化）。
 * 独立于 DataStore 的 McConfig，便于 TermuxRuntime/BootstrapInstaller/McServerController
 * 等在无 config 上下文的环境读取多线程下载开关与线程数。
 */
object DownloadPrefs {

    private const val PREFS = "download_settings"
    private const val KEY_ENABLED = "multi_thread_download"
    private const val KEY_THREADS = "download_threads"

    /** 默认：多线程下载内置并默认启用 */
    const val DEFAULT_ENABLED = true
    const val DEFAULT_THREADS = 4

    @Volatile
    private var appContext: Context? = null

    @SuppressLint("StaticFieldLeak")
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private fun prefs(): android.content.SharedPreferences? {
        val ctx = appContext ?: return null
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** 多线程下载是否启用（默认 true） */
    fun isEnabled(): Boolean {
        val p = prefs() ?: return DEFAULT_ENABLED
        return p.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    /** 下载线程数（默认 4） */
    fun threadCount(): Int {
        val p = prefs() ?: return DEFAULT_THREADS
        return p.getInt(KEY_THREADS, DEFAULT_THREADS).coerceIn(1, 16)
    }

    fun setEnabled(enabled: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    fun setThreadCount(count: Int) {
        prefs()?.edit()?.putInt(KEY_THREADS, count.coerceIn(1, 16))?.apply()
    }
}