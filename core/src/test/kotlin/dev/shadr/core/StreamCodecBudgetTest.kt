/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.stream.MapPalette
import dev.shadr.core.stream.StreamChannel
import dev.shadr.core.stream.StreamCodec
import dev.shadr.core.stream.StreamSink
import dev.shadr.core.stream.StreamVideoSource
import java.io.File
import kotlin.math.log10
import kotlin.test.Test

class StreamCodecBudgetTest {

    private val video = File("../contents/videos/demo.mp4")

    private fun ffmpeg(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun deflate(data: ByteArray): Int {
        val d = java.util.zip.Deflater()
        d.setInput(data)
        d.finish()
        val out = ByteArray(data.size + 64)
        var n = 0
        while (!d.finished()) {
            val w = d.deflate(out, n, out.size - n)
            if (w == 0) break
            n += w
        }
        d.end()
        return n
    }

    @Test
    fun `what the inter frame codec costs at 1080p60`() {
        if (!ffmpeg() || !video.isFile) {
            println("skipping codec budget: ffmpeg or contents/videos/demo.mp4 missing")
            return
        }
        run(4, 7, 8, 4, 2000, 140_000)
        run(2, 4, 4, 2, 2600, 240_000)
        run(1, 3, 2, 1, 3200, 480_000)
    }

    private fun regionOf(g: StreamCodec.Geometry, address: Int): Int = when {
        address < g.splitBase -> 1
        address < g.residualBase -> 2
        address < g.poolBase -> 3
        else -> 4
    }

    private fun run(skipPerPx: Int, mcPerPx: Int, lumaStep: Int, chromaStep: Int, pool: Int, payloadBudget: Int) {
        val width = 1920
        val height = 1088
        val geometry = StreamCodec.Geometry(width, height, pool, lumaStep, chromaStep)
        val channel = StreamChannel(geometry.slots, 0)
        val view = IntArray(width * height)
        val options = StreamCodec.Options(
            skipThreshold = StreamCodec.CU * StreamCodec.CU * 3 * skipPerPx,
            mcThreshold = StreamCodec.CU * StreamCodec.CU * 3 * mcPerPx,
            refreshPerFrame = geometry.cus / 120,
            payloadBudget = payloadBudget,
        )

        val buffered = ArrayList<IntArray>()
        StreamVideoSource(video, width, height, 60.0, true, "ffmpeg", false).use { feed ->
            val deadline = System.nanoTime() + 60_000_000_000L
            while (buffered.size < 16 && System.nanoTime() < deadline) {
                val frame = feed.poll()
                if (frame == null) {
                    Thread.sleep(3)
                    continue
                }
                buffered.add(frame.copyOf())
            }
        }
        if (buffered.size < 16) {
            println("skipping codec budget: only ${buffered.size} frame(s) decoded")
            return
        }
        val workspace = StreamCodec.Workspace(geometry)
        var decisionMs = 0.0
        var emitMs = 0.0
        var applyMs = 0.0
        var raw = 0L
        var deflated = 0L
        var dirtyMaps = 0
        var frames = 0
        var encodeNanos = 0L
        var lastStats: StreamCodec.Stats? = null
        var quality = 0.0
        val regionRaw = LongArray(5)

        while (frames < 150) {
            run {
                val frame = buffered[frames % buffered.size]

                val started = System.nanoTime()
                val stats = StreamCodec.encode(channel, frame, view, geometry, options, frames, workspace)
                encodeNanos += System.nanoTime() - started
                decisionMs += workspace.decisionNanos / 1e6
                emitMs += workspace.emitNanos / 1e6
                applyMs += workspace.applyNanos / 1e6
                lastStats = stats

                for (slot in 0 until geometry.slots) {
                    channel.header(slot, 0, if (slot == 0) frames else 0, 0, 0)
                }

                var frameRaw = 0L
                var frameZ = 0L
                val maps = HashSet<Int>()
                val measuring = frames > 89
                channel.flush(
                    StreamSink { mapId, _, startY, w, h, colors ->
                        frameRaw += colors.size
                        frameZ += deflate(colors)
                        maps.add(mapId)
                        if (measuring) {
                            for (r in 0 until h) {
                                val row = startY + r
                                if (row == 0) {
                                    regionRaw[0] += w
                                    continue
                                }
                                val address = mapId * StreamCodec.SLOT_PAYLOAD + (row - 1) * MapPalette.MAP_EDGE
                                regionRaw[regionOf(geometry, address)] += w
                            }
                        }
                    },
                )

                if (measuring) {
                    raw += frameRaw
                    deflated += frameZ
                    dirtyMaps += maps.size
                    var total = 0.0
                    for (i in frame.indices step 37) {
                        for (shift in listOf(16, 8, 0)) {
                            val d = ((frame[i] shr shift) and 0xFF) - ((view[i] shr shift) and 0xFF)
                            total += (d * d).toDouble()
                        }
                    }
                    val n = (frame.indices step 37).count() * 3
                    quality += 10.0 * log10(255.0 * 255.0 / (total / n).coerceAtLeast(1e-9))
                }
                frames++
            }
        }

        val measured = frames - 90
        check(measured >= 5) { "not enough measured frames" }

        val meanRaw = raw.toDouble() / measured
        val meanZ = deflated.toDouble() / measured
        println(
            buildString {
                appendLine()
                appendLine(
                    "transform codec ${width}x$height 60fps, skip $skipPerPx mc $mcPerPx " +
                        "luma $lumaStep chroma $chromaStep pool $pool budget $payloadBudget, $measured frames",
                )
                appendLine(
                    "  arena        ${geometry.slots} slots, ${geometry.totalWords} words " +
                        "(plane ${geometry.planeWords}, pool ${geometry.poolWords})",
                )
                appendLine("  modes        $lastStats")
                appendLine("  raw sent     %.0f KiB/frame".format(meanRaw / 1024))
                appendLine(
                    "  regions      header %.1f / plane %.1f / split %.1f / residual %.1f / pool %.1f KiB".format(
                        regionRaw[0].toDouble() / measured / 1024,
                        regionRaw[1].toDouble() / measured / 1024,
                        regionRaw[2].toDouble() / measured / 1024,
                        regionRaw[3].toDouble() / measured / 1024,
                        regionRaw[4].toDouble() / measured / 1024,
                    ),
                )
                appendLine("  deflated     %.1f KiB/frame  (%.1fx)".format(meanZ / 1024, meanRaw / meanZ))
                appendLine("  dirty maps   %.1f of ${geometry.slots}".format(dirtyMaps.toDouble() / measured))
                appendLine("  quality      %.1f dB".format(quality / measured))
                appendLine("  encode       %.1f ms/frame".format(encodeNanos / 1e6 / frames))
                appendLine(
                    "  phases       decision %.1f / emit %.1f / apply %.1f ms".format(
                        decisionMs / frames, emitMs / frames, applyMs / frames,
                    ),
                )
                appendLine()
                appendLine(
                    "  AT 60 FPS    %.2f MB/s  =  %.1f Mbit/s".format(
                        meanZ * 60 / 1048576, meanZ * 60 * 8 / 1e6,
                    ),
                )
            },
        )
    }
}
