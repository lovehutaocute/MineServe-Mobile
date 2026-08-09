package com.mineserve.mobile.server

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * 外部备份存储：/storage/emulated/0/世界与服务器的备份/
 *
 * 卸载或覆盖安装应用时该目录保留（位于外部存储，不随 app 数据删除）。
 * Android 11+ 写固定外部路径需「所有文件访问」（MANAGE_EXTERNAL_STORAGE）；
 * 低版本用传统 WRITE_EXTERNAL_STORAGE。
 */
object ExternalBackupStore {

    /** 外部备份根目录（/storage/emulated/0/世界与服务器的备份/） */
    val rootDir: File
        get() = File(Environment.getExternalStorageDirectory(), "世界与服务器的备份")

    /** 确保目录存在，返回是否可用 */
    fun ensure(): Boolean {
        return try {
            val d = rootDir
            d.exists() || d.mkdirs()
        } catch (e: Exception) {
            false
        }
    }

    /** 是否有外部存储写权限（所有文件访问 / 传统存储权限） */
    fun hasPermission(context: Context): Boolean {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("NewApi")
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /** 列出外部目录下所有备份 zip（按名称排序） */
    fun listBackups(): List<File> {
        if (!rootDir.isDirectory) return emptyList()
        return rootDir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }
}
