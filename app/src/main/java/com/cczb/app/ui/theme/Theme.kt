package com.cczb.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = Color(0xFF17151D),
    surface = Color(0xFF211E28),
    surfaceContainer = Color(0xFF2B2733),
    surfaceVariant = Color(0xFF37323F),
    outlineVariant = Color(0xFF514B5B),
    primaryContainer = Color(0xFF3B2D62),
    onPrimaryContainer = Color(0xFFE9DFFF),
    secondaryContainer = Color(0xFF3D3747),
    onSecondaryContainer = Color(0xFFE7E0EF),
    tertiaryContainer = Color(0xFF573E2B),
    onTertiaryContainer = Color(0xFFF5DDC5)
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = Color(0xFFF5F4F8),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEDEAF2),
    surfaceVariant = Color(0xFFE8E4ED),
    outlineVariant = Color(0xFFD9D4E1),
    primaryContainer = Color(0xFFE9E0FF),
    onPrimaryContainer = Color(0xFF29145F),
    secondaryContainer = Color(0xFFE4DFE9),
    onSecondaryContainer = Color(0xFF302B38),
    tertiaryContainer = Color(0xFFF4E6D7),
    onTertiaryContainer = Color(0xFF4A2E13)

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun PumpmutiappTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
