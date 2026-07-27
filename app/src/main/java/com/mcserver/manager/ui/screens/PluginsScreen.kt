package com.mcserver.manager.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.PillButton
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Muted

@Composable
fun PluginsScreen(vm: McViewModel) {
    val plugins by vm.plugins.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        HeaderBlock(eyebrow = "Plugin Market", title = "插件管理")
        McCard(
            title = "已安装插件",
            trailing = {
                Text("+ 浏览市场", color = Indigo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        ) {
            plugins.forEach { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(IndigoSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            p.avatarText,
                            color = Indigo,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(p.description, color = Muted, fontSize = 11.sp)
                    }
                    PillButton(
                        text = if (p.installed) "卸载" else "安装",
                        install = !p.installed,
                        onClick = {
                            if (p.installed) vm.uninstallPlugin(p) else vm.installPlugin(p)
                        }
                    )
                }
            }
        }

        McCard(title = "插件目录") {
            Text(
                "/home/server/plugins/",
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.sendCommand("reload") },
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("热重载插件", color = Color.White) }
        }
        Spacer(Modifier.height(16.dp))
    }
}
