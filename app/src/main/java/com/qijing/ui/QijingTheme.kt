package com.qijing.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val QijingMint = Color(0xFF36B895)
val QijingBlue = Color(0xFF4F82D8)
val QijingViolet = Color(0xFF7657C7)
val QijingAmber = Color(0xFFC97825)
val QijingRose = Color(0xFFC94E69)
val QijingDanger = Color(0xFFC94E5C)
val QijingNight = Color(0xFF0E1417)
val QijingPanel = Color(0xFF172126)
val QijingPanelRaised = Color(0xFF1D2B30)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FE0C0),
    onPrimary = Color(0xFF06231B),
    primaryContainer = Color(0xFF18352E),
    onPrimaryContainer = Color(0xFF8AF0D2),
    secondary = Color(0xFF9ABEFF),
    onSecondary = Color(0xFF10285F),
    secondaryContainer = Color(0xFF172B45),
    onSecondaryContainer = Color(0xFFD9E4FF),
    tertiary = Color(0xFFFFBD72),
    onTertiary = Color(0xFF432C00),
    background = QijingNight,
    onBackground = Color(0xFFEDF7F4),
    surface = QijingPanel,
    onSurface = Color(0xFFEDF7F4),
    surfaceVariant = QijingPanelRaised,
    onSurfaceVariant = Color(0xFF9EB1AD),
    outline = Color(0xFF506267),
    outlineVariant = Color(0xFF2B3A3E),
    error = Color(0xFFFF8992),
    onError = Color(0xFF530900),
    errorContainer = Color(0xFF5C211C),
    onErrorContainer = Color(0xFFFFDAD5)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF087F68),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F2E8),
    onPrimaryContainer = Color(0xFF064F40),
    secondary = Color(0xFF4F72B8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF5FC),
    onSecondaryContainer = Color(0xFF001A41),
    tertiary = Color(0xFFA65B16),
    onTertiary = Color.White,
    background = Color(0xFFEEF1F4),
    onBackground = Color(0xFF16201F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16201F),
    surfaceVariant = Color(0xFFE8F4F0),
    onSurfaceVariant = Color(0xFF60706F),
    outline = Color(0xFF7B8A88),
    outlineVariant = Color(0xFFE0E6E9),
    error = Color(0xFFB63B47)
)

private val QijingTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium),
    headlineLarge = TextStyle(fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Medium),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
)

private val QijingShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
)

@Composable
fun QijingTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = QijingTypography, shapes = QijingShapes, content = content)
}
