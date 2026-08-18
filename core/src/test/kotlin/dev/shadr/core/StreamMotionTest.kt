/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.stream.StreamVideoSource
import java.io.File
import java.util.stream.IntStream
import kotlin.test.Test

class StreamMotionTest {

    private val video = File("../contents/videos/demo.mp4")

    private fun ffmpeg(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private val cu = 16
    private val search = 12

    private fun sad(a: IntArray, b: IntArray, w: Int, ax: Int, ay: Int, bx: Int, by: Int, cap: Int): Int {
        var total = 0
        for (y in 0 until cu) {
            var ai = (ay + y) * w + ax
            var bi = (by + y) * w + bx
            for (x in 0 until cu) {
                val p = a[ai++]
                val q = b[bi++]
                total += Math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
                    Math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
                    Math.abs((p and 0xFF) - (q and 0xFF))
            }
            if (total > cap) return total
        }
        return total
    }

    @Test
    fun `how much of a real 60fps frame is predictable from the last one`() {
        if (!ffmpeg() || !video.isFile) {
            println("skipping motion study: ffmpeg or contents/videos/demo.mp4 missing")
            return
        }

        val width = 1920
        val height = 1088
        val fps = 60.0
        val source = StreamVideoSource(video, width, height, fps, loop = true)

        val cusX = width / cu
        val cusY = height / cu
        val total = cusX * cusY

        var previous: IntArray? = null
        val skipPct = mutableListOf<Double>()
        val mcPct = mutableListOf<Double>()
        var studied = 0

        try {
            val deadline = System.nanoTime() + 60_000_000_000L
            while (studied < 12 && System.nanoTime() < deadline) {
                val frame = source.poll()
                if (frame == null) {
                    Thread.sleep(5)
                    continue
                }
                val prev = previous
                previous = frame
                if (prev == null) continue

                val skipThreshold = cu * cu * 3 * 6
                val mcThreshold = cu * cu * 3 * 10

                val skips = java.util.concurrent.atomic.AtomicInteger()
                val mcs = java.util.concurrent.atomic.AtomicInteger()

                IntStream.range(0, cusY).parallel().forEach { cy ->
                    var localSkip = 0
                    var localMc = 0
                    val ay = cy * cu
                    for (cx in 0 until cusX) {
                        val ax = cx * cu
                        val still = sad(frame, prev, width, ax, ay, ax, ay, skipThreshold)
                        if (still <= skipThreshold) {
                            localSkip++
                            continue
                        }
                        var best = still
                        var step = 8
                        var bx = ax
                        var by = ay
                        while (step >= 1) {
                            var movedX = bx
                            var movedY = by
                            for (dy in -1..1) {
                                for (dx in -1..1) {
                                    val nx = bx + dx * step
                                    val ny = by + dy * step
                                    if (nx < 0 || ny < 0 || nx + cu > width || ny + cu > height) continue
                                    if (Math.abs(nx - ax) > search || Math.abs(ny - ay) > search) continue
                                    val error = sad(frame, prev, width, ax, ay, nx, ny, best)
                                    if (error < best) {
                                        best = error
                                        movedX = nx
                                        movedY = ny
                                    }
                                }
                            }
                            bx = movedX
                            by = movedY
                            step /= 2
                        }
                        if (best <= mcThreshold) localMc++
                    }
                    skips.addAndGet(localSkip)
                    mcs.addAndGet(localMc)
                }

                skipPct += skips.get() * 100.0 / total
                mcPct += mcs.get() * 100.0 / total
                studied++
            }
        } finally {
            source.close()
        }

        if (skipPct.isEmpty()) {
            println("skipping motion study: no frame pairs decoded (${source.failure()})")
            return
        }

        val skip = skipPct.average()
        val mc = mcPct.average()
        val coded = 100.0 - skip - mc

        println(
            buildString {
                appendLine()
                appendLine("motion study: ${width}x$height at ${fps.toInt()} fps, ${cu}x$cu units, +/-$search search")
                appendLine("  frame pairs      ${skipPct.size}")
                appendLine("  SKIP (no send)   %.1f%%".format(skip))
                appendLine("  MC only (2 words)%.1f%%".format(mc))
                appendLine("  needs residual   %.1f%%".format(coded))
                appendLine()
                appendLine("  a unit is ${cu * cu} px; $total units per frame")
                appendLine("  residual units   %.0f of $total".format(coded * total / 100))
            },
        )
    }
}
