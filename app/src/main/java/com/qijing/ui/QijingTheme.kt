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

val QijingMint = Color(0xFF72E1BF)
val QijingBlue = Color(0xFF8DA9FF)
val QijingAmber = Color(0xFFFFCA78)
val QijingDanger = Color(0xFFFF8C82)
val QijingNight = Color(0xFF080C12)
val QijingPanel = Color(0xFF111821)
val QijingPanelRaised = Color(0xFF18222E)

private val DarkColors = darkColorScheme(
    primary = QijingMint,
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF164F42),
    onPrimaryContainer = Color(0xFF9DF4D7),
    secondary = QijingBlue,
    onSecondary = Color(0xFF10285F),
    secondaryContainer = Color(0xFF263A70),
    onSecondaryContainer = Color(0xFFDCE2FF),
    tertiary = QijingAmber,
    onTertiary = Color(0xFF432C00),
    background = QijingNight,
    onBackground = Color(0xFFE7EDF5),
    surface = QijingPanel,
    onSurface = Color(0xFFE7EDF5),
    surfaceVariant = QijingPanelRaised,
    onSurfaceVariant = Color(0xFFABB8C8),
    outline = Color(0xFF344354),
    outlineVariant = Color(0xFF263341),
    error = QijingDanger,
    onError = Color(0xFF530900),
    errorContainer = Color(0xFF5C211C),
    onErrorContainer = Color(0xFFFFDAD5)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B56),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9DF4D7),
    onPrimaryContainer = Color(0xFF002018),
    secondary = Color(0xFF425D9B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE2FF),
    onSecondaryContainer = Color(0xFF001A41),
    tertiary = Color(0xFF775A16),
    onTertiary = Color.White,
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF151B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF151B22),
    surfaceVariant = Color(0xFFE8EEF4),
    onSurfaceVariant = Color(0xFF45515F),
    outline = Color(0xFF74808E),
    outlineVariant = Color(0xFFC4CCD6),
    error = Color(0xFFBA1A1A)
)

private val QijingTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
)

private val QijingShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
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
