/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.pack.msdf.Msdf
import dev.shadr.pack.msdf.MsdfFont
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MsdfTest {
    private val size = 64
    private val spread = 8.0

    private fun field(shape: java.awt.Shape) =
        Msdf.render(shape, size, size, AffineTransform(), spread)

    private fun median(argb: Int): Double {
        val r = ((argb shr 16) and 0xFF) / 255.0
        val g = ((argb shr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return maxOf(minOf(r, g), minOf(maxOf(r, g), b))
    }

    private fun alpha(argb: Int) = ((argb ushr 24) and 0xFF) / 255.0

    private fun at(pixels: IntArray, x: Int, y: Int) = pixels[y * size + x]

    @Test
    fun `inside encodes above half and outside below`() {
        val pixels = field(Rectangle2D.Double(16.0, 16.0, 32.0, 32.0))
        assertTrue(median(at(pixels, 32, 32)) > 0.9, "deep inside should saturate high")
        assertTrue(median(at(pixels, 2, 2)) < 0.1, "far outside should saturate low")
    }

    @Test
    fun `the outline sits at one half`() {
        val pixels = field(Rectangle2D.Double(16.0, 16.0, 32.0, 32.0))

        val justOutside = median(at(pixels, 15, 32))
        val justInside = median(at(pixels, 16, 32))
        assertTrue(justOutside < 0.5, "texel outside the edge was $justOutside")
        assertTrue(justInside > 0.5, "texel inside the edge was $justInside")
        assertTrue(abs((justOutside + justInside) / 2.0 - 0.5) < 0.02, "edge should straddle 0.5")
    }

    @Test
    fun `value ramps linearly with distance at the configured spread`() {
        val pixels = field(Rectangle2D.Double(16.0, 16.0, 32.0, 32.0))

        val step = median(at(pixels, 14, 32)) - median(at(pixels, 13, 32))
        assertEquals(1.0 / spread, step, 0.01)
    }

    @Test
    fun `channels disagree at a corner`() {
        val pixels = field(Rectangle2D.Double(16.0, 16.0, 32.0, 32.0))

        val argb = at(pixels, 10, 14)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val spreadOfChannels = maxOf(r, g, b) - minOf(r, g, b)
        assertTrue(spreadOfChannels > 8, "channels were flat ($r,$g,$b): degenerated to SDF")
    }

    @Test
    fun `channels agree on a smooth contour`() {
        val pixels = field(Ellipse2D.Double(12.0, 12.0, 40.0, 40.0))
        val argb = at(pixels, 20, 20)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        assertEquals(r, g, "smooth contour should not split channels")
        assertEquals(g, b, "smooth contour should not split channels")
    }

    @Test
    fun `alpha marks the field extent, which is what sets letter-spacing`() {
        val pixels = field(Rectangle2D.Double(16.0, 16.0, 32.0, 32.0))
        val marker = Msdf.FIELD_ALPHA

        assertEquals(marker, (at(pixels, 32, 32) ushr 24) and 0xFF, "interior must be marked")
        assertEquals(marker, (at(pixels, 13, 32) ushr 24) and 0xFF, "the spread band must be marked")

        assertEquals(0, (at(pixels, 4, 32) ushr 24) and 0xFF, "far outside must be unmarked")
    }

    @Test
    fun `every overlay's decode range matches the generator's spread`() {
        val overlays = File("../shaders/overlays").listFiles()?.sortedBy { it.name }.orEmpty()
        check(overlays.isNotEmpty()) { "no shader overlays found" }

        var checked = 0
        for (overlay in overlays) {
            val glsl = File(overlay, "include/hud_fragment.glsl")
            if (!glsl.isFile) continue
            val declared = Regex("#define\\s+SHADR_FIELD_RANGE\\s+([0-9.]+)")
                .find(glsl.readText())?.groupValues?.get(1)?.toDouble()
            assertEquals(4.0, declared, "${overlay.name} disagrees with FontAssets.MSDF_SPREAD")
            checked++
        }
        assertTrue(checked >= 3, "expected at least three overlays to decode, found $checked")
    }

    @Test
    fun `every overlay with a decode include also overrides a text fragment program`() {
        val overlays = File("../shaders/overlays").listFiles()?.sortedBy { it.name }.orEmpty()
        for (overlay in overlays) {
            if (!File(overlay, "include/hud_fragment.glsl").isFile) continue
            val core = File(overlay, "core")
            val programs = core.listFiles { f -> f.name.endsWith(".fsh") }.orEmpty()
            assertTrue(
                programs.any { it.name == "text.fsh" || it.name == "rendertype_text.fsh" },
                "${overlay.name} has a decode include but no text fragment program",
            )
        }
    }

    @Test
    fun `holes read as outside`() {
        val ring = Path2D.Double(Path2D.WIND_EVEN_ODD).apply {
            append(Ellipse2D.Double(8.0, 8.0, 48.0, 48.0), false)
            append(Ellipse2D.Double(24.0, 24.0, 16.0, 16.0), false)
        }
        val pixels = field(ring)
        assertTrue(median(at(pixels, 32, 32)) < 0.5, "the counter should be outside the glyph")
        assertTrue(median(at(pixels, 32, 14)) > 0.5, "the ring itself should be inside")
    }

    @Test
    fun `bakes the vendored UI typeface into a grid atlas`() {
        val ttf = File("../assets/font/nerd_mono.ttf")
        check(ttf.isFile) { "expected the vendored TTF at ${ttf.absolutePath}" }

        val baked = MsdfFont(ttf, cell = 32, spread = 4.0).bake(MsdfFont.DEFAULT_CHARSET)

        assertEquals(16 * 32, baked.image.width)
        assertEquals(baked.chars.size * 32, baked.image.height)
        assertTrue(baked.chars.all { it.length == 16 }, "every provider row must be full width")

        val index = MsdfFont.DEFAULT_CHARSET.indexOf('H')
        val cellX = (index % 16) * 32
        val cellY = (index / 16) * 32
        var inside = 0
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                if (median(baked.image.getRGB(cellX + x, cellY + y)) > 0.5) inside++
            }
        }
        assertTrue(inside > 20, "'H' produced almost no ink ($inside texels): glyph did not render")
        assertTrue(inside < 32 * 32 / 3, "'H' filled $inside texels: the outline is not being cut out")

        val spaceCentre = baked.image.getRGB(16, 16)
        assertEquals(0.0, alpha(spaceCentre), 0.01)
    }
}
