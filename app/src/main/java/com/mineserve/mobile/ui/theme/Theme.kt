package com.mineserve.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val McColorScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = LightCard,
    primaryContainer = LightIndigoSoft,
    onPrimaryContainer = Indigo,
    secondary = Mint,
    onSecondary = LightCard,
    secondaryContainer = LightMintSoft,
    onSecondaryContainer = Mint,
    tertiary = Coral,
    onTertiary = LightCard,
    tertiaryContainer = LightCoralSoft,
    onTertiaryContainer = Coral,
    background = LightBg,
    onBackground = LightInk,
    surface = LightCard,
    onSurface = LightInk,
    surfaceVariant = LightFieldGray,
    onSurfaceVariant = LightMuted,
    outline = LightLine
)

private val McDarkColorScheme = darkColorScheme(
    primary = Color(0xFF9FB0F0),
    onPrimary = Color(0xFF1B2447),
    primaryContainer = DarkIndigoSoft,
    onPrimaryContainer = Color(0xFFD5DCFA),
    secondary = MintBright,
    onSecondary = Color(0xFF06301F),
    secondaryContainer = DarkMintSoft,
    onSecondaryContainer = MintBright,
    tertiary = Color(0xFFFFB4A8),
    onTertiary = Color(0xFF561E1A),
    tertiaryContainer = DarkCoralSoft,
    onTertiaryContainer = Color(0xFFFFB4A8),
    background = DarkBg,
    onBackground = DarkInk,
    surface = DarkCard,
    onSurface = DarkInk,
    surfaceVariant = DarkFieldGray,
    onSurfaceVariant = DarkMuted,
    outline = DarkLine
)

@Composable
fun MineServeMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) McDarkColorScheme else McColorScheme,
        typography = McTypography,
        content = content
    )
}
