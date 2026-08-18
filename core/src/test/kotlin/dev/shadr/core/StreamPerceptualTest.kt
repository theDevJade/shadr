/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.stream.StreamChannel
import dev.shadr.core.stream.StreamCodec
import dev.shadr.core.stream.StreamVideoSource
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

class StreamPerceptualTest {

    private val video = File("../contents/videos/demo.mp4")

    private val width = 1920

    private val height = 1088

    private fun ffmpegWithVmaf(): Boolean = runCatching {
        val process = ProcessBuilder("ffmpeg", "-hide_banner", "-filters")
            .redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        text.contains("libvmaf")
    }.getOrDefault(false)

    private fun appendRaw(out: java.io.OutputStream, frame: IntArray, row: ByteArray) {
        var at = 0
        for (pixel in frame) {
            row[at++] = ((pixel ushr 16) and 0xFF).toByte()
            row[at++] = ((pixel ushr 8) and 0xFF).toByte()
            row[at++] = (pixel and 0xFF).toByte()
        }
        out.write(row)
    }

    private fun metric(dir: File, filter: String, log: File?): String? {
        val raw = listOf("-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "${width}x$height", "-r", "60")
        val command = buildList {
            addAll(listOf("ffmpeg", "-hide_banner", "-nostats", "-y"))
            addAll(raw)
            addAll(listOf("-i", File(dir, "recon.rgb").path))
            addAll(raw)
            addAll(listOf("-i", File(dir, "source.rgb").path))
            addAll(listOf("-lavfi", filter, "-f", "null", "-"))
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) {
            println("  ffmpeg failed: ${output.takeLast(300)}")
            return null
        }
        return if (log != null && log.isFile) log.readText() else output
    }

    private fun run(name: String, skipPerPx: Int, mcPerPx: Int, lumaStep: Int, chromaStep: Int, pool: Int, budget: Int) {
        val geometry = StreamCodec.Geometry(width, height, pool, lumaStep, chromaStep)
        val channel = StreamChannel(geometry.slots, 0)
        val view = IntArray(width * height)
        val workspace = StreamCodec.Workspace(geometry)
        val options = StreamCodec.Options(
            skipThreshold = StreamCodec.CU * StreamCodec.CU * 3 * skipPerPx,
            mcThreshold = StreamCodec.CU * StreamCodec.CU * 3 * mcPerPx,
            refreshPerFrame = geometry.cus / 120,
            payloadBudget = budget,
        )

        val dir = createTempDirectory("shadr-perceptual-$name").toFile()
        val row = ByteArray(width * height * 3)
        var frames = 0

        val source = StreamVideoSource(video, width, height, 60.0, true, "ffmpeg", false)
        try {
            File(dir, "source.rgb").outputStream().buffered().use { srcOut ->
                File(dir, "recon.rgb").outputStream().buffered().use { reconOut ->
                    while (frames < 90) {
                        val frame = source.poll()
                        if (frame == null) {
                            Thread.sleep(3)
                            continue
                        }
                        StreamCodec.encode(channel, frame, view, geometry, options, frames, workspace)
                        if (frames >= 30) {
                            appendRaw(srcOut, frame, row)
                            appendRaw(reconOut, view, row)
                        }
                        frames++
                    }
                }
            }
        } finally {
            source.close()
        }

        val vmafLog = File(dir, "vmaf.json")
        val vmafText = metric(
            dir,
            "[0:v]format=yuv420p[d];[1:v]format=yuv420p[r];" +
                "[d][r]libvmaf=n_threads=8:log_fmt=json:log_path=${vmafLog.path}",
            vmafLog,
        )
        val vmafScores = vmafText?.let { text ->
            Regex(
                "\"vmaf\"\\s*:\\s*\\{\\s*\"min\"\\s*:\\s*([0-9.]+).*?" +
                    "\"mean\"\\s*:\\s*([0-9.]+).*?\"harmonic_mean\"\\s*:\\s*([0-9.]+)",
                RegexOption.DOT_MATCHES_ALL,
            ).find(text)?.let {
                Triple(it.groupValues[2].toDouble(), it.groupValues[1].toDouble(), it.groupValues[3].toDouble())
            }
        }

        val ssimText = metric(dir, "[0:v][1:v]ssim", null)
        val ssim = ssimText?.let { Regex("All:([0-9.]+)").findAll(it).lastOrNull()?.groupValues?.get(1)?.toDouble() }

        val xpsnrText = metric(dir, "[0:v][1:v]xpsnr", null)
        val xpsnrLine = xpsnrText?.lineSequence()?.lastOrNull { it.contains("XPSNR") }?.trim()

        println(
            "  %-9s vmaf %.1f (min %.1f, harmonic %.1f)   ssim %.4f".format(
                name,
                vmafScores?.first ?: Double.NaN,
                vmafScores?.second ?: Double.NaN,
                vmafScores?.third ?: Double.NaN,
                ssim ?: Double.NaN,
            ),
        )
        if (xpsnrLine != null) println("            $xpsnrLine")
        dir.deleteRecursively()
    }

    @Test
    fun `perceptual scores across the quality ladder`() {
        if (!ffmpegWithVmaf() || !video.isFile) {
            println("skipping perceptual study: libvmaf or demo.mp4 missing")
            return
        }
        println()
        println("perceptual study, 60 frames of 1080p60, demo.mp4")
        run("balanced", 4, 7, 8, 4, 2000, 140_000)
        run("high", 2, 4, 4, 2, 2600, 240_000)
        run("ultra", 1, 3, 2, 1, 3200, 480_000)
    }
}
