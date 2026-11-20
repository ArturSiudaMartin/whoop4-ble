package dev.whoop4.bloop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Bloop palette: near-black surfaces, white type, green = recovered, yellow/red = strain. */
object BloopColors {
    val Background = Color(0xFF0B0D10)
    val Surface = Color(0xFF15181D)
    val SurfaceHigh = Color(0xFF1D2128)
    val Outline = Color(0xFF2A2F38)
    val OnSurface = Color(0xFFF2F4F7)
    val Muted = Color(0xFF8C94A1)
    val Recovery = Color(0xFF34D399)   // high recovery / good
    val Caution = Color(0xFFFACC15)    // moderate
    val Strain = Color(0xFFF87171)     // high strain / low recovery
    val Sleep = Color(0xFF8B9CFF)
    val Live = Color(0xFFFF5A4F)
}

private val scheme = darkColorScheme(
    primary = BloopColors.Recovery,
    onPrimary = BloopColors.Background,
    secondary = BloopColors.Caution,
    tertiary = BloopColors.Sleep,
    error = BloopColors.Strain,
    background = BloopColors.Background,
    onBackground = BloopColors.OnSurface,
    surface = BloopColors.Surface,
    onSurface = BloopColors.OnSurface,
    surfaceVariant = BloopColors.SurfaceHigh,
    onSurfaceVariant = BloopColors.Muted,
    surfaceContainer = BloopColors.Surface,
    outline = BloopColors.Outline,
)

private val typography = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)

@Composable
fun BloopTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
