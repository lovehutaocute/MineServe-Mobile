package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.DiagnosticCheck
import com.mineserve.mobile.data.DiagnosticStatus
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted

@Composable
fun DiagnosticsScreen(vm: McViewModel, onBack: () -> Unit) {
    val report by vm.diagnosticReport.collectAsState()
    val isDiagnosing by vm.isDiagnosing.collectAsState()
    val isRepairing by vm.isRepairingRuntime.collectAsState()

    LaunchedEffect(Unit) { vm.runDiagnostics() }

    Column(Modifier.fillMaxSize()) {
        BackBar(title = "运行诊断", onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(
                eyebrow = "SERVER HEALTH",
                title = "运行诊断",
                statusBarPadding = false
            )
            McCard(title = "诊断结果", compact = true) {
                val summary = when {
                    isRepairing -> "正在执行安全修复，完成后自动复检"
                    isDiagnosing -> "正在读取运行环境和当前服务端状态"
                    report.generatedAtMs == 0L -> "尚未生成诊断结果"
                    report.issueCount == 0 -> "未发现需要处理的问题"
                    else -> "发现 ${report.issueCount} 项需要注意的问题"
                }
                Text(summary, color = if (report.issueCount == 0 && !isDiagnosing && !isRepairing) Mint else Coral, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.runDiagnostics() },
                        enabled = !isDiagnosing && !isRepairing,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)
                    ) { Text("重新诊断", color = Indigo, fontSize = 12.sp) }
                    Button(
                        onClick = { vm.safeRepairRuntime() },
                        enabled = !isDiagnosing && !isRepairing,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo)
                    ) {
                        if (isRepairing) {
                            CircularProgressIndicator(Modifier.size(15.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(if (isRepairing) "修复中" else "一键安全修复", color = Color.White, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("安全修复只补齐运行环境、Java、命令和字体，不会删除服务器、世界、插件、配置、备份或重置 Ubuntu/Termux。", color = Muted, fontSize = 10.sp)
            }
            report.checks.forEach { check -> DiagnosticCheckCard(check) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DiagnosticCheckCard(check: DiagnosticCheck) {
    val color = when (check.status) {
        DiagnosticStatus.Pass -> Mint
        DiagnosticStatus.Warning, DiagnosticStatus.Failed -> Coral
        DiagnosticStatus.Running -> Indigo
        DiagnosticStatus.NotApplicable -> Muted
    }
    McCard(title = check.title, compact = true) {
        Row(verticalAlignment = Alignment.Top) {
            Spacer(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(check.detail, color = Muted, fontSize = 11.sp)
                if (check.repairable) {
                    Spacer(Modifier.height(4.dp))
                    Text("可通过一键安全修复处理", color = Indigo, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
