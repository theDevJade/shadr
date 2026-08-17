/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.video.MosaicEncoder
import dev.shadr.core.video.MosaicQuality
import dev.shadr.core.video.MosaicReferenceDecoder
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MosaicQualityTest {

    private val width = 192
    private val height = 128

    private fun rgb(r: Int, g: Int, b: Int) =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun frame(t: Int, random: Random): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = x + t * 2
                val plate = if (((sx / 16) + (y / 16)) % 2 == 0) 40 else 0
                val ramp = (sx * 255 / width) and 0xFF
                val ring = (((sx - 96) * (sx - 96) + (y - 64) * (y - 64)) / 24) and 0x3F
                val grain = random.nextInt(-4, 5)
                pixels[y * width + x] = rgb(ramp + grain, plate + ring * 2 + grain, 150 - ring + grain)
            }
        }
        return pixels
    }

    private fun clip(count: Int, seed: Int = 7): List<IntArray> {
        val random = Random(seed)
        return (0 until count).map { frame(it, random) }
    }

    private fun measure(
        frames: List<IntArray>,
        options: MosaicEncoder.Options = MosaicEncoder.Options(),
    ): MosaicQuality.Report {
        val encoded = assertNotNull(
            MosaicEncoder.encode(frames, width, height, 30.0, options),
            "the clip did not fit a sheet",
        )
        val decoded = MosaicReferenceDecoder.decode(encoded)
        return MosaicQuality.compare(frames, decoded, width, height)
    }

    @Test
    fun `a clip clears the quality floor on both measures`() {
        val report = measure(clip(24))

        assertTrue(report.psnr > 28.0, "PSNR floor missed: $report")
        assertTrue(report.ssim > 0.85, "SSIM floor missed: $report")
    }

    @Test
    fun `quality does not sag across a group of pictures`() {
        val gop = 12
        val report = measure(clip(gop * 3), MosaicEncoder.Options(gopFrames = gop))

        val worst = assertNotNull(report.worstPsnr)
        assertTrue(
            report.psnr - worst.psnr < 6.0,
            "frame ${worst.index} is ${"%.1f".format(report.psnr - worst.psnr)} dB below the " +
                "mean, which is a sag rather than noise: $report",
        )

        for (start in gop until report.frames.size step gop) {
            val before = report.frames[start - 1]
            val after = report.frames[start]
            assertTrue(
                before.psnr > after.psnr - 8.0,
                "quality collapsed to ${"%.1f".format(before.psnr)} dB by frame ${before.index} " +
                    "and the keyframe at ${after.index} restored it to ${"%.1f".format(after.psnr)}",
            )
        }
    }

    @Test
    fun `a motionless clip settles and never degrades`() {
        val still = frame(0, Random(1))
        val report = measure(List(16) { still.copyOf() }, MosaicEncoder.Options(gopFrames = 16))

        for (i in 1 until report.frames.size) {
            val previous = report.frames[i - 1]
            val current = report.frames[i]
            assertTrue(
                current.psnr >= previous.psnr - 1e-9,
                "a motionless clip lost quality at frame ${current.index}: " +
                    "${"%.3f".format(previous.psnr)} dB to ${"%.3f".format(current.psnr)} dB",
            )
        }

        val tail = report.frames.takeLast(4)
        for (frame in tail) {
            assertEquals(
                tail.first().psnr, frame.psnr, 1e-9,
                "frame ${frame.index} is still changing after a dozen motionless frames",
            )
        }
    }

    @Test
    fun `asking for more quality delivers more quality`() {
        val frames = clip(16)
        val coarse = measure(frames, MosaicEncoder.Options(quality = 200))
        val fine = measure(frames, MosaicEncoder.Options(quality = 4))

        assertTrue(
            fine.psnr > coarse.psnr + 1.0,
            "the quality knob does nothing: ${"%.1f".format(coarse.psnr)} dB at 200 " +
                "vs ${"%.1f".format(fine.psnr)} dB at 4",
        )
        assertTrue(fine.ssim > coarse.ssim, "SSIM did not follow the quality knob")
    }

    @Test
    fun `the measures agree that identical frames are identical`() {
        val frames = clip(4)
        val report = MosaicQuality.compare(frames, frames, width, height)

        assertEquals(99.0, report.psnr, 1e-9, "PSNR of a perfect match should saturate")
        assertTrue(report.ssim > 0.9999, "SSIM of a perfect match came back at ${report.ssim}")
    }

    @Test
    fun `the measures notice damage that the other might miss`() {
        val original = clip(1).first()

        val lifted = IntArray(original.size) { i ->
            val c = original[i]
            rgb(((c ushr 16) and 0xFF) + 20, ((c ushr 8) and 0xFF) + 20, (c and 0xFF) + 20)
        }

        val punched = original.copyOf()
        for (i in punched.indices step 97) punched[i] = punched[i] xor 0xFFFFFF

        val liftedSsim = MosaicQuality.ssim(original, lifted, width, height)
        val punchedSsim = MosaicQuality.ssim(original, punched, width, height)

        assertTrue(
            punchedSsim < liftedSsim,
            "SSIM rated scattered damage ($punchedSsim) no worse than a flat shift ($liftedSsim), " +
                "so it is adding nothing over PSNR",
        )
        assertTrue(abs(1.0 - liftedSsim) < 0.2, "a flat shift should barely move SSIM")
    }
}
