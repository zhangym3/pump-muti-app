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
    primary = PumpBlueLight,
    onPrimary = Color(0xFF08264A),
    secondary = Color(0xFFADC9F4),
    tertiary = Color(0xFF75D6A0),
    background = Color(0xFF101827),
    onBackground = Color(0xFFEAF1FB),
    surface = Color(0xFF172235),
    onSurface = Color(0xFFEAF1FB),
    surfaceContainer = Color(0xFF202E45),
    surfaceVariant = Color(0xFF2C3A52),
    onSurfaceVariant = Color(0xFFB4C2D7),
    outline = Color(0xFF8395AF),
    outlineVariant = Color(0xFF3B4B65),
    primaryContainer = Color(0xFF183C69),
    onPrimaryContainer = Color(0xFFD7E8FF),
    secondaryContainer = Color(0xFF2A3951),
    onSecondaryContainer = Color(0xFFD9E4F5),
    tertiaryContainer = Color(0xFF153D30),
    onTertiaryContainer = Color(0xFFC9F5DC),
    error = Color(0xFFFF8B82),
    errorContainer = Color(0xFF5A292B),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = PumpBlue,
    onPrimary = Color.White,
    secondary = PumpSlate,
    tertiary = PumpGreen,
    background = PumpCanvas,
    onBackground = PumpNavy,
    surface = Color(0xFFFFFFFF),
    onSurface = PumpNavy,
    surfaceContainer = Color(0xFFEDF3FB),
    surfaceVariant = Color(0xFFE7EEF8),
    onSurfaceVariant = PumpSlate,
    outline = Color(0xFF9AA9BF),
    outlineVariant = Color(0xFFDCE5F1),
    primaryContainer = PumpPaleBlue,
    onPrimaryContainer = Color(0xFF124D9F),
    secondaryContainer = Color(0xFFF0F4F9),
    onSecondaryContainer = Color(0xFF35435A),
    tertiaryContainer = Color(0xFFE7F6EC),
    onTertiaryContainer = Color(0xFF145B32),
    error = PumpRed,
    errorContainer = Color(0xFFFFE9E7),
    onErrorContainer = Color(0xFF8E1F17)
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
