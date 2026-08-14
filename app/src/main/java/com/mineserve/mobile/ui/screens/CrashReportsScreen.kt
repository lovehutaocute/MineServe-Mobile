package com.mineserve.mobile.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.server.CrashReportAnalyzer
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Muted

@Composable
fun CrashReportsScreen(vm: McViewModel, onBack: () -> Unit) {
    val reports by vm.crashReports.collectAsState()
    val content by vm.currentCrashContent.collectAsState()
    val analysis by vm.currentCrashAnalysis.collectAsState()
    LaunchedEffect(Unit) { vm.loadCrashReports() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("崩溃报告", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        if (reports.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无崩溃报告", color = Muted) }
        else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(reports, key = { it.path }) { report ->
                val label = remember(report.preview) { CrashReportAnalyzer.analyze(report.preview).primaryLabel }
                ListItem(headlineContent = { Text(report.fileName, maxLines = 1) }, supportingContent = { Text("$label | ${report.createdText} | ${report.sizeText}", fontSize = 11.sp) }, leadingContent = { Icon(Icons.Outlined.Warning, null, tint = Coral) }, modifier = Modifier.clickable { vm.readCrashReport(report.fileName) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun CrashReportDialog(content: String, analysis: CrashReportAnalyzer.Analysis, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var raw by remember { mutableStateOf(false) }
    val summary = buildString {
        appendLine(analysis.title)
        analysis.exitCode?.let { appendLine("退出码: $it") }
        analysis.causedBy.forEach { appendLine("Caused by: $it") }
        analysis.findings.forEach { appendLine("${it.label}: ${it.detail}"); appendLine("建议: ${it.suggestion}") }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (raw) "原始崩溃报告" else "崩溃分析") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !raw, onClick = { raw = false }, label = { Text("摘要") })
                FilterChip(selected = raw, onClick = { raw = true }, label = { Text("原始报告") })
            }
            Spacer(Modifier.height(8.dp))
            Text(if (raw) content else summary, fontFamily = if (raw) FontFamily.Monospace else FontFamily.Default, fontSize = 12.sp, modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
        }
    }, confirmButton = {
        Row {
            IconButton(onClick = { copy(context, if (raw) content else summary) }) { Icon(Icons.Outlined.ContentCopy, "复制", tint = Indigo) }
            IconButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, content) }, "导出崩溃报告")) }) { Icon(Icons.Outlined.IosShare, "导出", tint = Indigo) }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    })
}

private fun copy(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("Crash report", text))
}
