package com.noryan.romcore.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.noryan.romcore.R

// Same palette and pixel font as RomRunner: true-black OLED background, brand orange highlight.
val Slate90 = Color(0xFF000000)
val Amber80 = Color(0xFFFFD9A6)
val BrandOrange = Color(0xFFFF6B4A)
val BrandNavy = Color(0xFF0E0A22)
val HighlightColor = BrandOrange

val Silkscreen = FontFamily(Font(R.font.silkscreen_regular))

private fun ts(size: Int) = TextStyle(fontFamily = Silkscreen, fontWeight = FontWeight.Normal, fontSize = size.sp)

val RomCoreTypography = Typography(
    displayLarge = ts(40), displayMedium = ts(34), displaySmall = ts(30),
    headlineLarge = ts(28), headlineMedium = ts(24), headlineSmall = ts(20),
    titleLarge = ts(26), titleMedium = ts(17), titleSmall = ts(15),
    bodyLarge = ts(16), bodyMedium = ts(15), bodySmall = ts(13),
    labelLarge = ts(15), labelMedium = ts(13), labelSmall = ts(12)
)

@Composable
fun RomCoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = HighlightColor, onPrimary = BrandNavy, secondary = Amber80, onBackground = Color(0xFFF4F5FA), onSurface = Color(0xFFF4F5FA), onSurfaceVariant = Color(0xFFB8B8C4), background = Slate90, surface = Slate90),
        typography = RomCoreTypography, content = content
    )
}
