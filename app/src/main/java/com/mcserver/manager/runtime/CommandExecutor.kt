package com.mcserver.manager.runtime

import android.os.FileObserver
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile

/**
 * 命令执行器（Termux 原生模式，不依赖 proot）：
 *  - 直接用 rootfs 里的 bash 执行命令
 *  - 设置 LD_LIBRARY_PATH 指向 rootfs/usr/lib
 *  - 日志通过文件监视实现（替代 LocalSocket）
 */
class CommandExecutor(private val installer: BootstrapInstaller) {

    private val _consoleFlow = MutableSharedFlow<String>(
        replay = 256,
        extraBufferCapacity = 2048
    )
    val consoleFlow: SharedFlow<String> = _consoleFlow.asSharedFlow()

    private var logWatcher: FileObserver? = null
    private var lastLogPos: Long = 0L

    fun emit(line: String) {
        _consoleFlow.tryEmit(line)
    }

    /** 使用 Android 系统 sh 执行，避免 app_data_file 执行限制 */
    private fun buildExecCommand(command: List<String>): List<String> {
        val sh = "/system/bin/sh"
        val cmdStr = command.joinToString(" ") { arg ->
            if (arg.any { it == ' ' || it == '\'' || it == '"' || it == '$' }) {
                "'${arg.replace("'", "'\\''")}'"
            } else arg
        }
        return listOf(sh, "-c", cmdStr)
    }

    /** Termux 环境变量 */
    fun termuxEnv(): Map<String, String> {
        val prefix = installer.rootDir.absolutePath
        return mapOf(
            "HOME" to "$prefix/home",
            "PATH" to "$prefix/usr/bin:$prefix/usr/bin/applets:/system/bin:/system/xbin",
            "TMPDIR" to "$prefix/usr/tmp",
            "LD_LIBRARY_PATH" to "$prefix/usr/lib:/system/lib64",
            "PREFIX" to "$prefix/usr",
            "TERM" to "xterm-256color",
            "LANG" to "en_US.UTF-8",
            "COLORTERM" to "true"
        )
    }

    /** 一次性命令，阻塞等待完成 */
    fun execOnce(vararg command: String, env: Map<String, String> = emptyMap()): Int {
        val full = buildExecCommand(command.toList())
        Log.d(TAG, "execOnce: ${full.joinToString(" ").take(200)}")
        val pb = ProcessBuilder(full).apply {
            redirectErrorStream(true)
            directory(File(installer.rootDir, "home/server").apply { mkdirs() })
            environment().putAll(termuxEnv())
            env.forEach { (k, v) -> environment()[k] = v }
        }
        val process = pb.start()
        BufferedReader(InputStreamReader(process.inputStream)).useLines { seq ->
            seq.forEach { line -> _consoleFlow.tryEmit(line) }
        }
        return process.waitFor()
    }

    /**
     * 后台长期命令（如 tmux new-session -d）。
     * 输出流入 consoleFlow 供 UI 订阅
     */
    fun execStream(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process {
        val full = buildExecCommand(command.toList())
        Log.d(TAG, "execStream[$tag]: ${full.joinToString(" ").take(200)}")
        val pb = ProcessBuilder(full).apply {
            redirectErrorStream(true)
            directory(File(installer.rootDir, "home/server").apply { mkdirs() })
            environment().putAll(termuxEnv())
            env.forEach { (k, v) -> environment()[k] = v }
        }
        val process = pb.start()
        Thread({
            BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    _consoleFlow.tryEmit("[$tag] $line")
                    line = r.readLine()
                }
            }
        }, "exec-$tag-reader").start()
        return process
    }

    /**
     * 启动日志文件监视：当 MC 服务器日志文件更新时，读取新行推送到 consoleFlow。
     * 替代之前的 ConsoleSocketServer 方案，更简单可靠。
     */
    fun startLogWatcher(logFile: File) {
        stopLogWatcher()
        if (!logFile.exists()) {
            logFile.parentFile?.mkdirs()
            logFile.createNewFile()
        }
        lastLogPos = logFile.length()

        logWatcher = object : FileObserver(logFile.absolutePath, MODIFY or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                try {
                    val len = logFile.length()
                    if (len < lastLogPos) {
                        lastLogPos = 0
                    }
                    if (len > lastLogPos) {
                        RandomAccessFile(logFile, "r").use { raf ->
                            raf.seek(lastLogPos)
                            val data = ByteArray((len - lastLogPos).toInt())
                            raf.readFully(data)
                            String(data).split("\n").forEach { line ->
                                if (line.isNotBlank()) {
                                    _consoleFlow.tryEmit(line)
                                }
                            }
                        }
                        lastLogPos = len
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "logWatcher error: ${e.message}")
                }
            }
        }
        logWatcher?.startWatching()
        Log.i(TAG, "logWatcher started on ${logFile.absolutePath}")
    }

    fun stopLogWatcher() {
        logWatcher?.stopWatching()
        logWatcher = null
    }

    companion object { private const val TAG = "CommandExecutor" }
}
