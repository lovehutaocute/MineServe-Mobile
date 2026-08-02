package com.mcserver.manager.ui.screens

import androidx.compose.ui.res.stringResource
import com.mcserver.manager.R

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.theme.Coral
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Mint
import com.mcserver.manager.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun FileManagerScreen(vm: McViewModel, onOpenMtGuide: () -> Unit = {}) {
    val context = LocalContext.current
    val files by vm.fileList.collectAsState()
    val currentPath by vm.currentPath.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showDeleteConfirm by remember { mutableStateOf<File?>(null) }
    var showNewDirDialog by remember { mutableStateOf(false) }
    var newDirName by remember { mutableStateOf("") }

    // SAF 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val targetDir = File(currentPath)
            vm.uploadFile(uri, targetDir)
        }
    }

    // 导出目标（用户点击的文件/文件夹），CreateDocument 回调用
    var exportTarget by remember { mutableStateOf<File?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { target -> exportTarget?.let { vm.exportPathToUri(it, target) } }
        exportTarget = null
    }
    // 整服务器导出选择器
    val exportServerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? -> uri?.let { vm.exportServerToUri(it) } }

    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    // 首次进入时加载根目录
    LaunchedEffect(isBootstrapped) {
        if (isBootstrapped && currentPath.isEmpty()) {
            vm.loadFilesRoot()
        }
    }

    androidx.compose.material3.Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HeaderBlock(eyebrow = "File Manager", title = "文件管理")

            // MT 管理器 + 导出服务器
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenMtGuide,
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.s489), fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { exportServerLauncher.launch("服务器导出.zip") },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.s491), fontSize = 12.sp, color = Indigo)
                }
            }

            // MT 管理器教程视频入口（在线播放，不打包进安装包）
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TUTORIAL_VIDEO_URL)))
                    } catch (e: Exception) {
                        // 无浏览器/网络异常时静默
                    }
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                Text(stringResource(R.string.s492), fontSize = 12.sp, color = Indigo)
            }

            // 路径导航栏
            McCard(title = "当前路径") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val rootPath = vm.fileManagerRoot.absolutePath
                    val relPath = if (currentPath.startsWith(rootPath)) {
                        currentPath.removePrefix(rootPath).removePrefix(File.separator)
                    } else {
                        currentPath
                    }
                    val displayPath = if (relPath.isEmpty()) "servers/" else "servers/$relPath"

                    Text(
                        displayPath, color = Muted, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    // 向上按钮
                    IconButton(onClick = { vm.navigateUp() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.ArrowUpward, "上级目录", tint = Indigo, modifier = Modifier.size(18.dp))
                    }
                    // 刷新按钮
                    IconButton(onClick = { vm.refreshFiles() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Refresh, "刷新", tint = Indigo, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 返回上级
                    val currentDir = File(currentPath)
                    val parentDir = currentDir.parentFile
                    val canGoUp = parentDir != null &&
                        currentDir.absolutePath != vm.fileManagerRoot.absolutePath &&
                        currentDir.absolutePath.contains(vm.fileManagerRoot.absolutePath)

                    Button(
                        onClick = {
                            if (canGoUp && parentDir != null) {
                                vm.loadFiles(parentDir!!)
                            }
                        },
                        enabled = canGoUp,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回上级",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.s496), color = Color.White, fontSize = 11.sp)
                    }

                    // 上传文件
                    Button(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Mint),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Outlined.Upload,
                            contentDescription = "上传",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.s497), color = Color.White, fontSize = 11.sp)
                    }

                    // 新建文件夹
                    Button(
                        onClick = {
                            newDirName = ""
                            showNewDirDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Outlined.CreateNewFolder,
                            contentDescription = "新建文件夹",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.s499), color = Color.White, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 文件列表
            if (files.isEmpty()) {
                McCard(title = "文件列表") {
                    Text(
                        if (currentPath.isEmpty()) "正在加载..." else "目录为空",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(files, key = { it.path }) { entry ->
                        FileItemRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) {
                                    vm.loadFiles(File(entry.path))
                                }
                            },
                            onExport = {
                                exportTarget = File(entry.path)
                                exportLauncher.launch(if (entry.isDirectory) "${entry.name}.zip" else entry.name)
                            },
                            onDelete = {
                                showDeleteConfirm = File(entry.path)
                            }
                        )
                    }
                }
            }
        }
    }

    // 删除确认对话框
    showDeleteConfirm?.let { fileToDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.s401), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    buildString {
                        append("即将删除${if (fileToDelete.isDirectory) "文件夹" else "文件"}：\n")
                        append(fileToDelete.name)
                        if (fileToDelete.isDirectory) {
                            append("\n\n该文件夹下的所有内容将被一并删除。")
                        }
                        append("\n\n此操作不可撤销。")
                    },
                    color = Muted,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteFile(fileToDelete)
                        showDeleteConfirm = null
                    }
                ) {
                    Text(stringResource(R.string.s339), color = Coral, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = null }
                ) {
                    Text(stringResource(R.string.s402), color = Muted)
                }
            }
        )
    }

    // 新建文件夹对话框
    if (showNewDirDialog) {
        AlertDialog(
            onDismissRequest = { showNewDirDialog = false },
            title = { Text(stringResource(R.string.s498), fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newDirName,
                    onValueChange = { newDirName = it },
                    label = { Text(stringResource(R.string.s506), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newDirName.isNotBlank()) {
                            vm.createDirectory(File(currentPath), newDirName.trim())
                            showNewDirDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.s507), color = Indigo, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNewDirDialog = false }
                ) {
                    Text(stringResource(R.string.s402), color = Muted)
                }
            }
        )
    }
}

@Composable
private fun FileItemRow(
    entry: McViewModel.FileEntry,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 文件类型图标
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (entry.isDirectory) Mint.copy(alpha = 0.15f) else IndigoSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                contentDescription = null,
                tint = if (entry.isDirectory) Mint else Indigo,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.size(10.dp))

        // 文件名和信息
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                // 自动功能注释（如 world【主世界存档文件夹】）
                fileAnnotation(entry)?.let { ann ->
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "【$ann】",
                        color = Muted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                if (entry.isDirectory) entry.modifiedText else "${entry.sizeText} · ${entry.modifiedText}",
                color = Muted,
                fontSize = 11.sp,
                maxLines = 1
            )
        }

        // 导出按钮
        IconButton(onClick = onExport, modifier = Modifier.padding(start = 2.dp)) {
            Icon(
                Icons.Outlined.FileDownload,
                contentDescription = "导出",
                tint = Indigo,
                modifier = Modifier.size(18.dp)
            )
        }

        // 删除按钮
        TextButton(
            onClick = onDelete,
            modifier = Modifier.padding(start = 4.dp)
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除",
                tint = Coral,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── 服务器目录文件/文件夹中文功能注释 ────────────────────────────────

/** MT 管理器教程视频（在线播放） */
private const val TUTORIAL_VIDEO_URL =
    "https://img.remit.ee/api/file/BAACAgUAAyEGAASHRsPbAAEYWwdqbtydlO6_o-rdvwpJ3O92AfEtHQACbB0AAh7GeVdh2nS2uFg_6T0E.mp4"

private val fileAnnotations = mapOf(
    "server.jar" to "服务端核心文件",
    "server.properties" to "服务器核心配置文件",
    "ops.json" to "OP（管理员）列表",
    "whitelist.json" to "白名单列表",
    "banned-players.json" to "封禁玩家列表",
    "banned-ips.json" to "封禁 IP 列表",
    "eula.txt" to "最终用户许可协议（EULA）",
    "usercache.json" to "玩家 UUID 缓存",
    "usernamecache.json" to "用户名缓存",
    "permissions.json" to "权限配置",
    "help.yml" to "帮助命令配置",
    "bukkit.yml" to "Bukkit 插件服务器配置",
    "spigot.yml" to "Spigot 服务器配置",
    "paper.yml" to "Paper 服务器配置",
    "commands.yml" to "命令别名配置",
    "seedcache" to "世界种子缓存",
    "version_history.json" to "版本历史记录",
    "latest.log" to "最新运行日志",
    "debug.log" to "调试日志"
)

private val dirAnnotations = mapOf(
    "world" to "主世界存档文件夹",
    "world_nether" to "下界存档文件夹",
    "world_the_end" to "末地存档文件夹",
    "logs" to "服务器运行日志文件夹",
    "log" to "服务器日志文件夹（旧版）",
    "plugins" to "插件文件夹",
    "config" to "插件配置文件文件夹",
    "versions" to "服务端版本文件夹",
    "libraries" to "服务端依赖库文件夹",
    "crash-reports" to "崩溃报告文件夹",
    "cache" to "缓存文件夹",
    "generated" to "生成数据文件夹",
    "datapacks" to "数据包文件夹",
    "world_backup" to "世界备份文件夹"
)

/** 返回文件/文件夹的中文功能注释，未知名称返回 null */
private fun fileAnnotation(entry: McViewModel.FileEntry): String? {
    val key = entry.name.lowercase()
    return if (entry.isDirectory) dirAnnotations[key] else fileAnnotations[key]
}
