package io.spatia.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF2E8B80)
private val TealLight = Color(0xFF7FD1C7)
private val Navy = Color(0xFF1B2A41)

private val LightColors = lightColorScheme(primary = Teal, secondary = Navy, tertiary = Color(0xFF8A5A00))
private val DarkColors = darkColorScheme(primary = TealLight, secondary = Color(0xFFB9C8E2), tertiary = Color(0xFFFFB951))

/** Material 3 with dynamic colour on Android 12+, falling back to Spatia's own palette. Follows system dark mode. */
@Composable
fun SpatiaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
