package com.mcserver.manager.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 对齐参考 html：
 *  - body   : Manrope 400/500/600/700/800
 *  - title  : Sora     600/700/800
 * 由于 Android 默认无 Manrope/Sora，使用系统字体作为兜底；保留字号字重语义
 */
private val sora = TextStyle(fontWeight = FontWeight.Bold)
private val manrope = TextStyle(fontWeight = FontWeight.Medium)

val McTypography = Typography(
    titleLarge = sora.copy(fontSize = 21.sp),                  // h1 云控面板
    titleMedium = sora.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), // 卡片标题
    titleSmall = manrope.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), // 列表项标题
    bodyLarge = manrope.copy(fontSize = 13.sp),                // 正文
    bodyMedium = manrope.copy(fontSize = 12.sp),               // 二级文本
    bodySmall = manrope.copy(fontSize = 11.sp),                // 标签/描述
    labelSmall = manrope.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), // 极小说明
    labelMedium = manrope.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)  // pill 按钮
)
