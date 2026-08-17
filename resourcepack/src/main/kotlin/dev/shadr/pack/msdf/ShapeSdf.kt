/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack.msdf

import java.awt.image.BufferedImage
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Distance fields for UI primitives, computed **analytically**.
 *
 * Glyph fields have to be measured against a flattened outline, so they inherit whatever
 * error the flattening leaves behind. A rounded rectangle does not: its signed distance has
 * a closed form, so every texel can be exact. That matters more here than for text, because
 * a box is a large flat expanse where a fraction of a texel of error along an edge reads as
 * a visible wobble.
 *
 * Encoded to the same layout [Msdf] produces (distance in RGB with 0.5 as the outline, and
 * [Msdf.FIELD_ALPHA] marking the described band) so the one fragment decode handles both.
 * All three channels carry the same value: a rounded rectangle has no corners in the MSDF
 * sense (every join is a tangent-continuous arc), so there is nothing for three channels to
 * disagree about and a plain field is exactly right.
 */
object ShapeSdf {

    /**
     * A rounded rectangle filling [size] texels, with [radius] texel corners.
     *
     * @param spread distance range in texels, matching the shader's decode range.
     *
     * The field is generated on a *square* texture even when the element is not square,
     * because the shader reconstructs the shape from the field's geometry, and a stretched
     * quad would stretch the corner arcs into ellipses and make the antialiasing width
     * differ per axis. Elements pick a radius, not an aspect.
     */
    fun roundedRectangle(size: Int, radius: Double, spread: Double = 4.0): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val half = size / 2.0
        val clampedRadius = radius.coerceIn(0.0, half)

        for (y in 0 until size) {
            for (x in 0 until size) {
                // Sample at the texel centre, as the GPU will.
                val px = x + 0.5 - half
                val py = y + 0.5 - half
                val distance = -signedDistanceToRoundedBox(px, py, half, half, clampedRadius)
                image.setRGB(x, y, encode(distance, spread))
            }
        }
        return image
    }

    /** A filled circle, for dots, radio marks and cursor hotspots. */
    fun circle(size: Int, spread: Double = 4.0): BufferedImage =
        roundedRectangle(size, size / 2.0, spread)

    /**
     * The standard rounded-box distance, positive outside.
     *
     * Fold the point into the first quadrant by absolute value, shift in by the corner
     * radius, and the problem collapses to the distance from a point to a rectangle corner:
     * outside on both axes it is the length of the offset, otherwise the larger single-axis
     * overshoot. Subtracting the radius rounds the corner.
     */
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

    /**
     * Same encoding as [Msdf]: 0.5 is the outline, alpha marks the described band.
     *
     * Note the asymmetry between the two uses of `spread`. The value saturates at
     * `spread / 2` texels either side of the outline, because the encoding is
     * `0.5 + d/spread` across a 0..1 range, but alpha marks out to the full `spread`,
     * because that band is letter-spacing rather than field data.
     */
    private fun encode(distance: Double, spread: Double): Int {
        val normalized = ((0.5 + distance / spread).coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        val alpha = if (kotlin.math.abs(distance) <= spread || distance > 0) Msdf.FIELD_ALPHA else 0
        return (alpha shl 24) or (normalized shl 16) or (normalized shl 8) or normalized
    }
}
