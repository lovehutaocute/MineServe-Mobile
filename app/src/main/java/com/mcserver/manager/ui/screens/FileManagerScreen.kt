package com.mcserver.manager.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HeaderBlock(eyebrow = "File Manager", title = "文件管理")

            // MT 管理器按钮
            Button(
                onClick = onOpenMtGuide,
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text("📁 MT 管理器管理文件", fontSize = 13.sp)
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
                        Text("上级", color = Color.White, fontSize = 11.sp)
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
                        Text("上传", color = Color.White, fontSize = 11.sp)
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
                        Text("新建", color = Color.White, fontSize = 11.sp)
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
            title = { Text("确认删除", fontWeight = FontWeight.Bold) },
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
                    Text("删除", color = Coral, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = null }
                ) {
                    Text("取消", color = Muted)
                }
            }
        )
    }

    // 新建文件夹对话框
    if (showNewDirDialog) {
        AlertDialog(
            onDismissRequest = { showNewDirDialog = false },
            title = { Text("新建文件夹", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newDirName,
                    onValueChange = { newDirName = it },
                    label = { Text("文件夹名称", fontSize = 12.sp) },
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
                    Text("创建", color = Indigo, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNewDirDialog = false }
                ) {
                    Text("取消", color = Muted)
                }
            }
        )
    }
}

@Composable
private fun FileItemRow(
    entry: McViewModel.FileEntry,
    onClick: () -> Unit,
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
            Text(
                entry.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (entry.isDirectory) entry.modifiedText else "${entry.sizeText} · ${entry.modifiedText}",
                color = Muted,
                fontSize = 11.sp,
                maxLines = 1
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
