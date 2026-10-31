package dev.pocketagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider

data class AccentOption(val id: String, val label: String, val color: Color, val soft: Color)

val Accents = listOf(
    AccentOption("ember", "Ember", Color(0xFFFF6B35), Color(0xFF3A2218)),
    AccentOption("ocean", "Ocean", Color(0xFF4C9BFF), Color(0xFF15253B)),
    AccentOption("mint", "Mint", Color(0xFF3DD9A4), Color(0xFF13302A)),
    AccentOption("violet", "Violet", Color(0xFFA58BFF), Color(0xFF261E3D)),
    AccentOption("rose", "Rose", Color(0xFFFF6F91), Color(0xFF3A1A24)),
    AccentOption("gold", "Gold", Color(0xFFFFC447), Color(0xFF362A12))
)

fun accentById(id: String): AccentOption = Accents.firstOrNull { it.id == id } ?: Accents.first()

/**
 * App colours. Accent and background are snapshot state so the whole UI
 * recomposes when the user changes them in Settings ▸ Appearance.
 * [apply] is called from MainActivity outside of composition.
 */
object Palette {
    var Background by mutableStateOf(Color(0xFF000000))
    var Surface by mutableStateOf(Color(0xFF121418))
    var SurfaceHigh by mutableStateOf(Color(0xFF1C1F25))
    val Outline = Color(0xFF2A2F38)
    var Accent by mutableStateOf(Accents.first().color)
    var AccentSoft by mutableStateOf(Accents.first().soft)
    val Research = Color(0xFF5AA9FF)
    val ResearchSoft = Color(0xFF16273A)
    val Text = Color(0xFFE8EAED)
    val TextMuted = Color(0xFF9AA0A6)
    val TerminalBg = Color(0xF20A0F0A)
    val TerminalGreen = Color(0xFF39D98A)
    val TerminalAmber = Color(0xFFFFC857)
    val TerminalCyan = Color(0xFF6CD4FF)
    val Error = Color(0xFFFF5C5C)
    val Success = Color(0xFF39D98A)

    fun apply(accentId: String, amoled: Boolean) {
        val a = accentById(accentId)
        Accent = a.color
        AccentSoft = a.soft
        Background = if (amoled) Color(0xFF000000) else Color(0xFF0B0D10)
        Surface = if (amoled) Color(0xFF111316) else Color(0xFF14171C)
        SurfaceHigh = if (amoled) Color(0xFF1B1E23) else Color(0xFF1F232A)
    }
}

val Mono = FontFamily.Monospace

private val typography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp)
)

@Composable
fun PocketAgentTheme(fontScale: Float = 1f, content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Palette.Accent,
        onPrimary = Color.Black,
        primaryContainer = Palette.AccentSoft,
        onPrimaryContainer = Palette.Text,
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
        surfaceContainerHighest = Palette.SurfaceHigh,
        surfaceContainerLow = Palette.Background,
        surfaceContainerLowest = Palette.Background,
        outline = Palette.Outline,
        outlineVariant = Palette.Outline,
        error = Palette.Error
    )
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
