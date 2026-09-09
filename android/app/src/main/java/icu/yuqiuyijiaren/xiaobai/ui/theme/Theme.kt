package icu.yuqiuyijiaren.xiaobai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Blue = Color(0xFF0A84FF)
val BlueStrong = Color(0xFF0062CC)
val Ink = Color(0xFFF5F5F7)
val Muted = Color(0xFF8E8E96)
val Canvas = Color(0xFF0B0B0E)
val SurfaceGlass = Color(0xFF15151B)
val StudioElevated = Color(0xFF1C1C24)
val StudioBorder = Color(0xFF2C2C35)
val BorderSubtle = Color(0xFF2C2C35)
val Amber = Color(0xFFFF9F0A)
val Green = Color(0xFF30D158)
val Danger = Color(0xFFFF453A)
val Red = Color(0xFFFF453A)

private val DarkColors = darkColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    background = Canvas,
    surface = SurfaceGlass,
    surfaceVariant = StudioElevated,
    onBackground = Ink,
    onSurface = Ink,
    error = Danger,
)

@Composable
fun XiaobaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}

