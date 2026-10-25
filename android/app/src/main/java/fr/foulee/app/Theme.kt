package fr.foulee.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Same palette as the web app: Tailwind slate neutrals and the blue accent. */
class Pal(
    val bg: Color, val card: Color, val border: Color, val text: Color, val muted: Color, val accent: Color, val track: Color,
    val good: Color, val warn: Color, val bad: Color, val neutral: Color, val violet: Color,
)

private val light = Pal(
    bg = Color(0xFFF8FAFC), card = Color.White, border = Color(0xFFE2E8F0), text = Color(0xFF0F172A), muted = Color(0xFF64748B),
    accent = Color(0xFF2A78D6), track = Color(0xFFF1F5F9), good = Color(0xFF10B981), warn = Color(0xFFF59E0B), bad = Color(0xFFEF4444), neutral = Color(0xFF94A3B8), violet = Color(0xFF6250D6),
)
private val dark = Pal(
    bg = Color(0xFF020617), card = Color(0xFF0F172A), border = Color(0xFF1E293B), text = Color(0xFFF1F5F9), muted = Color(0xFF94A3B8),
    accent = Color(0xFF3987E5), track = Color(0xFF1E293B), good = Color(0xFF10B981), warn = Color(0xFFF59E0B), bad = Color(0xFFEF4444), neutral = Color(0xFF64748B), violet = Color(0xFF9085E9),
)

val LocalPal = staticCompositionLocalOf { light }

@Composable
fun FouleeTheme(content: @Composable () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val pal = if (isDark) dark else light
    val scheme = if (isDark) {
        darkColorScheme(primary = pal.accent, background = pal.bg, surface = pal.card, onBackground = pal.text, onSurface = pal.text)
    } else {
        lightColorScheme(primary = pal.accent, background = pal.bg, surface = pal.card, onBackground = pal.text, onSurface = pal.text)
    }
    CompositionLocalProvider(LocalPal provides pal) { MaterialTheme(colorScheme = scheme, content = content) }
}
