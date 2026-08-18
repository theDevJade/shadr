/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.stream.MapPalette
import dev.shadr.core.stream.StreamBlocks
import dev.shadr.core.stream.StreamChannel
import dev.shadr.core.stream.StreamSink
import dev.shadr.core.stream.StreamVideoSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class StreamBudgetTest {

    private val video = File("../contents/videos/demo.mp4")

    private fun ffmpeg(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private class Counter : StreamSink {
        var bytes = 0L
        var deflated = 0L
        var patches = 0
        var dirtyMaps = HashSet<Int>()

        override fun mapData(mapId: Int, startX: Int, startY: Int, width: Int, height: Int, colors: ByteArray) {
            bytes += colors.size
            deflated += deflate(colors)
            patches++
            dirtyMaps.add(mapId)
        }

        fun reset() {
            bytes = 0; deflated = 0; patches = 0; dirtyMaps = HashSet()
        }

        private fun deflate(data: ByteArray): Int {
            val deflater = java.util.zip.Deflater()
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArray(data.size + 64)
            var total = 0
            while (!deflater.finished()) {
                val n = deflater.deflate(out, total, out.size - total)
                if (n == 0) break
                total += n
            }
            deflater.end()
            return total
        }
    }

    @Test
    fun `measure what 1080p actually costs on the wire`() {
        if (!ffmpeg() || !video.isFile) {
            println("skipping budget measurement: ffmpeg or contents/videos/demo.mp4 missing")
            return
        }
        for (threshold in listOf(0.0, 40.0, 120.0, 400.0)) run(threshold)
    }

    private fun run(skipThreshold: Double) {
        val columns = 15
        val rows = 8
        val slots = columns * rows
        val layout = StreamBlocks.Layout(blocksX = 32, blocksY = 34)
        assertTrue(layout.fits(), "the 1080p tile does not fit a slot")

        val channel = StreamChannel(slots, mapIdBase = 0, columns = columns)
        val width = layout.width(columns)
        val height = layout.height(rows)

        val source = StreamVideoSource(video, width, height, fps = 30.0, loop = true)
        val counter = Counter()
        val perFrame = mutableListOf<Long>()
        val perFrameZ = mutableListOf<Long>()
        val dirty = mutableListOf<Int>()
        var encodeNanos = 0L
        var frames = 0
        var skips = 0.0

        try {
            val deadline = System.nanoTime() + 40_000_000_000L
            while (frames < 40 && System.nanoTime() < deadline) {
                val frame = source.poll()
                if (frame == null) {
                    Thread.sleep(5)
                    continue
                }
                val started = System.nanoTime()
                val stats = StreamBlocks.encode(channel, frame, width, height, false, layout, skipThreshold)
                encodeNanos += System.nanoTime() - started
                if (frames > 0) skips += stats.skipped * 100.0 / stats.total
                for (slot in 0 until slots) channel.header(slot, 0, frames, 0, 0)
                counter.reset()
                channel.flush(counter)
                if (frames > 0) {
                    perFrame += counter.bytes
                    perFrameZ += counter.deflated
                    dirty += counter.dirtyMaps.size
                }
                frames++
            }
        } finally {
            source.close()
        }

        if (perFrame.size < 5) {
            println("skipping budget measurement: only ${perFrame.size} frame(s) decoded (${source.failure()})")
            return
        }

        val fullFrame = slots.toLong() * (MapPalette.MAP_EDGE + layout.words)
        val mean = perFrame.average()
        val worst = perFrame.max()
        val meanDirty = dirty.average()
        val encodeMs = encodeNanos / 1e6 / frames

        println(
            buildString {
                appendLine()
                appendLine("1080p BC1, skip threshold $skipThreshold, ${perFrame.size} inter frames of ${video.name}")
                appendLine("  geometry     ${width}x$height, $slots slots (${columns}x$rows), ${layout.words} words/slot")
                appendLine("  full frame   ${fullFrame / 1024} KiB")
                appendLine("  mean sent    ${(mean / 1024).toInt()} KiB  (${(mean * 100 / fullFrame).toInt()}% of the working set)")
                appendLine("  worst sent   ${worst / 1024} KiB")
                appendLine("  dirty maps   ${meanDirty.toInt()} of $slots per frame")
                appendLine("  blocks kept  %.0f%%".format(skips / perFrame.size))
                appendLine("  encode       %.1f ms/frame".format(encodeMs))
                val meanZ = perFrameZ.average()
                appendLine("  deflated     ${(meanZ / 1024).toInt()} KiB  (%.2fx)".format(mean / meanZ))
                appendLine("  at 60 fps    %.1f MB/s raw, %.1f MB/s deflated (%.0f Mbit/s)".format(
                    mean * 60 / 1048576, meanZ * 60 / 1048576, meanZ * 60 * 8 / 1e6,
                ))
                appendLine("  encode budget at 60 fps is 16.7 ms/frame")
            },
        )

        assertTrue(mean >= 0, "byte accounting went negative")
    }
}
