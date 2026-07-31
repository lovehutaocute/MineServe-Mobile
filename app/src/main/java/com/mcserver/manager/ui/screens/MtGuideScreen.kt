package com.mcserver.manager.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.Muted

@Composable
fun MtGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 返回栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text("返回", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        HeaderBlock(eyebrow = "Tools", title = "MT 管理器管理文件")

        McCard(title = "下载 MT 管理器") {
            Text("MT 管理器是一款强大的 Android 文件管理工具，支持免 Root 浏览应用私有数据目录。", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mt2.cn/download/")))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📥 前往 MT 管理器官网下载", fontSize = 14.sp)
            }
        }

        McCard(title = "使用教程") {
            GuideStep("1", "安装 MT 管理器", "从官网下载并安装 MT 管理器（首次打开需同意用户协议）。")
            GuideStep("2", "打开侧拉栏", "进入 MT 管理器主界面，向右侧滑动打开侧拉栏，或点击左上角菜单图标。")
            GuideStep("3", "添加本地存储", "在侧拉栏中找到「添加本地存储」选项并点击。")
            GuideStep("4", "选择本应用", "在弹出的列表中，找到并勾选「MCServerManager」，点击确定。")
            GuideStep("5", "开始管理文件", "返回 MT 管理器主界面，在侧拉栏中会出现本应用的条目，点击即可浏览和修改 data 目录下的所有文件（包括 MC 服务端文件）。")
        }

        McCard(title = "原理说明") {
            Text(
                "本应用内置了定制的 DocumentsProvider（MTDataFilesProvider），对外提供 data 目录的文件操作接口。MT 管理器通过 Android 系统标准的文件访问接口连接此 Provider，从而实现在 MT 管理器中直接读写本应用的私有数据——全程无需 Root 权限。",
                color = Muted, fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun GuideStep(step: String, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Indigo),
            contentAlignment = Alignment.Center
        ) {
            Text(step, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
