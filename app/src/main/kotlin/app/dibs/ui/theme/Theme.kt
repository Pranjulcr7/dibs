// SPDX-License-Identifier: Apache-2.0
package app.dibs.ui.theme

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

// Warm default palette for devices below Android 12 (SPEC §4.2); dynamic color above.
private val LightColors = lightColorScheme(
    primary = Color(0xFF8B5000),
    secondary = Color(0xFF745943),
    tertiary = Color(0xFF5C6237),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB870),
    secondary = Color(0xFFE3C0A5),
    tertiary = Color(0xFFC5CB96),
)

@Composable
fun DibsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
