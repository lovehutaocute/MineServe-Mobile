package com.mcserver.manager.server

import com.mcserver.manager.runtime.TermuxRuntime
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 玩家管理：通过 MC 的 JSON 文件读取玩家数据，通过控制台命令管理玩家。
 *
 * JSON 文件位于服务端工作目录（home/servers/{dirName}）：
 *  - ops.json：OP 列表
 *  - whitelist.json：白名单
 *  - banned-players.json：封禁列表
 *
 * 命令通过 [termux.sendCommand] 发送到 MC stdin，MC 执行后会回写对应 JSON 文件，
 * 可再次调用 read* 方法刷新本地视图。
 *
 * 重要：所有命令发送方法都会先调用 [termux.isMcRunning] 校验服务器状态，
 * 未运行时返回 false，由上层（ViewModel）给出错误反馈，避免静默失败。
 */
class PlayerManager(private val termux: TermuxRuntime) {

    /** 进服/离服日志解析：日志前缀(]: 结尾) + 玩家名(1-16位字母数字下划线) + 可选[IP]后缀 + (has) joined/left the game。
     * 以 ": " 锚定日志前缀结尾，过滤聊天消息（如 "<Steve> I joined the game"）误提取。 */
    private val joinLeaveStrictRegex = Regex(":\\s*([A-Za-z0-9_]{1,16})(?:\\[[^\\]]*\\])?\\s+(?:has\\s+)?(?:joined|left) the game")

    /** 宽松回退：兼容无日志前缀冒号的极端格式（如直接输出 "Steve joined the game"） */
    private val joinLeaveLooseRegex = Regex("([A-Za-z0-9_]{1,16})(?:\\[[^\\]]*\\])?\\s+(?:has\\s+)?(?:joined|left) the game")

    @Serializable
    data class OpEntry(val name: String, val uuid: String, val level: Int = 4)

    @Serializable
    data class WhitelistEntry(val name: String, val uuid: String)

    @Serializable
    data class BannedEntry(
        val name: String,
        val uuid: String,
        val reason: String = "",
        val expires: String = "forever",
        val source: String = ""
    )

    /** 宽松解析：MC 的 JSON 可能含额外字段（bypassesPlayerLimit 等），全部忽略 */
    private val json = Json { ignoreUnknownKeys = true }

    /** MC 服务端工作目录（home/servers/{dirName}） */
    fun serverDir(dirName: String): File =
        File(termux.installer.rootDir, "home/servers/$dirName")

    /** 读取 ops.json；文件不存在或解析失败时返回空列表 */
    fun readOps(dirName: String): List<OpEntry> = readJsonList(File(serverDir(dirName), "ops.json"))

    /** 读取 whitelist.json；文件不存在或解析失败时返回空列表 */
    fun readWhitelist(dirName: String): List<WhitelistEntry> =
        readJsonList(File(serverDir(dirName), "whitelist.json"))

    /** 读取 banned-players.json；文件不存在或解析失败时返回空列表 */
    fun readBanned(dirName: String): List<BannedEntry> =
        readJsonList(File(serverDir(dirName), "banned-players.json"))

    /** 通用 JSON 列表读取：文件不存在/为空/解析失败均返回空列表，绝不抛异常 */
    private inline fun <reified T> readJsonList(file: File): List<T> {
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            if (content.isBlank()) emptyList() else json.decodeFromString(content)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── 命令发送（带服务器状态校验） ──────────────────────────────

    /**
     * 发送命令到 MC 控制台
     * @return true 表示已发送，false 表示服务器未运行
     */
    private fun sendCmd(line: String): Boolean {
        if (!termux.isMcRunning()) return false
        termux.sendCommand(line)
        return true
    }

    // ── OP 管理 ──────────────────────────────────────────────────

    /**
     * 添加 OP（默认等级 4）
     * @return true 表示已发送
     */
    fun opPlayer(name: String): Boolean {
        return sendCmd("op $name")
    }

    /**
     * 添加 OP。
     * 发送 `op $name`（不带 level 参数）：MC 的 op 命令带等级参数是 1.20.2+
     * 才支持的，旧版会报 Usage 错误导致添加失败；与手动控制台命令保持一致。
     * @param level 兼容参数（仅新版服务器支持指定等级，旧版默认 4 级）
     * @return true 表示已发送
     */
    fun opPlayerWithLevel(name: String, level: Int): Boolean {
        // 不发送 deop：先撤后加在旧版上会导致"撤销成功、添加失败"的副作用
        return sendCmd("op $name")
    }

    /** 取消 OP */
    fun deopPlayer(name: String): Boolean = sendCmd("deop $name")

    // ── 白名单 ──────────────────────────────────────────────────

    /** 添加白名单 */
    fun whitelistAdd(name: String): Boolean = sendCmd("whitelist add $name")

    /** 移除白名单 */
    fun whitelistRemove(name: String): Boolean = sendCmd("whitelist remove $name")

    /** 开启白名单 */
    fun whitelistOn(): Boolean = sendCmd("whitelist on")

    /** 关闭白名单 */
    fun whitelistOff(): Boolean = sendCmd("whitelist off")

    /** 重载白名单（手动编辑 whitelist.json 后调用） */
    fun whitelistReload(): Boolean = sendCmd("whitelist reload")

    // ── 踢出/封禁 ──────────────────────────────────────────────

    /**
     * 踢出玩家
     * @param reason 踢出原因，留空则使用默认
     */
    fun kickPlayer(name: String, reason: String = ""): Boolean {
        val cmd = if (reason.isBlank()) "kick $name" else "kick $name $reason"
        return sendCmd(cmd)
    }

    /**
     * 永久封禁玩家
     * @param reason 封禁原因
     */
    fun banPlayer(name: String, reason: String = "Banned by admin"): Boolean {
        return sendCmd(if (reason.isBlank()) "ban $name" else "ban $name $reason")
    }

    /**
     * 限时封禁玩家
     * @param duration 时长（如 "30m"、"1h"、"7d"）
     * @param reason 封禁原因
     */
    fun tempBanPlayer(name: String, duration: String, reason: String = ""): Boolean {
        val cmd = if (reason.isBlank()) "tempban $name $duration"
                  else "tempban $name $duration $reason"
        return sendCmd(cmd)
    }

    /** 解除封禁 */
    fun pardonPlayer(name: String): Boolean = sendCmd("pardon $name")

    // ── 在线玩家列表 ────────────────────────────────────────────

    /** 请求在线玩家列表（发送 list 命令，结果通过日志解析） */
    fun requestOnlineList(): Boolean = sendCmd("list")

    /**
     * 从日志中解析在线玩家列表
     * 匹配 "players online: <names>" 格式
     */
    fun parseOnlinePlayers(logLine: String): List<String>? {
        // 标准格式：There are 3 of a max of 20 players online: Alice, Bob, Charlie
        val regex = Regex("players online:\\s*(.*)")
        val match = regex.find(logLine) ?: return null
        val names = match.groupValues[1].trim()
        if (names.isEmpty()) return emptyList()
        // 玩家名可能包含下划线、数字，按逗号分隔
        return names.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 从进服/离服日志行中提取玩家名。
     * 支持多种格式：`Steve joined the game`、`Steve has joined the game`、
     * `Steve[/127.0.0.1:5555] joined the game`（带 IP 后缀）、带日志时间戳前缀等。
     * 玩家名按 MC 格式（1-16 位字母/数字/下划线）校验，过滤聊天消息等误匹配。
     * @return 玩家名；无法提取或格式非法时返回 null
     */
    fun extractPlayerName(logLine: String): String? {
        // 先严格匹配（冒号锚定日志前缀，防聊天误报），失败再宽松回退（兼容无前缀格式）
        val m = joinLeaveStrictRegex.find(logLine) ?: joinLeaveLooseRegex.find(logLine) ?: return null
        return m.groupValues[1]
    }

    // ── 游戏模式切换 ────────────────────────────────────────────

    /**
     * 设置玩家游戏模式
     * @param mode 0=生存 1=创造 2=冒险 3=旁观
     */
    fun setGameMode(name: String, mode: Int): Boolean {
        val m = mode.coerceIn(0, 3)
        // 兼容新旧语法：MC 1.13+ 为 `gamemode <模式> <玩家>`，旧版为 `gamemode <玩家> <模式>`。
        // 同时发送两条保证任一生效（错误的一条 MC 仅提示 usage，不影响另一条）。
        sendCmd("gamemode $m $name")
        return sendCmd("gamemode $name $m")
    }

    /**
     * 给玩家经验
     * @param amount 经验数量
     */
    fun giveXp(name: String, amount: Int): Boolean {
        if (amount <= 0) return false
        return sendCmd("xp give $name $amount")
    }
}
