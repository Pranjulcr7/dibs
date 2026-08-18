// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The warm default palette (SPEC §4.2) used below Android 12, where dynamic
 * color is unavailable. Seeded from a terracotta/amber primary so the app reads
 * friendly rather than corporate; all pairings meet 4.5:1 (NFR-10).
 */
internal val WarmLightColors = lightColorScheme(
    primary = Color(0xFF8B5000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF2C1600),
    secondary = Color(0xFF745943),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCBE),
    onSecondaryContainer = Color(0xFF2A1707),
    tertiary = Color(0xFF5C6237),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE1E8B1),
    onTertiaryContainer = Color(0xFF191E00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF221A12),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF221A12),
    surfaceVariant = Color(0xFFF2DFD1),
    onSurfaceVariant = Color(0xFF51453A),
    outline = Color(0xFF837468),
)

internal val WarmDarkColors = darkColorScheme(
    primary = Color(0xFFFFB870),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF693C00),
    onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = Color(0xFFE3C0A5),
    onSecondary = Color(0xFF422C19),
    secondaryContainer = Color(0xFF5B422D),
    onSecondaryContainer = Color(0xFFFFDCBE),
    tertiary = Color(0xFFC5CB96),
    onTertiary = Color(0xFF2E330D),
    tertiaryContainer = Color(0xFF444A21),
    onTertiaryContainer = Color(0xFFE1E8B1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF19120B),
    onBackground = Color(0xFFF0E0D4),
    surface = Color(0xFF19120B),
    onSurface = Color(0xFFF0E0D4),
    surfaceVariant = Color(0xFF51453A),
    onSurfaceVariant = Color(0xFFD6C3B5),
    outline = Color(0xFF9E8E81),
)

/**
 * Semantic colors for money: green when the user is owed, red when they owe,
 * neutral when settled (SPEC §4.1 group cards). A separate token set — not
 * `primary`/`error` — because dynamic color would otherwise recolor debt to
 * whatever the wallpaper suggests, and red-means-owe must survive any theme.
 */
@Immutable
data class MoneyColors(
    val positive: Color,
    val negative: Color,
    val neutral: Color,
)

internal val LightMoneyColors = MoneyColors(
    positive = Color(0xFF1B6E20),
    negative = Color(0xFFB3261E),
    neutral = Color(0xFF51453A),
)

internal val DarkMoneyColors = MoneyColors(
    positive = Color(0xFF8BD88E),
    negative = Color(0xFFFFB4AB),
    neutral = Color(0xFFD6C3B5),
)

val LocalMoneyColors = staticCompositionLocalOf { LightMoneyColors }
