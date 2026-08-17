/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.hud

import kotlin.math.roundToInt

object ShapeBuckets {

    const val COUNT = 32

    const val MAX_FRACTION = 0.5

    fun fractionFor(index: Int): Double =
        index.coerceIn(0, COUNT - 1) * MAX_FRACTION / (COUNT - 1)

    fun bucketFor(fraction: Double): Int =
        (fraction.coerceIn(0.0, MAX_FRACTION) / MAX_FRACTION * (COUNT - 1)).roundToInt()

    fun bucketForRadius(radius: Double, width: Double, height: Double): Int {
        val shorter = minOf(width, height)
        if (shorter <= 0.0) return 0
        return bucketFor(radius / shorter)
    }
}
