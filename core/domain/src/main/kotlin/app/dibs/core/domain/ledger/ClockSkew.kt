// SPDX-License-Identifier: Apache-2.0
package app.dibs.core.domain.ledger

import kotlin.math.abs

/**
 * Wall-clock sanity check (SPEC §6.3 case 22). Ordering never uses wall
 * clocks — this exists only so the UI can warn "your clock looks wrong" when
 * the local clock is more than a day away from the newest op seen, because the
 * *displayed* dates would then be misleading.
 */
object ClockSkew {

    const val DEFAULT_THRESHOLD_MILLIS: Long = 24 * 60 * 60 * 1000L

    fun isSkewed(
        localWallClockMillis: Long,
        newestOpWallClockMillis: Long?,
        thresholdMillis: Long = DEFAULT_THRESHOLD_MILLIS,
    ): Boolean = newestOpWallClockMillis != null &&
        abs(localWallClockMillis - newestOpWallClockMillis) > thresholdMillis
}
