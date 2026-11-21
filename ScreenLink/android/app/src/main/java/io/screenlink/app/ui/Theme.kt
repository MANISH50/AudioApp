package io.screenlink.app.ui

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

private val Blue = Color(0xFF2F6FDB)
private val BlueLight = Color(0xFF9DBCFF)
private val Ink = Color(0xFF14213D)

private val LightColors = lightColorScheme(primary = Blue, secondary = Ink)
private val DarkColors = darkColorScheme(primary = BlueLight, secondary = Color(0xFFC3CEE6))

/** Material 3 with dynamic colour on Android 12+, falling back to ScreenLink's own palette. Follows system dark mode. */
@Composable
fun ScreenLinkTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
