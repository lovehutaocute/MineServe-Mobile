package com.mineserve.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── 亮色设计令牌（与参考界面完全对齐） ──────────────────────────────
val LightBg = Color(0xFFF3F4F8)
val LightCard = Color(0xFFFFFFFF)
val LightInk = Color(0xFF1F2430)
val LightMuted = Color(0xFF8890A0)
val LightLine = Color(0xFFE7E9F0)

val Indigo = Color(0xFF3B4C9C)
val IndigoDark = Color(0xFF2B3A7A)
val LightIndigoSoft = Color(0xFFEEF0FA)
val IndigoRingBg = Color(0xFF2E3D82)

val Mint = Color(0xFF2FBF87)
val LightMintSoft = Color(0xFFE7F8F0)
val MintBright = Color(0xFF8CF0C4)

val Coral = Color(0xFFF97066)
val LightCoralSoft = Color(0xFFFDEEEC)

val LightFieldBg = Color(0xFFFAFAFC)
val LightTrackBg = Color(0xFFEEF0F5)
val LightFieldGray = Color(0xFFF0F1F5)

// ── 深色设计令牌 ─────────────────────────────────────────────────
val DarkBg = Color(0xFF10131A)
val DarkCard = Color(0xFF1A1F2B)
val DarkInk = Color(0xFFE6E9F2)
val DarkMuted = Color(0xFF9AA3B5)
val DarkLine = Color(0xFF2A3140)
val DarkIndigoSoft = Color(0xFF2A3563)
val DarkMintSoft = Color(0xFF17382C)
val DarkCoralSoft = Color(0xFF3D2424)
val DarkFieldBg = Color(0xFF232936)
val DarkTrackBg = Color(0xFF2A3140)
val DarkFieldGray = Color(0xFF232936)

// ── 主题感知 token（Composable getter，跟随系统深色模式） ─────────────
// 组件内直接引用这些名字即可自动适配深色；纯色（Indigo/Mint/Coral/MintBright）
// 深浅色下保持品牌色不变，仅容器/文字/背景色切换。
val Bg: Color @Composable get() = if (isSystemInDarkTheme()) DarkBg else LightBg
val Card: Color @Composable get() = if (isSystemInDarkTheme()) DarkCard else LightCard
val Ink: Color @Composable get() = if (isSystemInDarkTheme()) DarkInk else LightInk
val Muted: Color @Composable get() = if (isSystemInDarkTheme()) DarkMuted else LightMuted
val Line: Color @Composable get() = if (isSystemInDarkTheme()) DarkLine else LightLine
val IndigoSoft: Color @Composable get() = if (isSystemInDarkTheme()) DarkIndigoSoft else LightIndigoSoft
val MintSoft: Color @Composable get() = if (isSystemInDarkTheme()) DarkMintSoft else LightMintSoft
val CoralSoft: Color @Composable get() = if (isSystemInDarkTheme()) DarkCoralSoft else LightCoralSoft
val FieldBg: Color @Composable get() = if (isSystemInDarkTheme()) DarkFieldBg else LightFieldBg
val TrackBg: Color @Composable get() = if (isSystemInDarkTheme()) DarkTrackBg else LightTrackBg
val FieldGray: Color @Composable get() = if (isSystemInDarkTheme()) DarkFieldGray else LightFieldGray
