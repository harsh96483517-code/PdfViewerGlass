package com.example.pdfglass

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.store by preferencesDataStore("prefs")
val THEME_KEY = stringPreferencesKey("theme")
val MODE_KEY = stringPreferencesKey("mode")

enum class ThemeChoice(val label: String, val top: Color, val bottom: Color, val accent: Color) {
    Ocean("Ocean", Color(0xFF061A3A), Color(0xFF0B3C7A), Color(0xFF22D3EE)),
    Aurora("Aurora", Color(0xFF14082E), Color(0xFF3B1D8F), Color(0xFFA78BFA)),
    Emerald("Emerald", Color(0xFF04201B), Color(0xFF0B5D4B), Color(0xFF34D399)),
    Sunset("Sunset", Color(0xFF2A0A18), Color(0xFF7A1F3D), Color(0xFFFB923C)),
    Rose("Rose", Color(0xFF2B0A22), Color(0xFF7B1E6B), Color(0xFFF472B6)),
    Graphite("Graphite", Color(0xFF0E1116), Color(0xFF2A3140), Color(0xFF94A3B8))
}

enum class Mode { System, Light, Dark }

data class GlassColors(
    val top: Color, val bottom: Color, val accent: Color,
    val text: Color, val subtext: Color, val glass: Color, val border: Color
)

val LocalGlass = staticCompositionLocalOf<GlassColors> { error("LiquidGlassTheme missing") }

@Composable
fun LiquidGlassTheme(theme: ThemeChoice, mode: Mode, content: @Composable () -> Unit) {
    val dark = when (mode) { Mode.Dark -> true; Mode.Light -> false; Mode.System -> isSystemInDarkTheme() }
    val c = if (dark) GlassColors(theme.top, theme.bottom, theme.accent, Color.White,
        Color(0xB3FFFFFF), Color(0x1AFFFFFF), Color(0x55FFFFFF))
    else GlassColors(lerp(Color.White, theme.accent, 0.10f), lerp(Color.White, theme.bottom, 0.20f),
        lerp(theme.accent, theme.top, 0.45f), Color(0xFF0B1630), Color(0x990B1630), Color(0xB3FFFFFF), Color(0x66FFFFFF))
    val scheme = if (dark) darkColorScheme(primary = c.accent, onPrimary = Color.Black, background = c.top,
        surface = c.top, onSurface = c.text, secondaryContainer = c.accent.copy(alpha = 0.25f), onSecondaryContainer = c.text)
    else lightColorScheme(primary = c.accent, onPrimary = Color.White, background = c.top,
        surface = c.top, onSurface = c.text, secondaryContainer = c.accent.copy(alpha = 0.20f), onSecondaryContainer = c.text)
    val act = LocalContext.current as? ComponentActivity
    LaunchedEffect(dark) {
        val t = android.graphics.Color.TRANSPARENT
        val s = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
        act?.enableEdgeToEdge(statusBarStyle = s, navigationBarStyle = s)
    }
    CompositionLocalProvider(LocalGlass provides c) { MaterialTheme(colorScheme = scheme, content = content) }
}

@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    val g = LocalGlass.current
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(g.top, g.bottom)))
            .background(Brush.radialGradient(listOf(g.accent.copy(alpha = 0.28f), Color.Transparent),
                center = Offset(900f, 150f), radius = 900f)),
        content = content
    )
}

/** Translucent glass surface. Works on every device (no hard dependency on backdrop blur). */
@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(24.dp)
    var m = modifier.clip(shape)
        .background(Brush.verticalGradient(listOf(g.glass.copy(alpha = (g.glass.alpha * 1.8f).coerceAtMost(0.9f)), g.glass)))
        .border(1.dp, Brush.linearGradient(listOf(g.border, g.border.copy(alpha = 0.1f), g.border)), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(16.dp), content = content)
}
