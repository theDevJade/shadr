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
import kotlin.test.Test

class StreamArtifactStudyTest {

    private val video = File("../contents/videos/demo.mp4")

    private fun ffmpeg(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun cuSad(a: IntArray, b: IntArray, width: Int, ox: Int, oy: Int): Int {
        var total = 0
        for (y in 0 until StreamCodec.CU) {
            var ai = (oy + y) * width + ox
            var bi = ai
            for (x in 0 until StreamCodec.CU) {
                val p = a[ai++]
                val q = b[bi++]
                total += Math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
                    Math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
                    Math.abs((p and 0xFF) - (q and 0xFF))
            }
        }
        return total
    }

    @Test
    fun `what the viewer actually sees over time`() {
        if (!ffmpeg() || !video.isFile) {
            println("skipping artifact study: ffmpeg or demo.mp4 missing")
            return
        }

        val width = 1920
        val height = 1088
        val geometry = StreamCodec.Geometry(width, height, 2000, 8, 4)
        val channel = StreamChannel(geometry.slots, 0)
        val view = IntArray(width * height)
        val workspace = StreamCodec.Workspace(geometry)
        val options = StreamCodec.Options(
            mcThreshold = StreamCodec.CU * StreamCodec.CU * 3 * 7,
            refreshPerFrame = geometry.cus / 120,
            payloadBudget = 140_000,
        )

        val perPx = StreamCodec.CU * StreamCodec.CU * 3
        val visible = perPx * 6

        val prevSrc = IntArray(width * height)
        val prevView = IntArray(width * height)
        val age = IntArray(geometry.cus)
        val errByMode = DoubleArray(5)
        val cusByMode = LongArray(5)
        val errByAge = DoubleArray(6)
        val cusByAge = LongArray(6)
        var flickerExcess = 0.0
        var flickerCus = 0L
        var staticCus = 0L
        val popMagnitude = DoubleArray(6)
        val popCount = LongArray(6)
        var demandRes = 0L
        var demandIntra = 0L
        var frames = 0

        val source = StreamVideoSource(video, width, height, 60.0, true, "ffmpeg", false)
        try {
            while (frames < 150) {
                val frame = source.poll()
                if (frame == null) {
                    Thread.sleep(3)
                    continue
                }
                System.arraycopy(view, 0, prevView, 0, view.size)
                StreamCodec.encode(channel, frame, view, geometry, options, frames, workspace)

                if (frames >= 30) {
                    for (cu in 0 until geometry.cus) {
                        val ox = cu % geometry.cusX * StreamCodec.CU
                        val oy = cu / geometry.cusX * StreamCodec.CU
                        val mode = workspace.modes[cu]
                        val err = cuSad(frame, view, width, ox, oy)
                        errByMode[mode] += err.toDouble() / perPx
                        cusByMode[mode]++

                        val coded = mode == StreamCodec.MODE_RESIDUAL || mode == StreamCodec.MODE_INTRA
                        val bucket = when {
                            coded -> 0
                            age[cu] <= 2 -> 1
                            age[cu] <= 5 -> 2
                            age[cu] <= 11 -> 3
                            age[cu] <= 23 -> 4
                            else -> 5
                        }
                        errByAge[bucket] += err.toDouble() / perPx
                        cusByAge[bucket]++

                        val srcDelta = cuSad(frame, prevSrc, width, ox, oy)
                        val viewDelta = cuSad(view, prevView, width, ox, oy)
                        if (srcDelta < perPx) {
                            staticCus++
                            if (viewDelta > srcDelta * 2 + perPx) {
                                flickerCus++
                                flickerExcess += (viewDelta - srcDelta).toDouble() / perPx
                            }
                        }

                        if (coded && age[cu] > 0) {
                            val popBucket = when {
                                age[cu] <= 2 -> 1
                                age[cu] <= 5 -> 2
                                age[cu] <= 11 -> 3
                                age[cu] <= 23 -> 4
                                else -> 5
                            }
                            popMagnitude[popBucket] += viewDelta.toDouble() / perPx
                            popCount[popBucket]++
                        }

                        if (coded) {
                            age[cu] = 0
                        } else if (err > visible) {
                            age[cu]++
                        } else {
                            age[cu] = 0
                        }

                        if (workspace.resultErr[cu] > 0 && !workspace.forced[cu]) {
                            if (mode == StreamCodec.MODE_RESIDUAL) demandRes++ else if (mode == StreamCodec.MODE_INTRA) demandIntra++
                        }
                    }
                }
                System.arraycopy(frame, 0, prevSrc, 0, frame.size)
                frames++
            }
        } finally {
            source.close()
        }

        val measured = frames - 30
        check(measured > 30) { "not enough frames" }
        val names = arrayOf("SKIP", "MC", "SPLIT", "RESIDUAL", "INTRA")
        val ageNames = arrayOf("coded now", "stale 1-2", "stale 3-5", "stale 6-11", "stale 12-23", "stale 24+")
        println(
            buildString {
                appendLine()
                appendLine("artifact study over $measured frames at 1080p60, live config (budget 140k)")
                appendLine()
                appendLine("  error carried by mode (mean abs err/px while in that mode)")
                for (m in 0 until 5) {
                    if (cusByMode[m] == 0L) continue
                    appendLine(
                        "    %-9s %5.1f%% of units   %.2f err/px".format(
                            names[m], cusByMode[m] * 100.0 / cusByMode.sum(), errByMode[m] / cusByMode[m],
                        ),
                    )
                }
                appendLine()
                appendLine("  drift: error vs frames since the unit was last coded")
                for (b in 0 until 6) {
                    if (cusByAge[b] == 0L) continue
                    appendLine(
                        "    %-12s %6.1f%%   %.2f err/px".format(
                            ageNames[b], cusByAge[b] * 100.0 / cusByAge.sum(), errByAge[b] / cusByAge[b],
                        ),
                    )
                }
                appendLine()
                appendLine("  flicker: reconstruction moving where the source did not")
                appendLine(
                    "    %.2f%% of static units flicker, mean excess %.2f err/px".format(
                        flickerCus * 100.0 / staticCus.coerceAtLeast(1),
                        if (flickerCus == 0L) 0.0 else flickerExcess / flickerCus,
                    ),
                )
                appendLine()
                appendLine("  pops: correction magnitude when a stale unit finally codes")
                for (b in 1 until 6) {
                    if (popCount[b] == 0L) continue
                    appendLine(
                        "    after %-11s %6d pops   %.1f delta/px".format(
                            ageNames[b].removePrefix("stale "), popCount[b], popMagnitude[b] / popCount[b],
                        ),
                    )
                }
            },
        )
    }
}
