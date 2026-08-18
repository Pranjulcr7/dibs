// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import app.dibs.core.domain.settings.ThemeMode

/**
 * The single theme entry point. Dynamic color on Android 12+ (FR-T1), the warm
 * palette otherwise; [MoneyColors] ride along via [LocalMoneyColors] so debt
 * red / owed green stay semantically fixed regardless of wallpaper.
 */
@Composable
fun DibsTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> WarmDarkColors
        else -> WarmLightColors
    }
    CompositionLocalProvider(LocalMoneyColors provides if (darkTheme) DarkMoneyColors else LightMoneyColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DibsTypography,
            content = content,
        )
    }
}
