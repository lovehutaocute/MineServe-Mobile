package com.mcserver.manager.ui.screens

import androidx.compose.ui.res.stringResource
import com.mcserver.manager.R

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
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

/**
 * 下载慢帮助页面：提供切换下载源、使用代理、切换网络等解决方案
 */
@Composable
fun DownloadHelpScreen(vm: McViewModel, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 返回栏（白底覆盖状态栏，配合全屏展示）
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
            Text(stringResource(R.string.s404), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
        }
        HeaderBlock(eyebrow = "Help", title = "下载慢怎么办？", statusBarPadding = false)

        // 介绍
        McCard(title = "为什么下载会慢？") {
            Text(
                "本应用需要从 GitHub 下载运行环境（约 50MB）和服务端核心（约 50MB）。GitHub 服务器在国外，国内访问可能很慢甚至失败。下面提供几种解决方案，按推荐顺序排列。",
                color = Muted, fontSize = 12.sp
            )
        }

        // 方案1：切换下载源（最推荐）
        HelpSolutionCard(
            icon = Icons.Outlined.Dns,
            iconColor = Indigo,
            tag = "推荐",
            tagColor = Mint,
            title = "方案一：切换 Termux 环境下载源",
            description = "本应用内置 7 个 GitHub 镜像源（gh-proxy.com、ghproxy.net 等），可自动加速 GitHub 下载。切换后重新初始化即可生效。"
        ) {
            HelpStep("1", "进入「设置」标签页")
            HelpStep("2", "找到「下载源」选项")
            HelpStep("3", "选择一个镜像源（推荐 gh-proxy.com 或 ghfast.top）")
            HelpStep("4", "回到「概览」页，点击「删除 Termux 运行环境」")
            HelpStep("5", "等待重新初始化，下载速度会明显提升")
        }

        // 方案2：切换 apt 软件源
        HelpSolutionCard(
            icon = Icons.Outlined.Public,
            iconColor = Indigo,
            tag = "推荐",
            tagColor = Mint,
            title = "方案二：切换依赖包下载源（apt 镜像）",
            description = "JDK、wget、frp 等依赖包从 Termux 官方源下载，国内可能很慢。切换到国内镜像可大幅提速。"
        ) {
            HelpStep("1", "进入「设置」标签页")
            HelpStep("2", "找到「软件源（apt）」选项")
            HelpStep("3", "选择国内镜像（推荐清华 TUNA 或阿里云）")
            HelpStep("4", "回到「概览」页，删除运行环境并重新初始化")
        }

        // 方案3：切换网络
        HelpSolutionCard(
            icon = Icons.Outlined.Wifi,
            iconColor = Indigo,
            tag = "免费",
            tagColor = Mint,
            title = "方案三：切换网络环境",
            description = "不同运营商对 GitHub 的路由质量差异很大，切换网络可能立竿见影。"
        ) {
            HelpStep("1", "尝试切换 WiFi 和手机数据")
            HelpStep("2", "如果用 WiFi，尝试切换到 5G/4G 数据网络")
            HelpStep("3", "不同运营商的基站路由不同，移动/联通/电信可都试试")
            HelpStep("4", "凌晨或非高峰时段下载通常更快")
        }

        // 方案4：使用 VPN/代理
        HelpSolutionCard(
            icon = Icons.Outlined.VpnKey,
            iconColor = Coral,
            tag = "进阶",
            tagColor = Coral,
            title = "方案四：使用网络代理（VPN）",
            description = "使用代理工具加速 GitHub 访问。这是最彻底但需要额外工具的方案。"
        ) {
            HelpStep("1", "在手机上启动代理工具（支持全局或分应用代理）")
            HelpStep("2", "确保代理节点延迟较低（推荐日本、新加坡、美国节点）")
            HelpStep("3", "回到本应用重新下载或初始化")
            HelpStep("4", "注意：部分代理工具可能拦截大文件下载，建议切换为 PAC 模式或放行本应用")
        }

        // 方案5：手动下载
        HelpSolutionCard(
            icon = Icons.Outlined.Sync,
            iconColor = Coral,
            tag = "手动",
            tagColor = Coral,
            title = "方案五：用电脑下载后传到手机",
            description = "如果手机网络实在不行，可以用电脑下载后通过文件管理器传到手机。"
        ) {
            HelpStep("1", "在电脑浏览器打开 GitHub release 页面下载所需文件")
            HelpStep("2", "Termux 环境 ZIP 放到 app 内部存储的 files/home/ 目录")
            HelpStep("3", "服务端核心 JAR 放到 files/home/home/servers/{核心名}/ 目录")
            HelpStep("4", "重启应用，环境会自动识别已存在的文件跳过下载")
        }

        // 测速小贴士
        McCard(title = "如何判断当前速度？") {
            Text(
                "在下载过程中，本应用会实时显示下载速度（如 \"2.35 MB/s\"）。一般来说：",
                color = Muted, fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
            SpeedRow("5+ MB/s", "极快（直连或优质代理）", Mint)
            SpeedRow("1-5 MB/s", "较快（镜像源加速）", Mint)
            SpeedRow("0.5-1 MB/s", "一般（正常国内访问）", Indigo)
            SpeedRow("< 0.5 MB/s", "偏慢，建议切换下载源", Coral)
            Spacer(Modifier.height(8.dp))
            Text(
                "提示：如果速度低于 100 KB/s 或长时间卡住，说明当前网络访问 GitHub 困难，请尝试上述方案。",
                color = Muted, fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HelpSolutionCard(
    icon: ImageVector,
    iconColor: Color,
    tag: String,
    tagColor: Color,
    title: String,
    description: String,
    steps: @Composable () -> Unit
) {
    McCard(title = title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.size(10.dp))
            // 标签
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(tagColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(tag, color = tagColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(8.dp))
        steps()
    }
}

@Composable
private fun HelpStep(step: String, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Indigo),
            contentAlignment = Alignment.Center
        ) {
            Text(step, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(8.dp))
        Text(title, fontSize = 12.sp)
    }
}

@Composable
private fun SpeedRow(speed: String, desc: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(speed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.width(90.dp))
        Text(desc, color = Muted, fontSize = 11.sp)
    }
}
