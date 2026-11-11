package com.offlinemesh.messenger.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TealPrimary = Color(0xFF0F766E)
private val TealSecondary = Color(0xFF14B8A6)
private val TealTertiary = Color(0xFF5EEAD4)
private val DarkBackground = Color(0xFF0B1220)
private val DarkSurface = Color(0xFF111A2E)
private val LightBackground = Color(0xFFF8FAFC)
private val LightSurface = Color(0xFFFFFFFF)

private val DarkScheme = darkColorScheme(
    primary = TealSecondary,
    secondary = TealTertiary,
    tertiary = TealPrimary,
    background = DarkBackground,
    surface = DarkSurface
)

private val LightScheme = lightColorScheme(
    primary = TealPrimary,
    secondary = TealSecondary,
    tertiary = TealTertiary,
    background = LightBackground,
    surface = LightSurface
)

@Composable
fun OfflineMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}
