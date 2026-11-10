package ru.chitalka.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Paper = Color(0xFFF6F1E7)
val Olive = Color(0xFF68734D)
private val Light = lightColorScheme(primary = Olive, onPrimary = Color.White,
    background = Paper, surface = Paper, onSurface = Color(0xFF292820),
    surfaceVariant = Color(0xFFEAE5D9), secondaryContainer = Color(0xFFE1E6D5),
    surfaceContainer = Color(0xFFF0EBDF), surfaceContainerHigh = Color(0xFFEAE5D9),
    surfaceContainerHighest = Color(0xFFE4DFD2))
private val Dark = darkColorScheme(primary = Color(0xFFB7C79F),
    background = Color(0xFF1D211F), surface = Color(0xFF1D211F),
    onSurface = Color(0xFFE7E4D8), surfaceVariant = Color(0xFF2E352F))

@Composable fun ChitalkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
