package dev.pocketagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.lightColorScheme
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

data class BackgroundOption(
    val id: String,
    val label: String,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val outline: Color,
    val light: Boolean = false
)

val Backgrounds = listOf(
    BackgroundOption("black", "Pure black", Color(0xFF000000), Color(0xFF111316), Color(0xFF1B1E23), Color(0xFF2A2F38)),
    BackgroundOption("charcoal", "Charcoal", Color(0xFF15171B), Color(0xFF1F2228), Color(0xFF2A2E35), Color(0xFF3A3F48)),
    BackgroundOption("midnight", "Midnight blue", Color(0xFF0B1220), Color(0xFF141E31), Color(0xFF1E2A42), Color(0xFF2E3B57)),
    BackgroundOption("plum", "Deep plum", Color(0xFF150E1C), Color(0xFF21172B), Color(0xFF2D203A), Color(0xFF3F2F50)),
    BackgroundOption("forest", "Forest", Color(0xFF0C1410), Color(0xFF15201A), Color(0xFF1F2C24), Color(0xFF2F3F35)),
    BackgroundOption("paper", "Light", Color(0xFFF6F5F2), Color(0xFFFFFFFF), Color(0xFFECEAE5), Color(0xFFD5D2CB), light = true)
)

fun backgroundById(id: String): BackgroundOption = Backgrounds.firstOrNull { it.id == id } ?: Backgrounds.first()

data class TextColorOption(val id: String, val label: String, val color: Color?)

/** "auto" picks white on dark backgrounds and near-black on the light one. */
val TextColors = listOf(
    TextColorOption("auto", "Auto", null),
    TextColorOption("white", "White", Color(0xFFFFFFFF)),
    TextColorOption("warm", "Warm white", Color(0xFFFFF1DC)),
    TextColorOption("sky", "Sky", Color(0xFFB8DCFF)),
    TextColorOption("mint", "Mint", Color(0xFFB9F5D8)),
    TextColorOption("amber", "Amber", Color(0xFFFFD58A)),
    TextColorOption("pink", "Pink", Color(0xFFFFC2D6)),
    TextColorOption("ink", "Ink", Color(0xFF15171B))
)

fun textColorById(id: String): TextColorOption = TextColors.firstOrNull { it.id == id } ?: TextColors.first()

/**
 * App colours. Everything the user can change in Settings ▸ Appearance is
 * snapshot state so the whole UI recomposes when it changes.
 * [apply] is called from MainActivity outside of composition.
 */
object Palette {
    var Background by mutableStateOf(Color(0xFF000000))
    var Surface by mutableStateOf(Color(0xFF121418))
    var SurfaceHigh by mutableStateOf(Color(0xFF1C1F25))
    var Outline by mutableStateOf(Color(0xFF2A2F38))
    var Accent by mutableStateOf(Accents.first().color)
    var AccentSoft by mutableStateOf(Accents.first().soft)
    val Research = Color(0xFF5AA9FF)
    val ResearchSoft = Color(0xFF16273A)
    var Text by mutableStateOf(Color(0xFFE8EAED))
    var TextMuted by mutableStateOf(Color(0xFF9AA0A6))
    var IsLight by mutableStateOf(false)
    val TerminalBg = Color(0xF20A0F0A)
    val TerminalGreen = Color(0xFF39D98A)
    val TerminalAmber = Color(0xFFFFC857)
    val TerminalCyan = Color(0xFF6CD4FF)
    val Error = Color(0xFFFF5C5C)
    val Success = Color(0xFF39D98A)

    fun apply(accentId: String, backgroundId: String, textColorId: String) {
        val a = accentById(accentId)
        val bg = backgroundById(backgroundId)
        Accent = a.color
        AccentSoft = if (bg.light) a.color.copy(alpha = 0.18f).compositeOver(bg.surface) else a.soft
        Background = bg.background
        Surface = bg.surface
        SurfaceHigh = bg.surfaceHigh
        Outline = bg.outline
        IsLight = bg.light
        val text = textColorById(textColorId).color ?: if (bg.light) Color(0xFF15171B) else Color(0xFFECEEF1)
        Text = text
        TextMuted = text.copy(alpha = 0.68f).compositeOver(bg.background)
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
    val colors = if (Palette.IsLight) {
        lightColorScheme(
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
    } else {
        darkColorScheme(
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
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
        MaterialTheme(colorScheme = colors, typography = typography) {
            // Plain Text outside a Surface reads LocalContentColor, which defaults to black.
            CompositionLocalProvider(LocalContentColor provides Palette.Text, content = content)
        }
    }
}
