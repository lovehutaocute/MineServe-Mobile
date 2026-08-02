package com.mcserver.manager.ui.screens

import androidx.compose.ui.res.stringResource
import com.mcserver.manager.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.SegPill
import com.mcserver.manager.ui.DebouncedTextField
import com.mcserver.manager.ui.theme.Coral
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Muted

// ── server.properties 参数元数据（完整覆盖，基础/高级分组） ──────────

private enum class PropGroup(val label: String) { Basic("基础配置"), Advanced("高级配置") }

private enum class PropType { Bool, Int, Text, Enum }

private data class PropertySpec(
    val key: String,
    val label: String,
    val desc: String = "",
    val type: PropType = PropType.Text,
    val group: PropGroup = PropGroup.Advanced,
    val options: List<Pair<String, String>> = emptyList(), // 显示名 to 值（仅 Enum）
    val default: String = ""
)

private val propertySpecs: List<PropertySpec> = listOf(
    // ── 基础配置 ──
    PropertySpec("difficulty", "难度", "怪物强度与饥饿机制", PropType.Enum, PropGroup.Basic,
        listOf("和平" to "peaceful", "简单" to "easy", "普通" to "normal", "困难" to "hard"), "easy"),
    PropertySpec("gamemode", "游戏模式", "新玩家进入时的默认模式", PropType.Enum, PropGroup.Basic,
        listOf("生存" to "survival", "创造" to "creative", "冒险" to "adventure", "旁观" to "spectator"), "survival"),
    PropertySpec("pvp", "PVP", "允许玩家之间互相攻击", PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("white-list", "白名单", "仅白名单内玩家可加入", PropType.Bool, PropGroup.Basic),
    PropertySpec("online-mode", "在线模式", "开启需正版验证，关闭可让离线客户端加入", PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("enforce-whitelist", "强制白名单", "白名单开启后，OP 也会被限制", PropType.Bool, PropGroup.Basic),
    PropertySpec("max-players", "最大玩家数", "同时在线人数上限", PropType.Int, PropGroup.Basic, default = "20"),
    PropertySpec("server-port", "服务器端口", "Minecraft 服务监听端口", PropType.Int, PropGroup.Basic, default = "25565"),
    PropertySpec("server-ip", "服务器 IP", "留空默认监听所有网卡", PropType.Text, PropGroup.Basic),
    PropertySpec("motd", "服务器描述（MOTD）", "服务器列表中显示的标语", PropType.Text, PropGroup.Basic, default = "A Minecraft Server"),
    PropertySpec("view-distance", "视距", "客户端可看到的区块距离 (3-32)", PropType.Int, PropGroup.Basic, default = "10"),
    PropertySpec("simulation-distance", "模拟距离", "服务器模拟的区块距离 (3-32)", PropType.Int, PropGroup.Basic, default = "10"),
    PropertySpec("max-world-size", "最大世界大小", "世界边界半径（格）", PropType.Int, PropGroup.Basic, default = "29999984"),
    PropertySpec("spawn-protection", "出生点保护", "出生点附近不可破坏半径 (0-16)", PropType.Int, PropGroup.Basic, default = "16"),
    PropertySpec("hardcore", "极限模式", "玩家死亡后永久封禁", PropType.Bool, PropGroup.Basic),
    PropertySpec("enable-command-block", "命令方块", "允许使用命令方块", PropType.Bool, PropGroup.Basic),
    PropertySpec("allow-flight", "允许飞行", "允许在生存模式飞行（防踢）", PropType.Bool, PropGroup.Basic),
    PropertySpec("allow-nether", "允许下界", "生成下界传送门与下界维度", PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("spawn-animals", "生成动物", "刷新动物实体", PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("spawn-monsters", "生成怪物", "刷新敌对怪物实体", PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("spawn-npcs", "生成 NPC", "刷新村民等 NPC 实体", PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("generate-structures", "生成结构", "生成村庄/神殿等结构", PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("level-name", "世界名称", "世界文件夹名称", PropType.Text, PropGroup.Basic, default = "world"),
    PropertySpec("level-seed", "世界种子", "留空随机生成", PropType.Text, PropGroup.Basic),
    PropertySpec("level-type", "世界类型", "地形生成方式", PropType.Enum, PropGroup.Basic,
        listOf(
            "普通" to "minecraft:normal",
            "超平坦" to "minecraft:flat",
            "巨大生物群系" to "minecraft:large_biomes",
            "放大化" to "minecraft:amplified",
            "单一生物群系" to "minecraft:single_biome_surface"
        ), "minecraft:normal"),
    PropertySpec("force-gamemode", "强制游戏模式", "玩家重进时强制套用默认模式", PropType.Bool, PropGroup.Basic),
    PropertySpec("max-tick-time", "最大 Tick 时间", "服务器卡顿超过该毫秒数会触发崩溃保护", PropType.Int, PropGroup.Basic, default = "60000"),
    PropertySpec("enable-status", "服务器列表状态", "在多人游戏列表显示在线状态", PropType.Bool, PropGroup.Basic, default = "true"),
    PropertySpec("hide-online-players", "隐藏在线玩家", "服务器列表不显示在线玩家", PropType.Bool, PropGroup.Basic),
    PropertySpec("op-permission-level", "OP 权限等级", "OP 命令权限等级 (1-4)", PropType.Int, PropGroup.Basic, default = "4"),
    PropertySpec("function-permission-level", "函数权限等级", "数据包函数权限等级 (1-4)", PropType.Int, PropGroup.Basic, default = "2"),
    PropertySpec("rate-limit", "速率限制", "0 表示不限制", PropType.Int, PropGroup.Basic),
    PropertySpec("require-resource-pack", "强制资源包", "拒绝使用资源包的玩家会被踢出", PropType.Bool, PropGroup.Basic),
    PropertySpec("resource-pack", "资源包地址", "玩家加入时提示下载的资源包 URL", PropType.Text, PropGroup.Basic),
    PropertySpec("player-idle-timeout", "玩家空闲超时", "空闲多少分钟踢出，0 禁用", PropType.Int, PropGroup.Basic),
    PropertySpec("network-compression-threshold", "网络压缩阈值", "超过该字节数压缩网络包，-1 禁用", PropType.Int, PropGroup.Basic, default = "256"),
    PropertySpec("max-chained-neighbor-updates", "链式邻居更新上限", "防止高频红石更新导致的性能问题", PropType.Int, PropGroup.Basic, default = "1000000"),

    // ── 高级配置 ──
    PropertySpec("accepts-transfers", "接受玩家转移", "接受从其他服务器转移来的玩家", PropType.Bool),
    PropertySpec("broadcast-console-to-ops", "控制台广播给 OP", "控制台命令广播给在线 OP", PropType.Bool, default = "true"),
    PropertySpec("broadcast-rcon-to-ops", "RCON 广播给 OP", "RCON 命令广播给在线 OP", PropType.Bool, default = "true"),
    PropertySpec("bug-report-link", "Bug 报告链接", "崩溃报告页面展示的链接", PropType.Text),
    PropertySpec("chat-spam-threshold-seconds", "聊天刷屏阈值", "聊天消息间隔阈值（秒）", PropType.Int, default = "10"),
    PropertySpec("command-spam-threshold-seconds", "命令刷屏阈值", "命令发送间隔阈值（秒）", PropType.Int, default = "10"),
    PropertySpec("debug", "调试模式", "输出详细调试日志", PropType.Bool),
    PropertySpec("enable-code-of-conduct", "行为准则提示", "启用微软行为准则功能", PropType.Bool),
    PropertySpec("enable-jmx-monitoring", "JMX 监控", "启用 JMX 远程监控", PropType.Bool),
    PropertySpec("enable-query", "启用 Query", "GameSpy4 Query 协议", PropType.Bool),
    PropertySpec("enable-rcon", "启用 RCON", "远程控制协议", PropType.Bool),
    PropertySpec("entity-broadcast-range-percentage", "实体广播范围", "实体同步距离百分比 (10-1000)", PropType.Int, default = "100"),
    PropertySpec("generator-settings", "生成器设置", "自定义地形生成 JSON", PropType.Text, default = "{}"),
    PropertySpec("initial-disabled-packs", "初始禁用数据包", "启动时禁用的数据包列表", PropType.Text),
    PropertySpec("initial-enabled-packs", "初始启用数据包", "启动时启用的数据包列表", PropType.Text, default = "vanilla"),
    PropertySpec("log-ips", "记录玩家 IP", "日志中记录玩家 IP 地址", PropType.Bool, default = "true"),
    PropertySpec("management-server-allowed-origins", "管理服务器来源", "允许连接管理服务器的来源", PropType.Text),
    PropertySpec("management-server-enabled", "启用管理服务器", "启用管理服务器（JMX 新接口）", PropType.Bool),
    PropertySpec("management-server-host", "管理服务器主机", "管理服务器监听地址", PropType.Text, default = "localhost"),
    PropertySpec("management-server-port", "管理服务器端口", "0 表示自动分配", PropType.Int),
    PropertySpec("management-server-secret", "管理服务器密钥", "管理服务器认证密钥", PropType.Text),
    PropertySpec("management-server-tls-enabled", "管理服务器 TLS", "启用 TLS 加密连接", PropType.Bool, default = "true"),
    PropertySpec("management-server-tls-keystore", "管理服务器密钥库", "TLS 密钥库路径", PropType.Text),
    PropertySpec("management-server-tls-keystore-password", "管理服务器密钥库密码", "TLS 密钥库密码", PropType.Text),
    PropertySpec("pause-when-empty-seconds", "空服暂停", "无玩家多少秒后暂停，-1 禁用", PropType.Int, default = "-1"),
    PropertySpec("prevent-proxy-connections", "阻止代理连接", "阻止玩家使用代理/虚拟专用网络连接", PropType.Bool),
    PropertySpec("query.port", "Query 端口", "GameSpy4 查询端口", PropType.Int, default = "25565"),
    PropertySpec("rcon.password", "RCON 密码", "远程控制密码，留空禁用", PropType.Text),
    PropertySpec("rcon.port", "RCON 端口", "远程控制端口", PropType.Int, default = "25575"),
    PropertySpec("region-file-compression", "区域文件压缩", "区块文件压缩算法", PropType.Text, default = "deflate"),
    PropertySpec("resource-pack-id", "资源包 ID", "资源包 UUID", PropType.Text),
    PropertySpec("resource-pack-prompt", "资源包提示", "拒绝资源包时的提示文本", PropType.Text),
    PropertySpec("resource-pack-sha1", "资源包 SHA1", "资源包校验值", PropType.Text),
    PropertySpec("status-heartbeat-interval", "状态心跳间隔", "状态心跳发送间隔（秒）", PropType.Int),
    PropertySpec("sync-chunk-writes", "同步区块写入", "同步写入区块数据", PropType.Bool, default = "true"),
    PropertySpec("text-filtering-config", "文本过滤配置", "聊天文本过滤配置", PropType.Text),
    PropertySpec("text-filtering-version", "文本过滤版本", "文本过滤协议版本", PropType.Int),
    PropertySpec("use-native-transport", "原生传输", "使用 Linux 原生网络传输", PropType.Bool, default = "true")
)


@Composable
fun PropertiesScreen(vm: McViewModel, onBack: () -> Unit, showBackBar: Boolean = true) {
    val loaded by vm.serverProperties.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val config by vm.config.collectAsState()

    // 本地编辑状态：进入页面后由 ViewModel 加载结果同步，控件修改时本地更新
    var props by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showRestartDialog by remember { mutableStateOf(false) }

    // 进入页面时加载 server.properties
    LaunchedEffect(Unit) { vm.loadServerProperties() }
    // ViewModel 加载完成后同步到本地编辑状态
    LaunchedEffect(loaded) { props = loaded }

    Column(modifier = Modifier.fillMaxSize()) {
        // 返回栏（白底覆盖状态栏，配合全屏展示）；作为底部导航 tab 时隐藏
        if (showBackBar) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text(stringResource(R.string.s541), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
        }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = "Server Properties", title = "服务器属性", statusBarPadding = !showBackBar)

            // 当前核心提示：每个命名服务器拥有独立配置
            val activeCore = config.installedCores.find { it.name == config.activeCoreName }
            Text(
                "当前核心：${activeCore?.name ?: "未选择"} · 各服务器核心配置相互独立",
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(8.dp))

            // ── 基础配置（常用参数，完整展示） ──
            McCard(title = PropGroup.Basic.label) {
                propertySpecs.filter { it.group == PropGroup.Basic }.forEach { spec ->
                    renderProperty(spec, props) { v ->
                        props = props.toMutableMap().apply { put(spec.key, v) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ── 高级配置（全部剩余参数） ──
            McCard(title = PropGroup.Advanced.label) {
                propertySpecs.filter { it.group == PropGroup.Advanced }.forEach { spec ->
                    renderProperty(spec, props) { v ->
                        props = props.toMutableMap().apply { put(spec.key, v) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // 兜底：未收录的新参数（保证完整加载展示，不丢失）
                val knownKeys = propertySpecs.map { it.key }.toSet()
                val unknownKeys = props.keys.filter { it !in knownKeys }
                if (unknownKeys.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.s1000), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    unknownKeys.forEach { key ->
                        Text(key, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        DebouncedTextField(
                            value = props[key] ?: "",
                            onValueChange = { v -> props = props.toMutableMap().apply { put(key, v) } },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 底部固定保存按钮
        Button(
            onClick = {
                if (serverState.isRunning) {
                    showRestartDialog = true
                } else {
                    vm.saveServerProperties(props)
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(stringResource(R.string.s328), color = Color.White, fontWeight = FontWeight.Bold)
        }
    }

    // 服务器运行中时保存的重启提示弹窗
    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text(stringResource(R.string.s581), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.s1001)) },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    vm.saveServerProperties(props)
                }) { Text(stringResource(R.string.s1002), color = Indigo) }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text(stringResource(R.string.s402), color = Coral)
                }
            }
        )
    }
}

/** 从 props 读取布尔值，key 不存在或解析失败时返回 default */
private fun boolOf(props: Map<String, String>, key: String, default: Boolean): Boolean {
    return props[key]?.let { it.equals("true", ignoreCase = true) } ?: default
}

/** 带标题/副标题的开关行 */
@Composable
private fun PropertySwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Indigo,
                checkedTrackColor = IndigoSoft
            )
        )
    }
}

/** 带标签的数字输入框 */
@Composable
private fun LabeledNumberField(
    label: String,
    desc: String = "",
    value: String,
    onValueChange: (String) -> Unit
) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (desc.isNotEmpty()) Text(desc, color = Muted, fontSize = 10.sp)
    Spacer(Modifier.height(4.dp))
    DebouncedTextField(
        value = value,
        onValueChange = onValueChange,
        sanitize = { it.filter(Char::isDigit) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 按类型渲染单个属性（数据驱动） */
@Composable
private fun renderProperty(
    spec: PropertySpec,
    props: Map<String, String>,
    onChange: (String) -> Unit
) {
    when (spec.type) {
        PropType.Bool -> PropertySwitch(
            title = spec.label,
            subtitle = spec.desc,
            checked = boolOf(props, spec.key, spec.default.toBoolean()),
            onChange = { onChange(it.toString()) }
        )
        PropType.Int -> LabeledNumberField(
            label = spec.label,
            desc = spec.desc,
            value = props[spec.key] ?: spec.default,
            onValueChange = onChange
        )
        PropType.Enum -> EnumField(
            label = spec.label,
            desc = spec.desc,
            value = props[spec.key] ?: spec.default,
            options = spec.options,
            onValueChange = onChange
        )
        PropType.Text -> LabeledTextField(
            label = spec.label,
            desc = spec.desc,
            value = props[spec.key] ?: spec.default,
            onValueChange = onChange
        )
    }
}

/** 枚举选择（SegPill 流式布局，自动换行） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnumField(
    label: String,
    desc: String,
    value: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit
) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (desc.isNotEmpty()) Text(desc, color = Muted, fontSize = 10.sp)
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (display, v) ->
            SegPill(
                text = display,
                selected = value == v,
                onClick = { onValueChange(v) }
            )
        }
    }
}

/** 带标签的文本输入框 */
@Composable
private fun LabeledTextField(
    label: String,
    desc: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    if (desc.isNotEmpty()) Text(desc, color = Muted, fontSize = 10.sp)
    Spacer(Modifier.height(4.dp))
    DebouncedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
