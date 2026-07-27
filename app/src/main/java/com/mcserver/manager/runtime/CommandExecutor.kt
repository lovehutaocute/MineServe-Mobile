package com.mcserver.manager.runtime

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 命令执行器（生产化）：
 *  - execOnce：一次性命令，阻塞等待结果，输出流入 consoleFlow
 *  - execStream：后台长期命令，用于 tmux + MC 服务进程
 *  - emit：外部（如 ConsoleSocketServer）向 consoleFlow 推送日志
 *  - 所有命令均通过 proot 在 chroot 环境中执行
 *  - proot 命令构造形如：
 *      proot --rootfs=rootDir --link2symlink --kill-on-exit --root-id --cwd=/home/server \
 *        /usr/bin/env -i HOME=/home PATH=/usr/bin:/system/bin TMPDIR=/tmp \
 *        bash -lc "实际命令"
 */
class CommandExecutor(private val installer: BootstrapInstaller) {

    private val _consoleFlow = MutableSharedFlow<String>(
        replay = 256,
        extraBufferCapacity = 2048
    )
    val consoleFlow: SharedFlow<String> = _consoleFlow.asSharedFlow()

    /** 外部推送日志（供 ConsoleSocketServer 使用，统一日志流） */
    fun emit(line: String) {
        _consoleFlow.tryEmit(line)
    }

    /** 一次性命令，阻塞等待完成 */
    fun execOnce(vararg command: String, env: Map<String, String> = emptyMap()): Int {
        val full = buildProotCommand(command.toList())
        Log.d(TAG, "execOnce: ${full.joinToString(" ").take(200)}")
        val pb = ProcessBuilder(full).apply {
            redirectErrorStream(true)
            directory(File(installer.rootDir, "home/server").apply { mkdirs() })
            environment().putAll(prootEnv())
            env.forEach { (k, v) -> environment()[k] = v }
        }
        val process = pb.start()
        BufferedReader(InputStreamReader(process.inputStream)).useLines { seq ->
            seq.forEach { line -> _consoleFlow.tryEmit(line) }
        }
        return process.waitFor()
    }

    /**
     * 后台长期命令（如 tmux new-session -d java -jar server.jar）。
     * - 输出流入 consoleFlow 供 UI 订阅
     * - 返回 Process 句柄，调用方可监控退出
     */
    fun execStream(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process {
        val full = buildProotCommand(command.toList())
        Log.d(TAG, "execStream[$tag]: ${full.joinToString(" ").take(200)}")
        val pb = ProcessBuilder(full).apply {
            redirectErrorStream(true)
            directory(File(installer.rootDir, "home/server").apply { mkdirs() })
            environment().putAll(prootEnv())
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

    /** 向进程 stdin 写入一行 */
    fun sendInput(process: Process, line: String) {
        runCatching {
            process.outputStream.write((line + "\n").toByteArray())
            process.outputStream.flush()
        }
    }

    /**
     * 构造 proot 命令。
     * 关键修复：环境变量不再拼成单个字符串，而是作为 /usr/bin/env 的独立参数，
     * 避免含空格的值破坏参数解析。
     * 最终命令：
     *   proot --rootfs=<rootDir> --link2symlink --kill-on-exit --root-id --cwd=/home/server \
     *     --bind=/dev --bind=/proc --bind=/sys \
     *     /usr/bin/env -i HOME=/home PATH=... TMPDIR=/tmp bash -lc "cmd"
     */
    private fun buildProotCommand(command: List<String>): List<String> {
        val prootBin = File(installer.nativeDir, "proot").absolutePath
        val rootfs = installer.rootDir.absolutePath
        val bash = File(installer.rootDir, "usr/bin/bash").absolutePath

        // 将命令拼成单个字符串传给 bash -lc
        val cmdStr = command.joinToString(" ") { arg ->
            if (arg.any { it == ' ' || it == '\'' || it == '"' || it == '$' }) {
                "'${arg.replace("'", "'\\''")}'"
            } else arg
        }

        // 环境变量作为 /usr/bin/env 的独立参数（修复空格问题）
        val envArgs = prootEnv().entries.flatMap { (k, v) -> listOf("$k=$v") }

        return listOf(
            prootBin,
            "--rootfs=$rootfs",
            "--link2symlink",
            "--kill-on-exit",
            "--root-id",
            "--cwd=/home/server",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "/usr/bin/env", "-i"
        ) + envArgs + listOf(bash, "-lc", cmdStr)
    }

    /** proot 环境变量模板 */
    private fun prootEnv(): Map<String, String> = mapOf(
        "HOME" to "/home",
        "PATH" to "/usr/bin:/usr/local/bin:/system/bin:/system/xbin",
        "TMPDIR" to "/tmp",
        "LD_LIBRARY_PATH" to "/usr/lib:/system/lib64",
        "TERM" to "xterm-256color",
        "LANG" to "en_US.UTF-8",
        "PROOT_TMP_DIR" to installer.tmpDir.absolutePath
    )

    companion object { private const val TAG = "CommandExecutor" }
}
