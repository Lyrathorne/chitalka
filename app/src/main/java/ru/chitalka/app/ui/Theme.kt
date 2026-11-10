package ru.chitalka.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.chitalka.app.ChitalkaApp

val Paper = Color(0xFFF6F1E7)
val Olive = Color(0xFF596A36)
val LocalAppTheme = staticCompositionLocalOf { AppTheme.PAPER }
private val Light = lightColorScheme(primary = Olive, onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E6D0), onPrimaryContainer = Color(0xFF29321B),
    secondary = Olive, onSecondary = Color.White, secondaryContainer = Color(0xFFE1E6D5), onSecondaryContainer = Color(0xFF29321B),
    background = Paper, onBackground = Color(0xFF292820), surface = Paper, onSurface = Color(0xFF292820),
    surfaceVariant = Color(0xFFEAE5D9), onSurfaceVariant = Color(0xFF5B5B4F), outline = Color(0xFF797B69), outlineVariant = Color(0xFFD2CEBF),
    surfaceContainer = Paper, surfaceContainerLow = Paper, surfaceContainerHigh = Color(0xFFF0EBDF), surfaceContainerHighest = Color(0xFFEAE5D9))
private val Dark = darkColorScheme(primary = Color(0xFFB5C59B), onPrimary = Color(0xFF182010),
    primaryContainer = Color(0xFF35452D), onPrimaryContainer = Color(0xFFE2ECD3),
    secondary = Color(0xFFB5C59B), onSecondary = Color(0xFF182010), secondaryContainer = Color(0xFF35452D), onSecondaryContainer = Color(0xFFE2ECD3),
    background = Color(0xFF171C18), onBackground = Color(0xFFF2EEDF), surface = Color(0xFF171C18), onSurface = Color(0xFFF2EEDF),
    surfaceVariant = Color(0xFF282F27), onSurfaceVariant = Color(0xFFCECDBE), outline = Color(0xFF949B84), outlineVariant = Color(0xFF4B5545),
    surfaceContainer = Color(0xFF171C18), surfaceContainerLow = Color(0xFF1C221C), surfaceContainerHigh = Color(0xFF252D24), surfaceContainerHighest = Color(0xFF2F382C))
private val Type = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 23.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium))

@Composable fun ChitalkaTheme(content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as ChitalkaApp
    val theme by app.themes.current.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalAppTheme provides theme) {
        MaterialTheme(colorScheme = if (theme == AppTheme.NIGHT) Dark else Light, typography = Type,
            shapes = Shapes(small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(14.dp)), content = content)
    }
}
