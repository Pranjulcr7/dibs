// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale. One place, so screens compose from a rhythm instead of
 * sprinkling magic dp values; 48dp is the accessibility floor for anything
 * tappable (NFR-10) and must never be shrunk locally.
 */
object Dimens {
    val spacingXs = 4.dp
    val spacingSm = 8.dp
    val spacingMd = 16.dp
    val spacingLg = 24.dp
    val spacingXl = 32.dp
    val minTouchTarget = 48.dp
    val chipIconSize = 18.dp
    val inviteQrSize = 220.dp
    val guideFrameStroke = 2.dp
}
