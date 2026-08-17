/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack

import dev.shadr.pack.msdf.Msdf
import dev.shadr.pack.msdf.ShapeSdf
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShapeSdfTest {
    private val size = 64
    private val spread = 4.0

    private fun value(argb: Int) = ((argb shr 16) and 0xFF) / 255.0
    private fun distance(argb: Int) = (value(argb) - 0.5) * spread

    @Test
    fun `all three channels agree, because a rounded box has no MSDF corner`() {
        val image = ShapeSdf.roundedRectangle(size, radius = 12.0, spread = spread)
        val argb = image.getRGB(20, 8)
        assertEquals((argb shr 16) and 0xFF, (argb shr 8) and 0xFF)
        assertEquals((argb shr 8) and 0xFF, argb and 0xFF)
    }

    @Test
    fun `the outline lands at one half all the way round`() {
        val radius = 16.0
        val image = ShapeSdf.roundedRectangle(size, radius, spread)
        val half = size / 2.0

        val acrossEdge = (0 until size).map { distance(image.getRGB(it, size / 2)) }
        val crossing = acrossEdge.indexOfFirst { it > 0 }
        assertTrue(crossing in 0..1, "left edge crossed at texel $crossing, expected the boundary")

        val centreX = radius
        val centreY = radius
        var closest = Double.MAX_VALUE
        var closestValue = 0.0
        for (y in 0 until size / 2) {
            for (x in 0 until size / 2) {
                val d = abs(hypot(x + 0.5 - centreX, y + 0.5 - centreY) - radius)
                if (d < closest) {
                    closest = d
                    closestValue = value(image.getRGB(x, y))
                }
            }
        }
        assertEquals(0.5, closestValue, 0.02, "the corner arc is not at the 0.5 contour")
        assertTrue(half > 0)
    }

    @Test
    fun `the field is the exact analytic distance, not an approximation`() {
        val radius = 10.0
        val image = ShapeSdf.roundedRectangle(size, radius, spread)
        val half = size / 2.0

        for ((x, y) in listOf(2 to 32, 32 to 2, 6 to 6, 32 to 32, 50 to 12)) {
            val px = abs(x + 0.5 - half) - (half - radius)
            val py = abs(y + 0.5 - half) - (half - radius)
            val outside = hypot(maxOf(px, 0.0), maxOf(py, 0.0)) + minOf(maxOf(px, py), 0.0) - radius

            val expected = (-outside).coerceIn(-spread / 2, spread / 2)
            assertEquals(expected, distance(image.getRGB(x, y)), 0.02, "texel ($x,$y)")
        }
    }

    @Test
    fun `a full-radius rounded rectangle is a circle`() {
        val image = ShapeSdf.circle(size, spread)
        val centre = size / 2.0

        val radius = centre
        val crossing = (0 until size / 2).first { value(image.getRGB(it, it)) >= 0.5 }
        val crossingRadius = hypot(crossing + 0.5 - centre, crossing + 0.5 - centre)
        assertEquals(radius, crossingRadius, 1.0, "the 0.5 contour is not at the radius")

        assertTrue(value(image.getRGB(2, 2)) < 0.1, "corner should be far outside a circle")
    }

    @Test
    fun `alpha marks the band so the advance and the decode agree with glyphs`() {
        val box = ShapeSdf.roundedRectangle(size, radius = 8.0, spread = spread)
        assertEquals(Msdf.FIELD_ALPHA, (box.getRGB(32, 32) ushr 24) and 0xFF, "interior marked")

        val circle = ShapeSdf.circle(size, spread)
        assertEquals(Msdf.FIELD_ALPHA, (circle.getRGB(32, 32) ushr 24) and 0xFF, "interior marked")
        assertEquals(0, (circle.getRGB(0, 0) ushr 24) and 0xFF, "beyond the band must be unmarked")
    }
}
