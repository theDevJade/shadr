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
        assertEquals(VideoFormat.KEY.toString(), define("SHADR_VIDEO_KEY"))
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
            for (y in listOf(0, 1, 1023, 1024, 2046, VideoFormat.UV_MAX)) {
                val u = x.toDouble() / VideoFormat.UV_MAX
                val v = y.toDouble() / VideoFormat.UV_MAX
                val (r, g, b) = VideoFormat.pack(u, v)

                assertTrue(r in 0..255 && g in 0..255 && b in 0..255, "packed outside a byte at $x,$y")

                val back = VideoFormat.unpack(r, g, b)
                assertNotNull(back, "the key was lost at $x,$y")
                assertEquals(x to y, back, "uv changed on the round trip")
            }
        }
    }

    @Test
    fun `uv is clamped rather than wrapped`() {
        assertEquals(VideoFormat.pack(0.0, 0.0), VideoFormat.pack(-1.0, -0.5))
        assertEquals(VideoFormat.pack(1.0, 1.0), VideoFormat.pack(2.0, 1.5))
    }

    @Test
    fun `colours outside the key are left to the rest of the ui`() {
        var claimed = 0
        for (r in 0..255) {
            if (VideoFormat.unpack(r, 0, 0) == null) continue
            claimed++
        }
        assertEquals(
            1 shl (8 - VideoFormat.KEY_BITS), claimed,
            "the key is ${VideoFormat.KEY_BITS} bits wide, so that many red values may claim a pixel",
        )
        assertNull(VideoFormat.unpack(0x3F, 0, 0))
        assertNull(VideoFormat.unpack(0xC0, 0, 0))
    }

    @Test
    fun `the continuity bound admits the narrowest panel worth drawing`() {
        val narrowest = VideoFormat.UV_MAX / VideoFormat.UV_CONTINUITY
        assertTrue(
            narrowest <= 32,
            "a panel narrower than $narrowest px steps uv faster than the neighbour test allows",
        )
    }
}
