package com.mineserve.mobile.ui

// 性能修改理由：输入过程使用本地状态，避免异步保存回读打断输入法的光标位置。
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.data.StepStatus
import kotlinx.coroutines.delay
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.CoralSoft
import com.mineserve.mobile.ui.theme.FieldGray
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoDark
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Line
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.MintBright
import com.mineserve.mobile.ui.theme.MintSoft
import com.mineserve.mobile.ui.theme.Muted

/**
 * 顶部 Header：参考界面 eyebrow + h1。
 * 默认以白色背景覆盖状态栏区域（配合全屏），内容避开状态栏文字；
 * 若该页面顶部已有返回栏承接状态栏避让，可传 statusBarPadding = false。
 */
@Composable
fun HeaderBlock(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    statusBarPadding: Boolean = true,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
                .padding(horizontal = 14.dp, vertical = 20.dp)
        ) {
            Text(
                text = eyebrow,
                color = Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
        }
    }
}

/** 子页面统一返回栏：白底延伸到状态栏后面（状态栏透明）+ 返回按钮 + 标题 */
@Composable
fun BackBar(title: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.s404))
            }
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

/** 统一空状态提示：图标 + 灰字文案，垂直居中 */
@Composable
fun EmptyHint(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(text, color = Muted, fontSize = 13.sp)
    }
}

/**
 * Hero 卡片：渐变背景 + 状态行 + 紧凑指标行（TPS / 在线 / 进程内存 / 运行时长）
 */
@Composable
fun HeroBlock(
    state: ServerState,
    coreLabel: String,
    cpuPercent: Int? = null,
    onlineModeEnabled: Boolean = false,
    onRefresh: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.linearGradient(colors = listOf(Indigo, IndigoDark))
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column {
            // 启动中：isRunning 且尚未完成启动（runningSinceMs == 0）
            val isStarting = state.isRunning && state.runningSinceMs == 0L
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 状态指示点：启动中橙色，运行中亮绿，未运行半透明白
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isStarting -> Color(0xFFFFA726)
                                    state.isRunning -> MintBright
                                    else -> Color.White.copy(alpha = 0.5f)
                                }
                            )
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        when {
                            isStarting -> stringResource(R.string.hero_starting, coreLabel)
                            state.isRunning -> stringResource(R.string.hero_running, coreLabel)
                            else -> stringResource(R.string.hero_stopped)
                        },
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onRefresh != null) {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.hero_refresh),
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                HeroStat(
                    if (state.isRunning) String.format("%.1f", state.tps) else "--",
                    stringResource(R.string.hero_tps)
                )
                HeroStat(
                    if (state.isRunning) "${state.onlinePlayers}/${state.maxPlayers}" else "--/--",
                    stringResource(R.string.hero_online)
                )
                HeroStat(
                    if (state.isRunning && state.usedMemoryMb > 0) formatMemory(state.usedMemoryMb) else "--",
                    stringResource(R.string.hero_memory)
                )
                HeroStat(
                    if (state.isRunning && state.runningSinceMs > 0)
                        formatUptime(android.os.SystemClock.elapsedRealtime() - state.runningSinceMs)
                    else "--",
                    stringResource(R.string.hero_uptime)
                )
                HeroStat(cpuPercent?.let { "$it%" } ?: "--", stringResource(R.string.hero_cpu))
            }
            if (onlineModeEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.hero_online_mode_warning),
                    color = Color(0xFFFF8A80),
                    fontSize = 10.sp
                )
            }
            // 启动中提示：适配启动耗时较长的场景
            if (isStarting) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.startupPhase.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFFFFC857),
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "启动阶段：${state.startupPhase.label}（${(state.startupPhase.progress * 100).toInt()}%）",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
                Text(
                    "准备环境 · 下载依赖 · 启动 Java · 加载核心 · 创建世界 · 启动网络",
                    color = Color.White.copy(alpha = 0.52f),
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    Column(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "$value, $label"
        }
    ) {
        Text(
            value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 9.sp
        )
    }
}

/** 运行时长格式化：秒/分钟/小时 */
@Composable
private fun formatUptime(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h >= 100 -> "${h}h"
        h > 0 -> "${h}h${m}m"
        m > 0 -> stringResource(R.string.sc_minutes, m)
        else -> stringResource(R.string.sc_seconds, totalSec)
    }
}

/**
 * 通用卡片容器：参考界面圆角 18 + 白底 + 1px line 边
 */
@Composable
fun McCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = if (compact) 4.dp else 7.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(if (compact) 12.dp else 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                trailing?.invoke()
            }
            Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
            content()
        }
    }
}

/**
 * 步骤行（参考界面 step-row）
 */
@Composable
fun StepRow(
    name: String,
    status: StepStatus,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
            .clearAndSetSemantics {
                contentDescription = "$name, $tag"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (bg, fg, text) = when (status) {
            StepStatus.Done -> Triple(MintSoft, Mint, "✓")
            StepStatus.Active -> Triple(IndigoSoft, Indigo, "•")
            StepStatus.Wait -> Triple(FieldGray, Muted, "○")
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Text(
            name,
            color = if (status == StepStatus.Wait) Muted else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Text(tag, color = Muted, fontSize = 10.sp)
    }
}

/**
 * 进度条（参考界面 progress-track）
 */
@Composable
fun ProgressTrack(percent: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(FieldGray)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percent / 100f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(Indigo, Color(0xFF6E7FD6))
                    )
                )
        )
    }
}

/**
 * 圆角分段按钮：参考界面 .seg / .on
 */
@Composable
fun SegPill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    unselectedBackground: Color = FieldGray,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Indigo else unselectedBackground)
            .clickable { onClick() }
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.White else Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Pill 按钮：参考界面 .pill-btn
 */
@Composable
fun PillButton(
    text: String,
    install: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val (bg, fg) = if (install) Indigo to Color.White else CoralSoft to Coral
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatMemory(mb: Long): String =
    if (mb >= 1024) String.format("%.1fG", mb / 1024.0) else "${mb}M"

/**
 * 防抖输入框：本地即时编辑 + 300ms 防抖写回外部值，失焦时立即写回。
 *
 * 解决 TextField 直接绑定持久化 StateFlow（如 config）导致的输入延迟与
 * 快速输入文字顺序错乱问题：输入过程完全在本地状态上进行，停顿 300ms
 * 后才把结果回传（快速连续输入只回传最后一次），失焦时立即回传避免丢失。
 *
 * @param sanitize 可选输入清洗函数（如数字框只保留数字），每次输入后应用
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun DebouncedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    sanitize: ((String) -> String)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    textStyle: TextStyle = TextStyle.Default,
    shape: Shape = OutlinedTextFieldDefaults.shape
) {
    var text by remember { mutableStateOf(value) }
    var isFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    // 外部值同步：仅在非编辑状态时同步到本地。
    // 关键：防抖写回后外部 value 的回显更新有延迟，若此时用户已继续输入，
    // 无条件覆盖会把正在输入的内容重置回旧值（快速输入文字丢失/乱序）。
    LaunchedEffect(value) {
        if (!editing && text != value) text = value
    }

    // 输入停顿 300ms 后写回（连续输入只写回最后一次）
    LaunchedEffect(text) {
        if (text == value) return@LaunchedEffect
        delay(300)
        if (text != value) {
            onValueChange(text)
            editing = false
        }
    }

    // 失焦时立即写回，避免切换页面丢失最后输入
    LaunchedEffect(isFocused) {
        if (!isFocused && text != value) {
            onValueChange(text)
            editing = false
        }
        if (isFocused) {
            delay(100)
            bringIntoViewRequester.bringIntoView()
        }
    }

    // 组合移除（切页/销毁）时立即回传未写回内容，避免防抖窗口内输入丢失
    DisposableEffect(Unit) {
        onDispose {
            if (text != value) onValueChange(text)
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { newText ->
            text = sanitize?.invoke(newText) ?: newText
            editing = true
        },
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { isFocused = it.isFocused },
        enabled = enabled,
        singleLine = singleLine,
        label = label,
        placeholder = placeholder,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        textStyle = textStyle,
        shape = shape
    )
}

/** QQ 交流群跳转链接 */
private const val QQ_GROUP_URL =
    "https://qun.qq.com/universal-share/share?ac=1&authKey=ISbTtN7IFJ0ItNdgzSlZ68hWxg136HpWhwOjj%2BRcl55agd85N3DCzBU82z7U8dQT&busi_data=eyJncm91cENvZGUiOiI1OTM2ODIwMzMiLCJ0b2tlbiI6ImJBS1d3WHRabHRBNUJXcHE5d1EzK01SbUZsVXg5ajM4SVdCeGhBZTVBQXNhMGlpck5DWE04azFKWWhSVW1JbTYiLCJ1aW4iOiIxNjcyNDU0ODQifQ%3D%3D&data=mDeXPqhlgK8JWPqiG2MpojgJuRaMiLLUN_czFSB2Yuhhl2mi9r-v-f6C6DzXxyXQY_Nog12BLMt6kJ8aanRlfg&svctype=4&tempid=h5_group_info"

/**
 * QQ 交流群入口卡片：显示群号并提供一键加群按钮。
 */
@Composable
fun QqGroupCard() {
    val context = LocalContext.current
    McCard(title = stringResource(R.string.s1051)) {
        Text(
            stringResource(R.string.s1052),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Indigo
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.s1053),
            color = Muted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_URL)))
                } catch (_: Exception) {
                    // 无浏览器时静默
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.s1054),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
