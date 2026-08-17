/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.video.VideoBudget
import dev.shadr.core.video.VideoSheet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoBudgetTest {

    @Test
    fun `whatever it plans actually lays out`() {
        for (frames in listOf(1, 30, 120, 600, 1800, 3600)) {
            val sheet = VideoBudget.plan(1920, 1080, frames) ?: continue
            assertEquals(
                sheet,
                VideoSheet.fit(sheet.frameWidth, sheet.frameHeight, frames),
                "the plan for $frames frames disagrees with the layout it claims",
            )
            assertTrue(sheet.capacity >= frames, "the plan holds fewer frames than asked for")
        }
    }

    @Test
    fun `a longer clip is paid for in resolution, not in frames`() {
        val short = assertNotNull(VideoBudget.plan(1920, 1080, 60))
        val long = assertNotNull(VideoBudget.plan(1920, 1080, 600))
        assertTrue(
            long.frameHeight < short.frameHeight,
            "ten times the frames should have cost resolution, got ${long.frameHeight} vs ${short.frameHeight}",
        )
        assertTrue(long.capacity >= 600, "the long plan dropped frames instead")
    }

    @Test
    fun `the source aspect survives the trade`() {
        for ((w, h) in listOf(1920 to 1080, 1280 to 720, 640 to 480, 1080 to 1920)) {
            val sheet = assertNotNull(VideoBudget.plan(w, h, 200), "no plan for ${w}x$h")
            val source = w.toDouble() / h
            val planned = sheet.frameWidth.toDouble() / sheet.frameHeight
            assertTrue(
                kotlin.math.abs(source - planned) / source < 0.05,
                "${w}x$h became ${sheet.frameWidth}x${sheet.frameHeight}, an aspect change",
            )
        }
    }

    @Test
    fun `dimensions stay even so chroma subsampling has something to work with`() {
        for (frames in listOf(10, 100, 1000)) {
            for ((w, h) in listOf(1920 to 1080, 1001 to 667, 333 to 777)) {
                val sheet = VideoBudget.plan(w, h, frames) ?: continue
                assertEquals(0, sheet.frameWidth % 2, "odd width for ${w}x$h at $frames frames")
                assertEquals(0, sheet.frameHeight % 2, "odd height for ${w}x$h at $frames frames")
            }
        }
    }

    @Test
    fun `a clip is never upscaled past its source`() {
        val sheet = assertNotNull(VideoBudget.plan(320, 180, 10))
        assertTrue(sheet.frameHeight <= 180, "a 180p source was planned at ${sheet.frameHeight}")
    }

    @Test
    fun `a clip that cannot fit at any size is refused`() {
        assertNull(
            VideoBudget.plan(1920, 1080, 1_000_000),
            "a million frames should have no plan at all",
        )
        assertNull(VideoBudget.plan(0, 0, 10))
        assertNull(VideoBudget.plan(1920, 1080, 0))
    }

    @Test
    fun `frame counts follow the rate`() {
        assertEquals(600, VideoBudget.frameCount(10.0, 60.0))
        assertEquals(300, VideoBudget.frameCount(10.0, 30.0))
        assertEquals(1, VideoBudget.frameCount(0.0, 60.0), "an empty clip is still one frame")
    }
}
