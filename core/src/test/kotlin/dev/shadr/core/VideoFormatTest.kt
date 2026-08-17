/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.shader.GlslComposer
import dev.shadr.core.shader.GlslHelpers
import dev.shadr.core.video.VideoFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoFormatTest {
    private val overlay = File("../shaders/overlays/mc_26_2").canonicalFile
    private val include = File(overlay, "include/shadr_video.glsl")

    private fun define(name: String): String? =
        Regex("""#define\s+$name\s+(\S+)""").find(include.readText())?.groupValues?.get(1)

    @Test
    fun `the marker layout is the same on both sides of the pack boundary`() {
        assertTrue(include.isFile, "no video include at ${include.path}")

        assertEquals(VideoFormat.MARKER_ALPHA.toString(), define("SHADR_VIDEO_MARKER_ALPHA"))
        assertEquals(VideoFormat.KEY_BITS.toString(), define("SHADR_VIDEO_KEY_BITS"))
        assertEquals(VideoFormat.UV_MAX.toString(), define("SHADR_VIDEO_UV_MAX"))
        assertEquals(VideoFormat.UV_CONTINUITY.toString(), define("SHADR_VIDEO_UV_CONTINUITY"))
    }

    @Test
    fun `the shader clock agrees with the one the item shaders use`() {
        assertEquals(
            GlslHelpers.CYCLE_SECONDS,
            define("SHADR_VIDEO_CYCLE")?.toDouble(),
            "a video keyed to a different clock than shadr_time() drifts against every other animation",
        )
    }

    @Test
    fun `the video marker cannot be mistaken for the custom shader marker`() {
        assertTrue(
            VideoFormat.MARKER_ALPHA != GlslComposer.POSITION_ALPHA,
            "item.fsh picks the marker protocol by alpha, so the two must differ",
        )
    }

    @Test
    fun `every uv the marker can carry survives the round trip`() {
        for (x in 0..VideoFormat.UV_MAX) {
            for (y in listOf(0, 1, 511, 512, 1022, VideoFormat.UV_MAX)) {
                val u = x.toDouble() / VideoFormat.UV_MAX
                val v = y.toDouble() / VideoFormat.UV_MAX

                for (px in 0..3) {
                    for (py in 0..3) {
                        val (r, g, b) = VideoFormat.pack(u, v, px, py)

                        assertTrue(
                            r in 0..255 && g in 0..255 && b in 0..255,
                            "packed outside a byte at $x,$y",
                        )

                        val back = VideoFormat.unpack(r, g, b, px, py)
                        assertNotNull(back, "the key was lost at $x,$y on tile $px,$py")
                        assertEquals(x to y, back, "uv changed on the round trip")
                    }
                }
            }
        }
    }

    @Test
    fun `uv is clamped rather than wrapped`() {
        assertEquals(VideoFormat.pack(0.0, 0.0, 0, 0), VideoFormat.pack(-1.0, -0.5, 0, 0))
        assertEquals(VideoFormat.pack(1.0, 1.0, 0, 0), VideoFormat.pack(2.0, 1.5, 0, 0))
    }

    @Test
    fun `a marker read at the wrong pixel is not a marker`() {
        val (r, g, b) = VideoFormat.pack(0.5, 0.5, 0, 0)
        assertNotNull(VideoFormat.unpack(r, g, b, 0, 0))
        assertNotNull(VideoFormat.unpack(r, g, b, 4, 8), "the tag repeats every 4x4 tile")

        for (px in 0..3) {
            for (py in 0..3) {
                if (px == 0 && py == 0) continue
                assertNull(VideoFormat.unpack(r, g, b, px, py), "false match at offset $px,$py in the tile")
            }
        }
    }

    private fun claims(field: (Int, Int) -> Triple<Int, Int, Int>, x: Int, y: Int): Boolean {
        val centre = field(x, y).let { VideoFormat.unpack(it.first, it.second, it.third, x, y) }
            ?: return false

        val agree = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).count { (dx, dy) ->
            val (nx, ny) = x + dx to y + dy
            val near = field(nx, ny).let { VideoFormat.unpack(it.first, it.second, it.third, nx, ny) }
            near != null &&
                Math.abs(near.first - centre.first) <= VideoFormat.UV_CONTINUITY &&
                Math.abs(near.second - centre.second) <= VideoFormat.UV_CONTINUITY
        }
        // A panel corner only ever has 2 cardinal neighbours that are actually
        // part of the panel, so this must stay at 2, not 3 - see the matching
        // comment in shadr_video_composite.fsh.
        return agree >= 2
    }

    @Test
    fun `the sky is not mistaken for a video panel`() {
        val sky = { _: Int, y: Int ->
            val t = (y % 512) / 512.0
            Triple(
                (110 + t * 60).toInt().coerceIn(0, 255),
                (170 + t * 60).toInt().coerceIn(0, 255),
                (210 + t * 45).toInt().coerceIn(0, 255),
            )
        }

        for (y in 0 until 1080) {
            for (x in 0 until 8) {
                assertTrue(!claims(sky, x, y), "the sky was claimed at $x,$y: ${sky(x, y)}")
            }
        }
    }

    @Test
    fun `flat scene colour is not mistaken for a video panel`() {
        for (r in 0..255) {
            val flat = { _: Int, _: Int -> Triple(r, 0x80, 0x40) }
            assertTrue(!claims(flat, 0, 0), "flat red $r was claimed")
            assertTrue(!claims(flat, 1, 0), "flat red $r was claimed")
            assertTrue(!claims(flat, 0, 1), "flat red $r was claimed")
            assertTrue(!claims(flat, 1, 1), "flat red $r was claimed")
        }
    }

    @Test
    fun `a panel the shaders wrote is claimed`() {
        val width = 640
        val panel = { x: Int, y: Int ->
            VideoFormat.pack(x.toDouble() / width, y.toDouble() / width, x, y)
        }

        for (y in 1 until 16) {
            for (x in 1 until 16) {
                assertTrue(claims(panel, x, y), "the panel was dropped at $x,$y")
            }
        }
    }

    @Test
    fun `a panel corner is claimed even though only 2 of its neighbours are panel`() {
        val width = 640
        val height = 360
        val background = Triple(200, 200, 200)
        val panel = { x: Int, y: Int ->
            if (x in 0 until width && y in 0 until height) {
                VideoFormat.pack(x.toDouble() / width, y.toDouble() / height, x, y)
            } else {
                background
            }
        }

        assertTrue(claims(panel, 0, 0), "top-left corner was dropped")
        assertTrue(claims(panel, width - 1, 0), "top-right corner was dropped")
        assertTrue(claims(panel, 0, height - 1), "bottom-left corner was dropped")
        assertTrue(claims(panel, width - 1, height - 1), "bottom-right corner was dropped")
    }

    @Test
    fun `the continuity bound admits the narrowest panel worth drawing`() {
        // UV_MAX dropped from 2047 (11-bit uv) to 1023 (10-bit) to fund the wider
        // key, and UV_CONTINUITY was separately tightened from 64 to 16 to cut down
        // false positives on sharp content (see its doc comment) - net effect on
        // this floor is 32px -> 64px.
        val narrowest = VideoFormat.UV_MAX / VideoFormat.UV_CONTINUITY
        assertTrue(
            narrowest <= 64,
            "a panel narrower than $narrowest px steps uv faster than the neighbour test allows",
        )
    }
}
