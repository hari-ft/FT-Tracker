package com.hari.screentime.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VivoBlue = Color(0xFF415FFF)

private val Light = lightColorScheme(
    primary = VivoBlue,
    primaryContainer = Color(0xFFE3E8FF),
    background = Color(0xFFF4F6FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEFF5),
    outline = Color(0xFF8A90A0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8DA0FF),
    primaryContainer = Color(0xFF26306B),
    background = Color(0xFF0E1015),
    surface = Color(0xFF1A1D24),
    surfaceVariant = Color(0xFF2A2E38),
    outline = Color(0xFF8A90A0),
)

@Composable
fun ScreenTimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
