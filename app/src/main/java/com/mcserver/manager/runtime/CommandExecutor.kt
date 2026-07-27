package com.mcserver.manager.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 命令执行抽象：
 *  - execOnce: 一次性命令，阻塞等待结果
 *  - execStream: 后台命令，行级日志通过 consoleFlow 推送
 *
 * 底层通过 ProcessBuilder 启动 proot + bash 子进程；不暴露任何 Termux UI。
 */
class CommandExecutor(private val installer: BootstrapInstaller) {

    private val _consoleFlow = MutableSharedFlow<String>(
        replay = 256,
        extraBufferCapacity = 1024
    )
    val consoleFlow: SharedFlow<String> = _consoleFlow.asSharedFlow()

    private val _errorFlow = MutableSharedFlow<String>(replay = 16, extraBufferCapacity = 256)
    val errorFlow: SharedFlow<String> = _errorFlow.asSharedFlow()

    /**
     * 在 proot 命名空间中执行一次性命令
     * @return 命令退出码
     */
    fun execOnce(vararg command: String, env: Map<String, String> = emptyMap()): Int {
        val full = buildProotCommand(*command)
        Log.d(TAG, "exec: ${full.joinToString(" ")}")
        val pb = ProcessBuilder(full).apply {
            redirectErrorStream(true)
            val workDir = File(installer.rootDir, "home/server").apply { mkdirs() }
            directory(workDir)
            // 注入 proot 需要的环境
            environment()["PROOT_TMP_DIR"] = installer.tmpDir.absolutePath
            environment()["HOME"] = File(installer.rootDir, "home").absolutePath
            environment()["TMPDIR"] = installer.tmpDir.absolutePath
            environment()["PATH"] = "${File(installer.rootDir, "usr/bin").absolutePath}:${File(installer.nativeDir).absolutePath}:/system/bin"
            environment()["LD_LIBRARY_PATH"] = File(installer.rootDir, "usr/lib").absolutePath
            env.forEach { (k, v) -> environment()[k] = v }
        }
        val process = pb.start()
        // 消费 stdout/stderr
        BufferedReader(InputStreamReader(process.inputStream)).useLines { seq ->
            seq.forEach { line ->
                _consoleFlow.tryEmit(line)
            }
        }
        return process.waitFor()
    }

    /**
     * 在后台启动一条长期命令（如 java -jar paper.jar）。
     * 使用 setsid 脱离父进程组，使得 APP 被杀时子进程仍存活。
     * 输出经 socket 推送给 UI（由 ConsoleSocketServer 中转）。
     */
    fun execStream(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process {
        val full = buildProotCommand(*command)
        val pb = ProcessBuilder(full).apply {
            redirectErrorStream(true)
            directory(File(installer.rootDir, "home/server").apply { mkdirs() })
            environment()["PROOT_TMP_DIR"] = installer.tmpDir.absolutePath
            environment()["HOME"] = File(installer.rootDir, "home").absolutePath
            environment()["TMPDIR"] = installer.tmpDir.absolutePath
            environment()["PATH"] = "${File(installer.rootDir, "usr/bin").absolutePath}:${File(installer.nativeDir).absolutePath}:/system/bin"
            environment()["LD_LIBRARY_PATH"] = File(installer.rootDir, "usr/lib").absolutePath
            env.forEach { (k, v) -> environment()[k] = v }
        }
        val process = pb.start()
        // 输出转发到 consoleFlow，UI 可订阅
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

    /** 写一行到正在运行的进程 stdin（如向 MC 控制台发指令） */
    fun sendInput(process: Process, line: String) {
        process.outputStream.write((line + "\n").toByteArray())
        process.outputStream.flush()
    }

    /**
     * 构造 proot 启动命令。
     * 实际命令形如：
     *   proot --rootfs=<rootDir> --link2symlink --kill-on-exit \
     *         /usr/bin/env -i PATH=... bash -c "<command>"
     */
    private fun buildProotCommand(vararg command: String): List<String> {
        val prootBin = File(installer.nativeDir, "proot").absolutePath
        val rootfs = installer.rootDir.absolutePath
        val bash = File(installer.rootDir, "usr/bin/bash").absolutePath
        val cmdStr = command.joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it }
        return listOf(
            prootBin,
            "--rootfs=$rootfs",
            "--link2symlink",
            "--kill-on-exit",
            "--root-id",
            "--cwd=/home/server",
            bash, "-lc", cmdStr
        )
    }

    companion object { private const val TAG = "CommandExecutor" }
}
