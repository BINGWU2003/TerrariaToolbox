package com.example.terrariatoolbox.ui.theme

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
    primary = Green80,
    secondary = Sage80,
    tertiary = Teal80,
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF0C5130), onPrimaryContainer = Color(0xFFBBF1C8),
    secondaryContainer = Color(0xFF3B4B3C), onSecondaryContainer = Color(0xFFD4E8D3),
    background = Color(0xFF101511), onBackground = Color(0xFFDFE5DE),
    surface = Color(0xFF101511), onSurface = Color(0xFFDFE5DE),
    surfaceVariant = Color(0xFF414942), onSurfaceVariant = Color(0xFFC1C9BF),
    surfaceContainerLowest = Color(0xFF0B100C), surfaceContainerLow = Color(0xFF181D19),
    surfaceContainer = Color(0xFF1C211D), surfaceContainerHigh = Color(0xFF262B27),
    surfaceContainerHighest = Color(0xFF313632)
)

private val LightColorScheme = lightColorScheme(
    primary = Green40,
    secondary = Sage40,
    tertiary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBF1C8), onPrimaryContainer = Color(0xFF00210F),
    secondaryContainer = Color(0xFFD4E8D3), onSecondaryContainer = Color(0xFF101F12),
    background = Color(0xFFF6FBF3), onBackground = Color(0xFF181D18),
    surface = Color(0xFFF6FBF3), onSurface = Color(0xFF181D18),
    surfaceVariant = Color(0xFFDDE5DA), onSurfaceVariant = Color(0xFF414942),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F5ED),
    surfaceContainer = Color(0xFFEAF0E7), surfaceContainerHigh = Color(0xFFE4EAE1),
    surfaceContainerHighest = Color(0xFFDFE5DB)
)

@Composable
fun TerrariaToolboxTheme(
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
