package icu.yuqiuyijiaren.xiaobai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Blue = Color(0xFF007AFF)
val BlueStrong = Color(0xFF0062CC)
val Ink = Color(0xFF1D1D1F)
val Muted = Color(0xFF6E6E73)
val Canvas = Color(0xFFF2F2F7)
val SurfaceGlass = Color(0xC7FFFFFF)
val Amber = Color(0xFFFF9F0A)
val Green = Color(0xFF34C759)
val Danger = Color(0xFFFF3B30)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = BlueStrong,
    background = Canvas,
    surface = SurfaceGlass,
    onBackground = Ink,
    onSurface = Ink,
    error = Danger,
)

private val DarkColors = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    onBackground = Color.White,
    onSurface = Color.White,
    error = Danger,
)

@Composable
fun XiaobaiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
