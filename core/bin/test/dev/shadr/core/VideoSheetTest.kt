/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.video.VideoSheet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoSheetTest {

    @Test
    fun `a sheet never exceeds the edge every gpu can load`() {
        for (w in listOf(1, 17, 128, 256, 480, 960, 1920, 4096)) {
            for (h in listOf(1, 9, 72, 144, 270, 540, 1080)) {
                val sheet = VideoSheet.fit(w, h, VideoSheet.capacityFor(w, h)) ?: continue
                assertTrue(
                    sheet.width <= VideoSheet.MAX_EDGE && sheet.height <= VideoSheet.MAX_EDGE,
                    "a ${w}x$h clip produced a ${sheet.width}x${sheet.height} sheet",
                )
            }
        }
    }

    @Test
    fun `a short clip pays only for the rows it uses`() {
        val sheet = assertNotNull(VideoSheet.fit(256, 144, 10))
        assertEquals(1, sheet.rows, "ten frames should not need a second row")
        assertEquals(10, sheet.columns, "columns should stop at the frame count")
        assertEquals(2560, sheet.width)
        assertEquals(144, sheet.height)
    }

    @Test
    fun `every frame lands on its own cell`() {
        val sheet = assertNotNull(VideoSheet.fit(256, 144, 100))
        val seen = mutableSetOf<Pair<Int, Int>>()
        for (frame in 0 until 100) {
            val origin = sheet.originOf(frame)
            assertTrue(seen.add(origin), "frame $frame reuses the cell at $origin")
            assertTrue(origin.first + sheet.frameWidth <= sheet.width, "frame $frame runs off the right")
            assertTrue(origin.second + sheet.frameHeight <= sheet.height, "frame $frame runs off the bottom")
        }
    }

    @Test
    fun `frames advance along a row before starting the next`() {
        val sheet = assertNotNull(VideoSheet.fit(1000, 100, 30))
        assertEquals(4, sheet.columns)
        assertEquals(0 to 0, sheet.originOf(0))
        assertEquals(1000 to 0, sheet.originOf(1))
        assertEquals(0 to 100, sheet.originOf(sheet.columns))
    }

    @Test
    fun `a clip too large for one sheet is refused rather than truncated`() {
        assertNull(VideoSheet.fit(1920, 1080, 100), "1080p x 100 cannot fit a 4096 sheet")
        assertNull(VideoSheet.fit(8192, 64, 1), "a frame wider than the edge has no layout")
        assertNull(VideoSheet.fit(256, 144, 0), "a clip with no frames is not a clip")
    }

    @Test
    fun `capacity agrees with what fit will actually accept`() {
        for ((w, h) in listOf(256 to 144, 128 to 72, 480 to 270, 64 to 64)) {
            val capacity = VideoSheet.capacityFor(w, h)
            assertNotNull(VideoSheet.fit(w, h, capacity), "${w}x$h rejected its own capacity")
            assertNull(VideoSheet.fit(w, h, capacity + 1), "${w}x$h accepted one frame too many")
        }
    }
}
