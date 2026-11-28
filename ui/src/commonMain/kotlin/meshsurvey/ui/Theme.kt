package meshsurvey.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** Dark is the default; System follows the OS where the platform reports it (otherwise dark). */
enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("System") }

/**
 * Semantic colours for text and panels that Material's scheme does not cover. Every text colour here keeps at least
 * 4.5:1 contrast (WCAG AA) against the surface it is used on in its theme.
 */
data class Palette(
    val dark: Boolean,
    /** Secondary text (notes, captions). */
    val muted: Color,
    /** Warnings and "limited" findings. */
    val warn: Color,
    val ok: Color,
    val bad: Color,
    /** Floating panels over the map (legend, summary). */
    val overlay: Color,
    val onOverlay: Color,
    /** Highlighted row (selected item). */
    val highlight: Color,
    val divider: Color,
    /** Background of the temporary-preset banner and its text. */
    val banner: Color,
    val onBanner: Color,
    /** Top bar. */
    val bar: Color,
    val onBar: Color,
)

val DarkPalette = Palette(
    dark = true, muted = Color(0xFFB8C2CC), warn = Color(0xFFFFCC66), ok = Color(0xFF8BD68F), bad = Color(0xFFFF9A9A),
    overlay = Color(0xEE1E2429), onOverlay = Color(0xFFE6EBEF), highlight = Color(0x332F8FD0), divider = Color(0x22FFFFFF),
    banner = Color(0xFF4A3410), onBanner = Color(0xFFFFE3B3), bar = Color(0xFF14323F), onBar = Color(0xFFFFFFFF),
)

val LightPalette = Palette(
    dark = false, muted = Color(0xFF4A5560), warn = Color(0xFF7A5300), ok = Color(0xFF1E6B22), bad = Color(0xFFB3261E),
    overlay = Color(0xF2FFFFFF), onOverlay = Color(0xFF1B1F23), highlight = Color(0x1A1976D2), divider = Color(0x1A000000),
    banner = Color(0xFFFFF3E0), onBanner = Color(0xFF3E2A00), bar = Color(0xFF1B5E7A), onBar = Color(0xFFFFFFFF),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Shorthand for the current palette inside composables. */
object Pal {
    val muted: Color @Composable get() = LocalPalette.current.muted
    val warn: Color @Composable get() = LocalPalette.current.warn
    val ok: Color @Composable get() = LocalPalette.current.ok
    val bad: Color @Composable get() = LocalPalette.current.bad
    val overlay: Color @Composable get() = LocalPalette.current.overlay
    val onOverlay: Color @Composable get() = LocalPalette.current.onOverlay
    val highlight: Color @Composable get() = LocalPalette.current.highlight
    val divider: Color @Composable get() = LocalPalette.current.divider
    val dark: Boolean @Composable get() = LocalPalette.current.dark
}

/** Chip/badge backgrounds (white text on them keeps ≥ 4.5:1 in both themes). */
object ChipColors {
    val good = Color(0xFF2E7D32)
    val busy = Color(0xFFA14A00)
    val bad = Color(0xFFC62828)
    val idle = Color(0xFF5F6368)
    val alarm = Color(0xFFB71C1C)
}

/** Text colour that stays readable on [bg] (black on light fills such as amber, white on dark ones). */
fun readableOn(bg: Color): Color {
    fun ch(c: Float) = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    val l = 0.2126f * ch(bg.red) + 0.7152f * ch(bg.green) + 0.0722f * ch(bg.blue)
    return if (l > 0.2f) Color(0xFF111111) else Color.White
}

@Composable
fun MeshSurveyTheme(mode: ThemeMode, systemDark: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemDark }
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF7CC6E6), onPrimary = Color(0xFF00344A), secondary = Color(0xFF8BD68F),
        background = Color(0xFF111518), surface = Color(0xFF111518), onSurface = Color(0xFFE3E8EC),
        surfaceVariant = Color(0xFF263038), onSurfaceVariant = Color(0xFFC3CCD4),
        surfaceContainer = Color(0xFF1A2025), surfaceContainerHigh = Color(0xFF222A30), surfaceContainerHighest = Color(0xFF2A333A),
        surfaceContainerLow = Color(0xFF161B1F), secondaryContainer = Color(0xFF2B4A5A), onSecondaryContainer = Color(0xFFD6EEF9),
    ) else lightColorScheme(primary = Color(0xFF1B5E7A), secondary = Color(0xFF2E7D32))
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
