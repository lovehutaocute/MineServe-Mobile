package com.mineserve.mobile.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.mineserve.mobile.R

/** 底部导航 Tab：labelRes 为本地化资源 id（渲染处用 stringResource 取当前语言） */
enum class McTab(val labelRes: Int, val icon: ImageVector) {
    Dashboard(R.string.tab_dashboard, Icons.Outlined.Dashboard),
    Download(R.string.tab_download, Icons.Outlined.Download),
    Players(R.string.tab_players, Icons.Outlined.People),
    Plugins(R.string.tab_plugins, Icons.Outlined.Extension),
    Files(R.string.tab_files, Icons.Outlined.Folder),
    Network(R.string.tab_network, Icons.Outlined.Cloud),
    Backup(R.string.tab_backup, Icons.Outlined.Backup),
    Config(R.string.tab_config, Icons.Outlined.Tune),
    Settings(R.string.tab_settings, Icons.Outlined.Settings)
}

