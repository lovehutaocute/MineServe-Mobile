package com.mineserve.mobile.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP 工具目录（纯 JVM 数据，可单测）：
 * 覆盖服务器管理、MC 控制台、Termux 命令、服务器文件管理与模组安装。
 */
object McpToolCatalog {

    /** 全部内置工具定义（顺序即 tools/list 展示顺序） */
    fun definitions(): List<McpToolDef> = listOf(
        // ── 服务器管理 ──────────────────────────────────────────
        McpToolDef(
            name = "get_server_status",
            description = "获取 Minecraft 服务器运行状态：是否运行、启动阶段、在线玩家、TPS、内存、CPU、运行时长与当前活动服务器。",
            inputSchema = schema()
        ),
        McpToolDef(
            name = "list_servers",
            description = "列出应用中所有已安装的 Minecraft 服务器，包括核心类型、版本以及哪一个是当前活动服务器。",
            inputSchema = schema()
        ),
        McpToolDef(
            name = "select_server",
            description = "切换当前活动服务器（会持久化保存）。文件、模组、启动等工具默认作用于活动服务器。",
            inputSchema = schema(
                "server" to strProp("服务器名称或目录名，见 list_servers 返回结果"),
                required = listOf("server")
            )
        ),
        McpToolDef(
            name = "start_server",
            description = "启动一个 Minecraft 服务器。可先切换到指定服务器再启动。立即返回；用 get_server_status 轮询启动进度。",
            inputSchema = schema(
                "server" to strProp("可选：要切换并启动的服务器名称/目录名；缺省为当前活动服务器")
            )
        ),
        McpToolDef(
            name = "stop_server",
            description = "优雅停止正在运行的 Minecraft 服务器（向控制台发送 stop 命令）。",
            inputSchema = schema()
        ),
        // ── MC 控制台 ──────────────────────────────────────────
        McpToolDef(
            name = "send_command",
            description = "向正在运行的服务器控制台发送命令，例如 list、say hello、op Steve。不要带开头的斜杠。",
            inputSchema = schema(
                "command" to strProp("控制台命令，不带开头的 /"),
                required = listOf("command")
            )
        ),
        McpToolDef(
            name = "get_console_logs",
            description = "读取某服务器控制台日志（logs/latest.log）的末尾若干行，重启后仍可读取历史输出。",
            inputSchema = schema(
                "lines" to intProp("返回末尾的行数，1-2000（默认 100）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器")
            )
        ),
        // ── Termux 运行环境 ─────────────────────────────────────
        McpToolDef(
            name = "run_termux_command",
            description = "在应用的 Termux 运行环境里执行一条 shell 命令（与应用内 Termux 终端会话同一环境），返回合并输出与退出码。可用于诊断、运行时管理以及服务器目录之外的文件操作。",
            inputSchema = schema(
                "command" to strProp("shell 命令，例如 'java -version'、'ls ~/servers'、'df -h'"),
                "timeout_sec" to intProp("超时秒数，5-300（默认 60）；超时后返回已捕获的部分输出"),
                required = listOf("command")
            )
        ),
        // ── 服务器文件管理（限定在服务器目录内） ──────────────────
        McpToolDef(
            name = "list_files",
            description = "列出服务器目录内的一个目录（世界、插件、模组、配置等）。路径相对于服务器根目录。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的目录路径（默认：服务器根目录）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器")
            )
        ),
        McpToolDef(
            name = "read_file",
            description = "读取服务器目录内的文本文件（配置、properties、日志等），二进制文件会被拒绝。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的文件路径"),
                "max_bytes" to intProp("最多返回的字节数，上限 524288（默认 131072）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path")
            )
        ),
        McpToolDef(
            name = "write_file",
            description = "在服务器目录内创建或覆盖一个文本文件（如 server.properties、配置 yml）。上限 1MB，父目录会自动创建。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的文件路径"),
                "content" to strProp("要写入的完整文本内容（UTF-8）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path", "content")
            )
        ),
        McpToolDef(
            name = "delete_file",
            description = "删除服务器目录内的文件或目录（递归删除）。服务器根目录本身不允许删除。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的路径"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path")
            )
        ),
        McpToolDef(
            name = "rename_file",
            description = "在服务器目录内重命名或移动文件/目录，目标必须不存在。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的源路径"),
                "new_path" to strProp("相对服务器根目录的目标路径"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path", "new_path")
            )
        ),
        McpToolDef(
            name = "make_dir",
            description = "在服务器目录内创建目录（自动创建父目录）。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的目录路径"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path")
            )
        ),
        McpToolDef(
            name = "upload_file",
            description = "把电脑上的二进制文件（模组 jar、世界 zip、地图压缩包等）上传到服务器目录。content_base64 为标准 base64（也接受 data: URI 前缀）。大于约 12MB 的文件需分块发送：首块 append=false，后续块 append=true。zip 压缩包可用 extract_archive 解压。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的文件路径"),
                "content_base64" to strProp("base64 编码的文件内容（单块不超过约 16MB base64 文本）"),
                "append" to strProp("设为 true 时追加写入已有文件而不是覆盖"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path", "content_base64")
            )
        ),
        McpToolDef(
            name = "extract_archive",
            description = "解压服务器目录内的 zip/tar/tar.gz/tar.bz2/tar.xz 压缩包（例如通过 upload_file 上传的世界存档或模组目录）。不安全的条目（路径穿越、符号链接）会自动跳过。",
            inputSchema = schema(
                "path" to strProp("相对服务器根目录的压缩包路径"),
                "dest" to strProp("解压目标目录，相对服务器根目录（默认：服务器根目录）"),
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器"),
                required = listOf("path")
            )
        ),
        McpToolDef(
            name = "import_server",
            description = "从 upload_file 上传的压缩包导入一个新服务器：解压到应用的服务器目录并注册到服务器列表，自动识别核心类型与版本（识别不出也能登记）。压缩包路径相对于当前活动服务器目录（即 upload_file 的落点），导入完成后会删除压缩包。",
            inputSchema = schema(
                "archive" to strProp("压缩包路径，相对当前活动服务器根目录，例如 'mcp-import-staging.zip'"),
                "name" to strProp("新服务器的显示名称"),
                required = listOf("archive", "name")
            )
        ),
        // ── 模组（Modrinth） ────────────────────────────────────
        McpToolDef(
            name = "list_mods",
            description = "列出某服务器已安装的模组（文件名、大小、启用状态）。",
            inputSchema = schema(
                "server" to strProp("可选：服务器名称/目录名；缺省为当前活动服务器")
            )
        ),
        McpToolDef(
            name = "search_mods",
            description = "在 Modrinth 搜索模组。默认按活动服务器的 MC 版本与加载器过滤，最多返回 20 条。",
            inputSchema = schema(
                "query" to strProp("搜索关键词，例如 'sodium'"),
                "mc_version" to strProp("可选：MC 版本过滤，例如 '1.21.1'"),
                "loader" to strProp("可选：加载器过滤：fabric / forge / neoforge / quilt"),
                required = listOf("query")
            )
        ),
        McpToolDef(
            name = "install_mod",
            description = "从 Modrinth 下载并安装模组到服务器的 mods 目录（重启服务器后生效）。",
            inputSchema = schema(
                "slug" to strProp("Modrinth 项目的 slug 或 ID（来自 search_mods）"),
                "mc_version" to strProp("可选：MC 版本；默认为目标服务器的版本"),
                "loader" to strProp("可选：加载器；默认按目标服务器的核心类型推断"),
                "server" to strProp("可选：目标服务器；缺省为当前活动服务器"),
                required = listOf("slug")
            )
        )
    )

    // ── schema 构建辅助 ─────────────────────────────────────────

    private fun schema(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                properties.forEach { (name, prop) -> put(name, prop) }
            })
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
        }

    private fun strProp(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun intProp(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }
}
