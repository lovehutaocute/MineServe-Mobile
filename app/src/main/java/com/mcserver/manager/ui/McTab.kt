package com.mcserver.manager.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class McTab(val label: String, val icon: ImageVector) {
    Dashboard("概览", Icons.Outlined.Dashboard),
    Download("下载", Icons.Outlined.Download),
    Players("玩家", Icons.Outlined.People),
    Plugins("插件与模组", Icons.Outlined.Extension),
    Files("文件", Icons.Outlined.Folder),
    Network("网络", Icons.Outlined.Cloud),
    Backup("备份", Icons.Outlined.Backup),
    Settings("设置", Icons.Outlined.Settings)
}

