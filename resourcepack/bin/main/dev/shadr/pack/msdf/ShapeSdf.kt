/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack.msdf

import java.awt.image.BufferedImage
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object ShapeSdf {
    fun roundedRectangle(size: Int, radius: Double, spread: Double = 4.0): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val half = size / 2.0
        val clampedRadius = radius.coerceIn(0.0, half)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val px = x + 0.5 - half
                val py = y + 0.5 - half
                val distance = -signedDistanceToRoundedBox(px, py, half, half, clampedRadius)
                image.setRGB(x, y, encode(distance, spread))
            }
        }
        return image
    }

    fun circle(size: Int, spread: Double = 4.0): BufferedImage =
        roundedRectangle(size, size / 2.0, spread)

    private fun signedDistanceToRoundedBox(
        px: Double,
        py: Double,
        halfWidth: Double,
        halfHeight: Double,
        radius: Double,
    ): Double {
        val qx = kotlin.math.abs(px) - (halfWidth - radius)
        val qy = kotlin.math.abs(py) - (halfHeight - radius)
        val outside = hypot(max(qx, 0.0), max(qy, 0.0))
        val inside = min(max(qx, qy), 0.0)
        return outside + inside - radius
    }

    private fun encode(distance: Double, spread: Double): Int {
        val normalized = ((0.5 + distance / spread).coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        val alpha = if (kotlin.math.abs(distance) <= spread || distance > 0) Msdf.FIELD_ALPHA else 0
        return (alpha shl 24) or (normalized shl 16) or (normalized shl 8) or normalized
    }
}
