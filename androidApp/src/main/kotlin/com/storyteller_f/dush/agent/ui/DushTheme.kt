package com.storyteller_f.dush.agent.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

private val Teal80 = Color(0xFF80CBC4)
private val Teal40 = Color(0xFF00897B)
private val Teal30 = Color(0xFF00695C)
private val TealDark = Color(0xFF004D40)
private val Cyan90 = Color(0xFFB2EBF2)
private val Cyan80 = Color(0xFF80DEEA)
private val Cyan40 = Color(0xFF00ACC1)

private val DushLightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = TealDark,
    secondary = Cyan40,
    onSecondary = Color.White,
    secondaryContainer = Cyan90,
    onSecondaryContainer = Color(0xFF004D57),
    tertiary = Color(0xFF6C63FF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8E5FF),
    onTertiaryContainer = Color(0xFF1A0063),
    surface = Color(0xFFFAFDFB),
    onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFFEEF2F0),
    onSurfaceVariant = Color(0xFF414846),
    surfaceContainerLowest = Color.White,
    surfaceContainer = Color(0xFFF0F4F2),
    surfaceContainerHigh = Color(0xFFEAEEEC),
)

private val DushDarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = TealDark,
    primaryContainer = Teal30,
    onPrimaryContainer = Color(0xFFA7F3EC),
    secondary = Cyan80,
    onSecondary = Color(0xFF003640),
    secondaryContainer = Color(0xFF004D57),
    onSecondaryContainer = Cyan90,
    tertiary = Color(0xFFB4ADFF),
    onTertiary = Color(0xFF2E2274),
    tertiaryContainer = Color(0xFF453A8C),
    onTertiaryContainer = Color(0xFFE8E5FF),
    surface = Color(0xFF121413),
    onSurface = Color(0xFFE1E3E1),
    surfaceVariant = Color(0xFF2B2F2D),
    onSurfaceVariant = Color(0xFFBFC9C5),
    surfaceContainerLowest = Color(0xFF0D0F0E),
    surfaceContainer = Color(0xFF1E2120),
    surfaceContainerHigh = Color(0xFF282B2A),
)

@Composable
fun DushTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DushDarkColors
        else -> DushLightColors
    }
    val defaultTypography = Typography()
    val typography = remember {
        defaultTypography.copy(
            displayLarge = defaultTypography.displayLarge.copy(fontWeight = FontWeight.Bold),
            headlineLarge = defaultTypography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
            titleLarge = defaultTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = defaultTypography.titleMedium.copy(fontWeight = FontWeight.Medium),
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content,
    )
}
