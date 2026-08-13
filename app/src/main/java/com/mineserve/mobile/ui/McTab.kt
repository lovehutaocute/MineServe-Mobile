package com.mineserve.mobile.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.ui.graphics.vector.ImageVector
import com.mineserve.mobile.R

/** 底部导航 Tab：labelRes 为本地化资源 id（渲染处用 stringResource 取当前语言） */
enum class McTab(val labelRes: Int, val icon: ImageVector) {
    Dashboard(R.string.tab_dashboard, Icons.Outlined.Dashboard),
    Download(R.string.tab_download, Icons.Outlined.Download),
    ServerManagement(R.string.tab_server_management, Icons.Outlined.Dns),
    Players(R.string.tab_players, Icons.Outlined.People),
    Files(R.string.tab_files, Icons.Outlined.Folder),
    Terminal(R.string.tab_terminal, Icons.Outlined.Terminal),
    Settings(R.string.tab_settings, Icons.Outlined.Settings)
}
