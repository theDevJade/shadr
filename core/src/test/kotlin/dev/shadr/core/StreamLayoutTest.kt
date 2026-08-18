/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.stream.MapPalette
import dev.shadr.core.stream.StreamFormat
import dev.shadr.core.stream.StreamLayout
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamLayoutTest {

    private val screens = listOf(
        1920 to 1080,
        3840 to 2160,
        1366 to 768,
        854 to 480,
        2560 to 1440,
        1280 to 800,
    )

    @Test
    fun `the word a fragment samples is the word the decoder looks for`() {
        val region = StreamLayout.Origin(0, 0)
        for (slots in listOf(1, 4, 9, 16)) {
            val columns = StreamFormat.slotColumns(slots)
            for (slot in 0 until slots) {
                val origin = StreamLayout.slotOrigin(region, slot, columns)
                for (wy in 0 until MapPalette.MAP_EDGE) {
                    for (wx in 0 until MapPalette.MAP_EDGE) {
                        val word = StreamLayout.Word(wx, wy)
                        val pixel = StreamLayout.pixelFor(origin, word)
                        assertEquals(word, StreamLayout.wordAt(origin, pixel.x, pixel.y), "slot $slot word $wx,$wy")
                    }
                }
            }
        }
    }

    @Test
    fun `the fragment interpolation the vertex hook sets up samples exactly one texel per pixel`() {
        val origin = StreamLayout.Origin(37, 91)
        val edge = MapPalette.MAP_EDGE.toDouble()
        for (localY in 0 until MapPalette.MAP_EDGE) {
            for (localX in 0 until MapPalette.MAP_EDGE) {
                val u = (localX + 0.5) / edge
                val v = 1.0 - (localY + 0.5) / edge
                val sampledX = floor(u * edge).toInt()
                val sampledY = floor(v * edge).toInt()
                val expected = StreamLayout.wordAt(origin, origin.x + localX, origin.y + localY)
                assertEquals(expected.x, sampledX, "u tap at $localX,$localY")
                assertEquals(expected.y, sampledY, "v tap at $localX,$localY")
            }
        }
    }

    @Test
    fun `slots never overlap and never leave the region`() {
        for (slots in 1..StreamFormat.MAX_SLOTS) {
            val columns = StreamFormat.slotColumns(slots)
            val rows = StreamFormat.slotRows(slots)
            val size = StreamLayout.regionSize(columns, rows)
            val claimed = HashSet<Long>()
            for (slot in 0 until slots) {
                val origin = StreamLayout.slotOrigin(StreamLayout.Origin(0, 0), slot, columns)
                assertTrue(origin.x + MapPalette.MAP_EDGE <= size.x, "slot $slot of $slots overflows width")
                assertTrue(origin.y + MapPalette.MAP_EDGE <= size.y, "slot $slot of $slots overflows height")
                assertTrue(claimed.add(origin.x.toLong() shl 32 or origin.y.toLong()), "slot $slot of $slots collides")
            }
        }
    }

    @Test
    fun `the region is clamped inside every screen it is asked about`() {
        for ((width, height) in screens) {
            val slots = StreamLayout.maxSlots(width, height)
            if (slots == 0) continue
            val columns = StreamFormat.slotColumns(slots)
            val rows = StreamFormat.slotRows(slots)
            val size = StreamLayout.regionSize(columns, rows)
            for (designX in listOf(0.0, 480.0, 960.0, 1919.0)) {
                for (designY in listOf(0.0, 270.0, 540.0, 1079.0)) {
                    val origin = StreamLayout.regionOrigin(
                        width.toDouble(), height.toDouble(), designX, designY, columns, rows,
                    )
                    assertTrue(origin.x >= 0 && origin.y >= 0, "${width}x$height origin $origin is negative")
                    assertTrue(
                        origin.x + size.x <= width && origin.y + size.y <= height,
                        "${width}x$height origin $origin spills a ${size.x}x${size.y} region",
                    )
                }
            }
        }
    }

    @Test
    fun `a panel anchored at the design origin lands at the top left of the screen`() {
        val columns = StreamFormat.slotColumns(16)
        val rows = StreamFormat.slotRows(16)
        val origin = StreamLayout.regionOrigin(1920.0, 1080.0, 0.0, 0.0, columns, rows)
        assertEquals(0, origin.x)
        assertEquals(1080 - 512, origin.y, "design y is measured from the top, device y from the bottom")
    }

    @Test
    fun `slot capacity degrades with the framebuffer instead of spilling`() {
        assertTrue(StreamLayout.fits(1920, 1080, 16))
        assertFalse(StreamLayout.fits(854, 480, 16))
        assertEquals(0, StreamLayout.maxSlots(100, 100))
        assertTrue(StreamLayout.maxSlots(854, 480) in 1..StreamFormat.MAX_SLOTS)
        for ((width, height) in screens) {
            val slots = StreamLayout.maxSlots(width, height)
            if (slots == 0) continue
            assertTrue(StreamLayout.fits(width, height, slots), "${width}x$height claims $slots slots but they do not fit")
            if (slots < StreamFormat.MAX_SLOTS) {
                assertFalse(
                    StreamLayout.fits(width, height, slots + 1),
                    "${width}x$height could have held more than $slots slots",
                )
            }
        }
    }

    @Test
    fun `the generated glsl carries every layout helper exactly once`() {
        val glsl = StreamLayout.glsl()
        val helpers = listOf(
            "int shadr_stream_join(",
            "ivec2 shadr_stream_region_size(",
            "ivec2 shadr_stream_region_origin(",
            "ivec2 shadr_stream_slot_origin(",
            "ivec2 shadr_stream_word_at(",
            "ivec2 shadr_stream_pixel_for(",
            "bool shadr_stream_in_region(",
        )
        for (helper in helpers) {
            assertEquals(1, glsl.split(helper).size - 1, "$helper is not declared exactly once")
        }
        assertFalse(
            java.io.File("../shaders/overlays/mc_26_2/include/shadr_stream.glsl").readText()
                .contains("shadr_stream_region_origin"),
            "the layout maths must live in generated GLSL only, so Kotlin stays the single source of truth",
        )
    }
}
