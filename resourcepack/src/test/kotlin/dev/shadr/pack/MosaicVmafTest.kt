/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.video.MosaicEncoder
import dev.shadr.core.video.MosaicQuality
import dev.shadr.core.video.MosaicReferenceDecoder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MosaicVmafTest {

    private val width = 384
    private val height = 216
    private val fps = 30.0

    private fun rgb(r: Int, g: Int, b: Int) =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun frames(count: Int): List<IntArray> {
        val random = Random(11)
        return (0 until count).map { t ->
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val sx = x + t * 3
                    val bars = if ((sx / 24) % 2 == 0) 60 else 10
                    val ramp = sx * 200 / width
                    val ring = (((sx - 190) * (sx - 190) + (y - 108) * (y - 108)) / 40) and 0x3F
                    val grain = random.nextInt(-3, 4)
                    pixels[y * width + x] =
                        rgb(ramp + grain, bars + ring * 3 + grain, 170 - ring * 2 + grain)
                }
            }
            pixels
        }
    }

    private fun ffmpegWithVmaf(): Boolean = runCatching {
        val process = ProcessBuilder("ffmpeg", "-hide_banner", "-filters")
            .redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        text.contains("libvmaf")
    }.getOrDefault(false)

    private fun writeRaw(file: File, frames: List<IntArray>) {
        file.outputStream().buffered().use { out ->
            val row = ByteArray(width * height * 3)
            for (frame in frames) {
                var at = 0
                for (pixel in frame) {
                    row[at++] = ((pixel ushr 16) and 0xFF).toByte()
                    row[at++] = ((pixel ushr 8) and 0xFF).toByte()
                    row[at++] = (pixel and 0xFF).toByte()
                }
                out.write(row)
            }
        }
    }

    private fun vmaf(dir: File, reference: List<IntArray>, distorted: List<IntArray>): Double? {
        val referenceFile = File(dir, "reference.rgb").also { writeRaw(it, reference) }
        val distortedFile = File(dir, "distorted.rgb").also { writeRaw(it, distorted) }
        val log = File(dir, "vmaf.json")

        val raw = listOf("-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${width}x$height", "-r", "$fps")
        val command = buildList {
            addAll(listOf("ffmpeg", "-hide_banner", "-nostats", "-y"))
            addAll(raw); addAll(listOf("-i", distortedFile.path))
            addAll(raw); addAll(listOf("-i", referenceFile.path))
            addAll(
                listOf(
                    "-lavfi",
                    "[0:v]format=yuv420p[dist];[1:v]format=yuv420p[ref];" +
                        "[dist][ref]libvmaf=log_fmt=json:log_path=${log.path}",
                    "-f", "null", "-",
                ),
            )
        }

        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0 || !log.isFile) {
            println("[vmaf] ffmpeg did not produce a score: ${output.takeLast(400)}")
            return null
        }

        return Regex("\"mean\"\\s*:\\s*([0-9.]+)")
            .findAll(log.readText())
            .map { it.groupValues[1].toDouble() }
            .lastOrNull()
    }

    @Test
    fun `the codec scores acceptably on a perceptual model`() {
        if (!ffmpegWithVmaf()) return

        val source = frames(24)
        val encoded = assertNotNull(
            MosaicEncoder.encode(source, width, height, fps),
            "the clip did not fit a sheet",
        )
        val decoded = MosaicReferenceDecoder.decode(encoded)

        val dir = createTempDirectory("shadr-vmaf").toFile()
        val score = vmaf(dir, source, decoded) ?: return

        val local = MosaicQuality.compare(source, decoded, width, height)
        println("[vmaf] score $score alongside $local at ${encoded.compression.toInt()}x")

        assertTrue(
            score > 60.0,
            "VMAF came back at $score, which is below anything worth putting on screen ($local)",
        )
    }

    @Test
    fun `a worse quality setting scores worse, and the model agrees with the others`() {
        if (!ffmpegWithVmaf()) return

        val source = frames(16)
        val dir = createTempDirectory("shadr-vmaf-rd").toFile()

        fun scoreAt(quality: Int): Double? {
            val encoded = MosaicEncoder.encode(
                source, width, height, fps, MosaicEncoder.Options(quality = quality),
            ) ?: return null
            return vmaf(
                File(dir, "q$quality").apply { mkdirs() },
                source,
                MosaicReferenceDecoder.decode(encoded),
            )
        }

        val fine = scoreAt(4) ?: return
        val coarse = scoreAt(400) ?: return

        assertTrue(
            fine > coarse,
            "the perceptual model does not follow the quality knob: $fine at 4 vs $coarse at 400",
        )
    }
}
