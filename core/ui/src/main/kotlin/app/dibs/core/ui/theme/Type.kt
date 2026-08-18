// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val DibsTypography = Typography()

/**
 * Styles for money amounts. All use tabular figures (`tnum`) so digits align
 * in columns and don't shimmer as values change — the amount is the largest
 * text on any screen that shows one (SPEC §4.2), which makes proportional
 * digits visibly ragged.
 */
object AmountTextStyles {
    val large = TextStyle(
        fontSize = 44.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
    val medium = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
    val small = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
}
