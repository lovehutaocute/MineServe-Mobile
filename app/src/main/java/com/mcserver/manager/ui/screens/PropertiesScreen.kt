package com.mcserver.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.OutlinedTextField
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
import com.mcserver.manager.ui.theme.Coral
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Muted

@Composable
fun PropertiesScreen(vm: McViewModel, onBack: () -> Unit) {
    val loaded by vm.serverProperties.collectAsState()
    val serverState by vm.serverState.collectAsState()

    // 本地编辑状态：进入页面后由 ViewModel 加载结果同步，控件修改时本地更新
    var props by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showRestartDialog by remember { mutableStateOf(false) }

    // 进入页面时加载 server.properties
    LaunchedEffect(Unit) { vm.loadServerProperties() }
    // ViewModel 加载完成后同步到本地编辑状态
    LaunchedEffect(loaded) { props = loaded }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text("返回设置", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = "Server Properties", title = "服务器属性")

            // ── 基本设置 ──
            McCard(title = "基本设置") {
                // 难度
                Text("难度", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val difficulty = props["difficulty"] ?: "easy"
                    listOf(
                        "和平" to "peaceful",
                        "简单" to "easy",
                        "普通" to "normal",
                        "困难" to "hard"
                    ).forEach { (label, value) ->
                        SegPill(
                            text = label,
                            selected = difficulty == value,
                            modifier = Modifier.weight(1f),
                            onClick = { props = props.toMutableMap().apply { put("difficulty", value) } }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                // 游戏模式
                Text("游戏模式", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val gamemode = props["gamemode"] ?: "survival"
                    listOf(
                        "生存" to "survival",
                        "创造" to "creative",
                        "冒险" to "adventure",
                        "旁观" to "spectator"
                    ).forEach { (label, value) ->
                        SegPill(
                            text = label,
                            selected = gamemode == value,
                            modifier = Modifier.weight(1f),
                            onClick = { props = props.toMutableMap().apply { put("gamemode", value) } }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                // PVP
                PropertySwitch(
                    title = "PVP",
                    subtitle = "允许玩家之间互相攻击",
                    checked = boolOf(props, "pvp", true),
                    onChange = { v -> props = props.toMutableMap().apply { put("pvp", v.toString()) } }
                )
                Spacer(Modifier.height(8.dp))

                // 白名单
                PropertySwitch(
                    title = "白名单",
                    subtitle = "仅白名单内玩家可加入服务器",
                    checked = boolOf(props, "white-list", false),
                    onChange = { v -> props = props.toMutableMap().apply { put("white-list", v.toString()) } }
                )
                Spacer(Modifier.height(8.dp))

                // 在线模式
                PropertySwitch(
                    title = "在线模式",
                    subtitle = "关闭可让盗版客户端加入（正版验证）",
                    checked = boolOf(props, "online-mode", true),
                    onChange = { v -> props = props.toMutableMap().apply { put("online-mode", v.toString()) } }
                )
            }

            // ── 世界设置 ──
            McCard(title = "世界设置") {
                LabeledNumberField(
                    label = "最大玩家数",
                    value = props["max-players"] ?: "20",
                    onValueChange = { v -> props = props.toMutableMap().apply { put("max-players", v) } }
                )
                Spacer(Modifier.height(12.dp))
                LabeledNumberField(
                    label = "视距 (3-32)",
                    value = props["view-distance"] ?: "10",
                    onValueChange = { v -> props = props.toMutableMap().apply { put("view-distance", v) } }
                )
                Spacer(Modifier.height(12.dp))
                LabeledNumberField(
                    label = "模拟距离 (3-32)",
                    value = props["simulation-distance"] ?: "10",
                    onValueChange = { v -> props = props.toMutableMap().apply { put("simulation-distance", v) } }
                )
                Spacer(Modifier.height(12.dp))
                LabeledNumberField(
                    label = "最大世界大小",
                    value = props["max-world-size"] ?: "29999984",
                    onValueChange = { v -> props = props.toMutableMap().apply { put("max-world-size", v) } }
                )
            }

            // ── 性能设置 ──
            McCard(title = "性能设置") {
                PropertySwitch(
                    title = "允许飞行",
                    subtitle = "允许玩家在生存模式下飞行",
                    checked = boolOf(props, "allow-flight", false),
                    onChange = { v -> props = props.toMutableMap().apply { put("allow-flight", v.toString()) } }
                )
                Spacer(Modifier.height(8.dp))
                PropertySwitch(
                    title = "允许下界",
                    subtitle = "生成下界传送门与下界维度",
                    checked = boolOf(props, "allow-nether", true),
                    onChange = { v -> props = props.toMutableMap().apply { put("allow-nether", v.toString()) } }
                )
                Spacer(Modifier.height(8.dp))
                PropertySwitch(
                    title = "生成动物",
                    subtitle = "刷新动物实体",
                    checked = boolOf(props, "spawn-animals", true),
                    onChange = { v -> props = props.toMutableMap().apply { put("spawn-animals", v.toString()) } }
                )
                Spacer(Modifier.height(8.dp))
                PropertySwitch(
                    title = "生成怪物",
                    subtitle = "刷新敌对怪物实体",
                    checked = boolOf(props, "spawn-monsters", true),
                    onChange = { v -> props = props.toMutableMap().apply { put("spawn-monsters", v.toString()) } }
                )
                Spacer(Modifier.height(8.dp))
                PropertySwitch(
                    title = "生成 NPC",
                    subtitle = "刷新村民等 NPC 实体",
                    checked = boolOf(props, "spawn-npcs", true),
                    onChange = { v -> props = props.toMutableMap().apply { put("spawn-npcs", v.toString()) } }
                )
            }

            // ── 服务器地址 ──
            McCard(title = "服务器地址") {
                LabeledNumberField(
                    label = "服务器端口",
                    value = props["server-port"] ?: "25565",
                    onValueChange = { v -> props = props.toMutableMap().apply { put("server-port", v) } }
                )
                Spacer(Modifier.height(12.dp))
                Text("服务器 IP", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = props["server-ip"] ?: "",
                    onValueChange = { v -> props = props.toMutableMap().apply { put("server-ip", v) } },
                    singleLine = true,
                    placeholder = { Text("留空默认监听所有网卡") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("MOTD（服务器描述）", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = props["motd"] ?: "A Minecraft Server",
                    onValueChange = { v -> props = props.toMutableMap().apply { put("motd", v) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── 高级设置 ──
            McCard(title = "高级设置") {
                LabeledNumberField(
                    label = "出生点保护半径 (0-16)",
                    value = props["spawn-protection"] ?: "16",
                    onValueChange = { v -> props = props.toMutableMap().apply { put("spawn-protection", v) } }
                )
                Spacer(Modifier.height(12.dp))
                LabeledNumberField(
                    label = "网络压缩阈值",
                    value = props["network-compression-threshold"] ?: "256",
                    onValueChange = { v -> props = props.toMutableMap().apply { put("network-compression-threshold", v) } }
                )
                Spacer(Modifier.height(12.dp))
                LabeledNumberField(
                    label = "玩家空闲超时(分钟,0=禁用)",
                    value = props["player-idle-timeout"] ?: "0",
                    onValueChange = { v -> props = props.toMutableMap().apply { put("player-idle-timeout", v) } }
                )
                Spacer(Modifier.height(8.dp))
                PropertySwitch(
                    title = "极限模式",
                    subtitle = "死亡后永久封禁",
                    checked = boolOf(props, "hardcore", false),
                    onChange = { v -> props = props.toMutableMap().apply { put("hardcore", v.toString()) } }
                )
                Spacer(Modifier.height(8.dp))
                PropertySwitch(
                    title = "命令方块",
                    subtitle = "启用命令方块",
                    checked = boolOf(props, "enable-command-block", false),
                    onChange = { v -> props = props.toMutableMap().apply { put("enable-command-block", v.toString()) } }
                )
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
            Text("保存", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }

    // 服务器运行中时保存的重启提示弹窗
    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = { Text("提示", fontWeight = FontWeight.Bold) },
            text = { Text("部分属性需重启服务器才生效，是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showRestartDialog = false
                    vm.saveServerProperties(props)
                }) { Text("继续", color = Indigo) }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text("取消", color = Coral)
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
    value: String,
    onValueChange: (String) -> Unit
) {
    Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}
