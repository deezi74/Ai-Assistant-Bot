package dev.pocketagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Background = Color(0xFF0B0D10)
    val Surface = Color(0xFF14171C)
    val SurfaceHigh = Color(0xFF1C2027)
    val Outline = Color(0xFF2A2F38)
    val Accent = Color(0xFFFF6B35)
    val AccentSoft = Color(0xFF3A2218)
    val Research = Color(0xFF5AA9FF)
    val ResearchSoft = Color(0xFF16273A)
    val Text = Color(0xFFE8EAED)
    val TextMuted = Color(0xFF9AA0A6)
    val TerminalBg = Color(0xF20A0F0A)
    val TerminalGreen = Color(0xFF39D98A)
    val TerminalAmber = Color(0xFFFFC857)
    val TerminalCyan = Color(0xFF6CD4FF)
    val Error = Color(0xFFFF5C5C)
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.Black,
    secondary = Palette.Research,
    onSecondary = Color.Black,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextMuted,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerLow = Palette.Background,
    outline = Palette.Outline,
    error = Palette.Error
)

val Mono = FontFamily.Monospace

private val typography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun PocketAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
