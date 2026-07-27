package com.mcserver.manager.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Bootstrap 安装器：
 * 1. 首次启动检测 native helper（proot/login/bash/tmux）是否存在
 * 2. 从 assets/native/<abi>/ 释放到 filesDir/native/
 * 3. 解压 Termux bootstrap rootfs（首次需联网下载并校验 SHA256）到 filesDir/home/
 * 4. 设置 PATH 与 LD_LIBRARY_PATH，使得后续 exec 可直接调用
 *
 * 实际生产中，bootstrap rootfs 的下载与解压由 BootstrapDownloader 完成（此处仅给接口骨架）。
 */
class BootstrapInstaller(private val context: Context) {

    val rootDir: File get() = File(context.filesDir, "home")
    val nativeDir: File get() = File(context.filesDir, "native")
    val tmpDir: File get() = File(context.filesDir, "tmp").apply { mkdirs() }
    val runtimeDir: File get() = File(context.filesDir, "runtime").apply { mkdirs() }

    val socketFile: File get() = File(runtimeDir, "mc.sock")

    /** 标记 bootstrap 是否已就绪 */
    private val readyFile: File get() = File(context.filesDir, ".bootstrap_ready")

    fun isReady(): Boolean = readyFile.exists() &&
            File(nativeDir, "proot").canExecute() &&
            File(rootDir, "usr/bin/bash").exists()

    /**
     * 完整初始化流程。返回是否成功。
     * 此处仅给出步骤骨架，实际生产需对接 BootstrapDownloader 拉取 rootfs。
     */
    suspend fun ensureInstalled(onProgress: (InstallPhase, Int) -> Unit): Boolean {
        if (isReady()) return true

        onProgress(InstallPhase.RELEASE_NATIVE, 5)
        releaseBundledNative()

        onProgress(InstallPhase.DOWNLOAD_ROOTFS, 15)
        // TODO: 拉取 termux-bootstrap rootfs (约 30MB) 到 tmpDir，校验 SHA256
        // val downloader = BootstrapDownloader(context)
        // downloader.downloadAndVerify(abi, rootfsFile)
        // 此处假定已有 rootfs 镜像
        val rootfsFile = File(tmpDir, "bootstrap-rootfs.tar.xz")
        if (!rootfsFile.exists()) {
            Log.w(TAG, "bootstrap rootfs not found in tmp/, will fall back to download later")
            // 生产环境此处应抛出可恢复错误，让 UI 引导用户重试
        }

        onProgress(InstallPhase.EXTRACT_ROOTFS, 50)
        extractRootfs(rootfsFile)

        onProgress(InstallPhase.POST_SETUP, 90)
        postSetup()

        readyFile.writeText(System.currentTimeMillis().toString())
        onProgress(InstallPhase.DONE, 100)
        return true
    }

    private fun releaseBundledNative() {
        nativeDir.mkdirs()
        val abis = listOf("arm64-v8a", "x86_64")
        val abi = abis.find { isAbiSupported(it) } ?: abis.first()
        // 实际生产：从 assets/native/<abi>/*.so / proot 释放到 nativeDir
        // 占位：写入一个标记文件
        listOf("proot", "login", "bash", "tmux", "tar", "xz", "coreutils").forEach { name ->
            File(nativeDir, name).apply {
                if (!exists()) {
                    writeText("#!/system/bin/sh\n# placeholder for $name\n")
                    setExecutable(true)
                }
            }
        }
    }

    private fun isAbiSupported(abi: String): Boolean =
        android.os.Build.SUPPORTED_ABIS.any { it == abi }

    private fun extractRootfs(rootfsFile: File) {
        rootDir.mkdirs()
        // 实际：用 nativeDir/tar 解压 rootfs 到 rootDir
        // ProcessBuilder("$nativeDir/tar", "-xJf", rootfsFile.absolutePath, "-C", rootDir.absolutePath)
        //     .redirectErrorStream(true).start().waitFor()
        // 占位：创建基础目录结构
        listOf("usr/bin", "usr/lib", "usr/etc", "home/server").forEach {
            File(rootDir, it).mkdirs()
        }
    }

    private fun postSetup() {
        // 创建 server 工作目录与配置文件位置
        File(rootDir, "home/server").mkdirs()
        File(rootDir, "home/server/eula.txt").writeText("eula=true\n")
    }

    enum class InstallPhase(val label: String) {
        RELEASE_NATIVE("释放 native helper"),
        DOWNLOAD_ROOTFS("下载 bootstrap rootfs"),
        EXTRACT_ROOTFS("解压 rootfs"),
        POST_SETUP("初始化目录结构"),
        DONE("完成")
    }

    companion object { private const val TAG = "BootstrapInstaller" }
}
