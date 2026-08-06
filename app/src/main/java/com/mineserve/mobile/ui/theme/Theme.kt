package com.mineserve.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val McColorScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Card,
    primaryContainer = IndigoSoft,
    onPrimaryContainer = Indigo,
    secondary = Mint,
    onSecondary = Card,
    secondaryContainer = MintSoft,
    onSecondaryContainer = Mint,
    tertiary = Coral,
    onTertiary = Card,
    tertiaryContainer = CoralSoft,
    onTertiaryContainer = Coral,
    background = Bg,
    onBackground = Ink,
    surface = Card,
    onSurface = Ink,
    surfaceVariant = FieldGray,
    onSurfaceVariant = Muted,
    outline = Line
)

@Composable
fun MCServerManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 当前界面设计以亮色为主；后续可扩展 darkScheme
    MaterialTheme(
        colorScheme = McColorScheme,
        typography = McTypography,
        content = content
    )
}
