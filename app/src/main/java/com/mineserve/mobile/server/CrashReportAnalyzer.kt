package com.mineserve.mobile.server

/** Offline crash hints. Rules are evidence-based and never perform repairs. */
object CrashReportAnalyzer {
    enum class Level { Identified, Possible, Reminder }

    data class Finding(val level: Level, val label: String, val detail: String, val evidence: String, val suggestion: String)
    data class Analysis(
        val title: String,
        val firstException: String?,
        val causedBy: List<String>,
        val exitCode: Int?,
        val components: List<String>,
        val findings: List<Finding>
    ) { val primaryLabel: String get() = findings.firstOrNull()?.label ?: "未能明确归因" }

    private val exception = Regex("""(?:[A-Za-z0-9_.]+)?(?:Exception|Error|Throwable)(?::[^\n]*)?""")
    private val causedBy = Regex("""Caused by: ([^\n]+)""")
    private val exitCode = Regex("""(?:exit(?: code)?|退出码)\s*[:=]\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val component = Regex("""([A-Za-z0-9_.-]+\.(?:jar|zip|class))""")
    private val classVersion = Regex("""class file version\s+(\d+)""", RegexOption.IGNORE_CASE)

    /** Returns the minimum Java release explicitly requested by a log line. */
    fun requiredJavaVersion(text: String): Int? {
        classVersion.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { version ->
            return when (version) { 52 -> 8; 55 -> 11; 61 -> 17; 65 -> 21; 69 -> 25; else -> null }
        }
        Regex("""(?:requires|need)\s+Java\s+(\d+)""", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("""Java\s+(\d+)\s+or\s+higher""", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return null
    }

    fun analyze(text: String): Analysis {
        val first = exception.find(text)?.value?.trim()
        val causes = causedBy.findAll(text).map { it.groupValues[1].trim() }.distinct().take(6).toList()
        val code = exitCode.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val components = component.findAll(text).map { it.groupValues[1] }.distinct().take(12).toList()
        val findings = mutableListOf<Finding>()
        fun add(label: String, detail: String, evidence: String, suggestion: String, level: Level = Level.Identified) {
            findings += Finding(level, label, detail, evidence, suggestion)
        }
        fun hasAny(vararg terms: String) = terms.any { text.contains(it, ignoreCase = true) }

        if (hasAny("UnsupportedClassVersionError", "class file version", "has been compiled by a more recent version"))
            add("Java 版本不兼容", "核心、插件或模组使用了当前 Java 无法加载的 class 版本。", "检测到 class version/UnsupportedClassVersionError", "按错误中显示的 class 版本选择对应 Java；同时确认核心官方支持范围。")
        if (hasAny("Unsupported major.minor version", "requires java", "requires Java", "Java 17 or higher", "Java 21 or higher", "Java 25 or higher", "Minecraft 1.20.5 requires Java 21", "Java 未安装", "Java not installed", "Java 不支持", "Java version is not supported"))
            add("Java 版本要求不满足", "日志明确指出当前 Java 版本低于核心或依赖要求。", "检测到 requires Java/更高 Java 版本提示", "切换到日志和核心官方文档要求的 Java 版本后重新启动。")
        if (hasAny("Could not find or load main class", "Unable to access jarfile", "unix_args.txt", "quilt-server-launch.jar", "server.jar 不存在", "启动文件缺失"))
            add("核心启动文件不完整", "启动入口或 installer 生成的产物缺失。", "检测到主类、jar 或启动参数文件缺失", "确认核心安装已完成；Forge/NeoForge/Quilt 需要先成功执行 installer。")
        if (hasAny("NoSuchMethodError", "NoClassDefFoundError", "ClassNotFoundException", "NoSuchFieldError", "IncompatibleClassChangeError"))
            add("依赖或版本不匹配", "运行时找不到类、方法或字段，通常来自核心、插件、模组或库版本不一致。", "检测到类/方法/字段链接错误", "检查首个异常涉及的 jar，移除最近添加的插件/模组，并核对 Minecraft 与加载器版本。")
        if (hasAny("mixin apply failed", "mixin", "mod loading has failed", "loading errors encountered", "failed to load mod", "fabricloader"))
            add("模组不匹配", "模组加载或 Mixin 注入失败。", "检测到模组加载/Mixin 错误", "核对 Minecraft、Forge/NeoForge/Fabric/Quilt 和所有模组版本；逐个停用最近更新的模组。", Level.Possible)
        if (hasAny("paper-plugin.yml", "plugin.yml", "invalid plugin", "could not load plugin", "failed to load plugin", "Unsupported API version", "插件不匹配", "插件加载失败"))
            add("插件不匹配或加载失败", "日志包含插件加载失败、API 版本不支持或插件描述异常。", "检测到插件加载/API 错误", "检查报错前后列出的插件，下载与当前服务端核心和 Minecraft 版本匹配的版本。", Level.Possible)
        if (hasAny("Address already in use", "BindException", "Failed to bind", "端口被占用", "Cannot assign requested address"))
            add("端口无法监听", "服务端无法绑定配置的地址或端口。", "检测到 bind/Address already in use", "关闭占用端口的旧进程，或修改 server.properties/pnx.yml 端口后重启。")
        if (hasAny("You need to agree to the EULA", "eula=false", "EULA"))
            add("EULA 未接受", "服务端因为 EULA 状态未满足而拒绝启动。", "检测到 EULA/eula=false", "阅读并接受对应服务端 EULA，再重新启动。")
        if (hasAny("Permission denied", "permission denied", "Operation not permitted", "execve", "PROOT_TMP_DIR"))
            add("运行环境权限异常", "启动脚本、临时目录或 PRoot 依赖没有可用权限。", "检测到 Permission denied/PROOT_TMP_DIR", "检查应用存储权限、临时目录可写性和运行环境诊断结果；不要删除服务器数据。")
        if (hasAny("Could not resolve host", "Temporary failure resolving", "UnknownHostException", "Connection timed out", "download failed"))
            add("依赖或资源下载失败", "核心启动或安装过程需要的网络资源未能获取。", "检测到 DNS、超时或下载失败", "检查网络/VPN、镜像源和 libraries 缓存后重试。", Level.Possible)
        if (hasAny("libc.so.6", "com.sun.jna.Native", "Did not JNA classes", "udev library"))
            add("Java/JNA 本地库限制", "JNA/OSHI 需要的 glibc 或 udev 能力在 Android/Termux 中可能不存在。", "检测到 libc.so.6/JNA/udev", "这是运行环境兼容性提示，不等同于 Minecraft 主线程崩溃；以首个真正异常为准。", Level.Possible)
        if (hasAny("OutOfMemoryError", "Java heap space", "GC overhead limit exceeded", "Cannot reserve enough space"))
            add("内存不足", "Java 堆或系统内存不足。", "检测到 OOM/heap space", "降低视距、插件/模组数量和 -Xmx；同时检查 Android 是否终止后台进程。")
        if (code == 137 || hasAny("SIGKILL", "Killed"))
            add("进程被系统终止", "exit=137 或 SIGKILL 通常表示进程被系统或外部强制终止。", "检测到 exit=137/SIGKILL", "检查设备内存压力、电池优化和后台限制；不要只根据此项断定是 Java 异常。")
        if (hasAny("fontconfig", "fc-cache", "fontconfig error", "Fontconfig head is null"))
            add("字体运行库缺失", "Forge/NeoForge 等核心的字体初始化依赖未完整安装。", "检测到 fontconfig/fc-cache", "先补齐字体运行库和缓存；无图形模式可能仍可继续，但图形相关功能会受限。", Level.Possible)
        if (findings.isEmpty()) add("未能明确归因", "报告中没有匹配到可靠的已知模式。", first ?: "未找到明确异常", "查看原始报告中的第一个异常和完整 Caused by 链，并核对核心官方要求。", level = Level.Reminder)
        return Analysis(first ?: "未找到首个异常", first, causes, code, components, findings)
    }
}
