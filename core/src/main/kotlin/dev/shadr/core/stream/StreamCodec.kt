/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.stream

import dev.shadr.core.video.MosaicBc1
import java.util.stream.IntStream

object StreamCodec {

    const val CU = 16

    const val PLANE_WORDS = 5

    const val POOL_BLOCKS = (CU / StreamBlocks.BLOCK) * (CU / StreamBlocks.BLOCK)

    const val POOL_ENTRY_WORDS = POOL_BLOCKS * StreamBlocks.WORDS_PER_BLOCK

    const val MODE_SKIP = 0

    const val MODE_MC = 1

    const val MODE_SPLIT = 2

    const val MODE_RESIDUAL = 3

    const val MODE_INTRA = 4

    const val SUB = CU / 2

    const val SPLIT_WORDS = 8

    const val RES_BLOCK = 8

    const val KEPT = 4

    const val COEFFS = KEPT * KEPT

    const val RESIDUAL_BLOCK_WORDS = COEFFS + 3

    const val RESIDUAL_WORDS = 4 * RESIDUAL_BLOCK_WORDS

    const val RESIDUAL_BIAS = 64

    const val DEFAULT_LUMA_STEP = 6

    const val DEFAULT_CHROMA_STEP = 4

    const val MV_BIAS = 64

    const val MV_RANGE = 63

    const val SLOT_PAYLOAD = MapPalette.MAP_WORDS - MapPalette.MAP_EDGE

    val KEPT_U = intArrayOf(0, 4, 6, 2)

    private val SIGN = Array(RES_BLOCK) { x ->
        IntArray(KEPT) { k -> if (Integer.bitCount(x and KEPT_U[k]) % 2 == 0) 1 else -1 }
    }

    class Geometry(
        val frameWidth: Int,
        val frameHeight: Int,
        val poolEntries: Int,
        val lumaStep: Int = DEFAULT_LUMA_STEP,
        val chromaStep: Int = DEFAULT_CHROMA_STEP,
    ) {
        val cusX = frameWidth / CU
        val cusY = frameHeight / CU
        val cus = cusX * cusY
        val planeWords = cus * PLANE_WORDS
        val splitBase = planeWords
        val splitWords = cus * SPLIT_WORDS
        val residualBase = splitBase + splitWords
        val residualWords = RESIDUAL_WORDS
        val residualArena = cus * RESIDUAL_WORDS
        val poolBase = residualBase + residualArena
        val poolWords = poolEntries * POOL_ENTRY_WORDS
        val totalWords = planeWords + splitWords + residualArena + poolWords
        val slots = (totalWords + SLOT_PAYLOAD - 1) / SLOT_PAYLOAD

        init {
            require(frameWidth % CU == 0 && frameHeight % CU == 0) {
                "frame ${frameWidth}x$frameHeight is not a multiple of $CU"
            }
            require(slots <= StreamFormat.MAX_SLOTS) { "needs $slots slots, over the ${StreamFormat.MAX_SLOTS} cap" }
            require(lumaStep in 1..16) { "lumaStep $lumaStep out of range" }
            require(chromaStep in 1..16) { "chromaStep $chromaStep out of range" }
        }

        val refreshStride: Int = run {
            var stride = (cus * 0.618).toInt() or 1
            while (gcd(stride, cus) != 1) stride += 2
            stride
        }

        fun planeIndex(cx: Int, cy: Int): Int = (cy * cusX + cx) * PLANE_WORDS

        fun splitIndex(cu: Int): Int = splitBase + cu * SPLIT_WORDS

        fun residualIndex(cu: Int): Int = residualBase + cu * RESIDUAL_WORDS

        fun poolIndex(entry: Int): Int = poolBase + entry * POOL_ENTRY_WORDS
    }

    class Stats(val skip: Int, val mc: Int, val split: Int, val residual: Int, val intra: Int, val poolUsed: Int) {
        val total: Int get() = skip + mc + split + residual + intra
        override fun toString(): String =
            "skip=%.1f%% mc=%.1f%% split=%.1f%% res=%.1f%% intra=%.1f%% pool=%d".format(
                skip * 100.0 / total, mc * 100.0 / total, split * 100.0 / total,
                residual * 100.0 / total, intra * 100.0 / total, poolUsed,
            )
    }

    class Workspace(geometry: Geometry) {
        val modes = IntArray(geometry.cus)
        val mvx = IntArray(geometry.cus)
        val mvy = IntArray(geometry.cus)
        val splitMvx = IntArray(geometry.cus * 4)
        val splitMvy = IntArray(geometry.cus * 4)
        val residuals = IntArray(geometry.cus * RESIDUAL_WORDS)
        val stillErr = IntArray(geometry.cus)
        val predErr = IntArray(geometry.cus)
        val resultErr = IntArray(geometry.cus)
        val forced = BooleanArray(geometry.cus)
        val entries = IntArray(geometry.cus)
        val previous = IntArray(geometry.frameWidth * geometry.frameHeight)
        var decisionNanos = 0L
        var emitNanos = 0L
        var applyNanos = 0L
    }

    class Options(
        val skipThreshold: Int = CU * CU * 3 * 4,
        val mcThreshold: Int = CU * CU * 3 * 7,
        val searchRange: Int = 24,
        val refreshPerFrame: Int = 0,
        val intraGain: Double = 1.0,
        val residualBias: Double = 1.4,
        val payloadBudget: Int = 0,
        val refreshGate: Boolean = true,
    )

    fun write(channel: StreamChannel, address: Int, value: Int) {
        val slot = address / SLOT_PAYLOAD
        val local = MapPalette.MAP_EDGE + address % SLOT_PAYLOAD
        channel.slot(slot)[local] = value
    }

    fun read(channel: StreamChannel, address: Int): Int {
        val slot = address / SLOT_PAYLOAD
        val local = MapPalette.MAP_EDGE + address % SLOT_PAYLOAD
        return channel.slot(slot)[local]
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private val HALF_DX = intArrayOf(1, -1, 0, 0)

    private val HALF_DY = intArrayOf(0, 0, 1, -1)

    private fun avg2(a: Int, b: Int): Int = (a or b) - (((a xor b) and 0xFEFEFE) shr 1)

    private fun interpAt(prev: IntArray, i: Int, width: Int, fx: Int, fy: Int): Int =
        if (fy == 0) {
            avg2(prev[i], prev[i + 1])
        } else if (fx == 0) {
            avg2(prev[i], prev[i + width])
        } else {
            avg2(avg2(prev[i], prev[i + 1]), avg2(prev[i + width], prev[i + width + 1]))
        }

    private fun tapFits(origin: Int, mv2: Int, size: Int, limit: Int): Boolean {
        val base = origin + (mv2 shr 1)
        return base >= 0 && base + size + (mv2 and 1) <= limit
    }

    private fun sadHalfPel(
        source: IntArray,
        prev: IntArray,
        width: Int,
        ax: Int,
        ay: Int,
        mv2x: Int,
        mv2y: Int,
        size: Int,
        cap: Int,
    ): Int {
        val ix = mv2x shr 1
        val iy = mv2y shr 1
        val fx = mv2x and 1
        val fy = mv2y and 1
        if (fx == 0 && fy == 0) return sad(source, prev, width, ax, ay, ax + ix, ay + iy, cap, size)
        var total = 0
        for (y in 0 until size) {
            var si = (ay + y) * width + ax
            var pi = (ay + y + iy) * width + ax + ix
            for (x in 0 until size) {
                val a = source[si++]
                val b = interpAt(prev, pi, width, fx, fy)
                pi++
                total += Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
                    Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
                    Math.abs((a and 0xFF) - (b and 0xFF))
            }
            if (total > cap) return total
        }
        return total
    }

    private fun predictBlock(
        prev: IntArray,
        width: Int,
        ox: Int,
        oy: Int,
        size: Int,
        mv2x: Int,
        mv2y: Int,
        out: IntArray,
    ) {
        val ix = mv2x shr 1
        val iy = mv2y shr 1
        val fx = mv2x and 1
        val fy = mv2y and 1
        var k = 0
        for (y in 0 until size) {
            var i = (oy + y + iy) * width + ox + ix
            if (fx == 0 && fy == 0) {
                for (x in 0 until size) out[k++] = prev[i++]
            } else {
                for (x in 0 until size) {
                    out[k++] = interpAt(prev, i, width, fx, fy)
                    i++
                }
            }
        }
    }

    private fun predictIntoFrame(
        prev: IntArray,
        out: IntArray,
        width: Int,
        ox: Int,
        oy: Int,
        size: Int,
        mv2x: Int,
        mv2y: Int,
    ) {
        val ix = mv2x shr 1
        val iy = mv2y shr 1
        val fx = mv2x and 1
        val fy = mv2y and 1
        if (fx == 0 && fy == 0) {
            for (y in 0 until size) {
                System.arraycopy(prev, (oy + y + iy) * width + ox + ix, out, (oy + y) * width + ox, size)
            }
            return
        }
        for (y in 0 until size) {
            var i = (oy + y + iy) * width + ox + ix
            var o = (oy + y) * width + ox
            for (x in 0 until size) {
                out[o++] = interpAt(prev, i, width, fx, fy)
                i++
            }
        }
    }

    private fun to565(r: Int, g: Int, b: Int): Int = ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)

    private fun rangeFit(block: IntArray, rows: IntArray, palette: IntArray): Long =
        rangeFit(block, rows, palette, 1)

    private fun rangeFit(block: IntArray, rows: IntArray, palette: IntArray, refinements: Int): Long {
        var minR = 255
        var minG = 255
        var minB = 255
        var maxR = 0
        var maxG = 0
        var maxB = 0
        for (p in 0 until 16) {
            val at = p * 3
            val r = block[at]
            val g = block[at + 1]
            val b = block[at + 2]
            if (r < minR) minR = r
            if (g < minG) minG = g
            if (b < minB) minB = b
            if (r > maxR) maxR = r
            if (g > maxG) maxG = g
            if (b > maxB) maxB = b
        }
        var e0 = to565(maxR, maxG, maxB)
        var e1 = to565(minR, minG, minB)
        if (e0 < e1) {
            val t = e0
            e0 = e1
            e1 = t
        }
        MosaicBc1.fillPalette(e0, e1, palette)
        MosaicBc1.code(block, palette, rows)

        for (pass in 0 until refinements) {
            if (e0 <= e1) break
            var aa = 0.0
            var ab = 0.0
            var bb = 0.0
            val x0 = DoubleArray(3)
            val x1 = DoubleArray(3)
            for (p in 0 until 16) {
                val choice = (rows[p / 4] ushr ((p % 4) * 2)) and 3
                val t = when (choice) {
                    0 -> 0.0
                    1 -> 1.0
                    2 -> 1.0 / 3.0
                    else -> 2.0 / 3.0
                }
                val u = 1.0 - t
                aa += u * u
                ab += u * t
                bb += t * t
                val at = p * 3
                for (ch in 0 until 3) {
                    x0[ch] += u * block[at + ch]
                    x1[ch] += t * block[at + ch]
                }
            }
            val det = aa * bb - ab * ab
            if (det > 1e-6) {
                var r0 = 0
                var g0 = 0
                var b0 = 0
                var r1 = 0
                var g1 = 0
                var b1 = 0
                for (ch in 0 until 3) {
                    val v0 = ((bb * x0[ch] - ab * x1[ch]) / det).coerceIn(0.0, 255.0)
                    val v1 = ((aa * x1[ch] - ab * x0[ch]) / det).coerceIn(0.0, 255.0)
                    when (ch) {
                        0 -> { r0 = v0.toInt(); r1 = v1.toInt() }
                        1 -> { g0 = v0.toInt(); g1 = v1.toInt() }
                        else -> { b0 = v0.toInt(); b1 = v1.toInt() }
                    }
                }
                var f0 = to565(r0, g0, b0)
                var f1 = to565(r1, g1, b1)
                if (f0 < f1) {
                    val t = f0
                    f0 = f1
                    f1 = t
                }
                if (f0 != f1) {
                    e0 = f0
                    e1 = f1
                }
            }
            if (pass + 1 < refinements) {
                MosaicBc1.fillPalette(e0, e1, palette)
                MosaicBc1.code(block, palette, rows)
            }
        }

        MosaicBc1.fillPalette(e0, e1, palette)
        val error = MosaicBc1.code(block, palette, rows)
        return (error.toLong() shl 32) or (e0.toLong() shl 16) or e1.toLong()
    }

    private fun sad(a: IntArray, b: IntArray, width: Int, ax: Int, ay: Int, bx: Int, by: Int, cap: Int): Int =
        sad(a, b, width, ax, ay, bx, by, cap, CU)

    private fun sadEstimate(
        a: IntArray,
        b: IntArray,
        width: Int,
        ax: Int,
        ay: Int,
        bx: Int,
        by: Int,
        cap: Int,
        size: Int,
        phase: Int = 0,
    ): Int {
        var total = 0
        val halfCap = cap shr 1
        var y = phase
        while (y < size) {
            var ai = (ay + y) * width + ax
            var bi = (by + y) * width + bx
            for (x in 0 until size) {
                val p = a[ai++]
                val q = b[bi++]
                total += Math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
                    Math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
                    Math.abs((p and 0xFF) - (q and 0xFF))
            }
            if (total > halfCap) return total shl 1
            y += 2
        }
        return total shl 1
    }

    private fun sad(
        a: IntArray,
        b: IntArray,
        width: Int,
        ax: Int,
        ay: Int,
        bx: Int,
        by: Int,
        cap: Int,
        size: Int,
    ): Int {
        var total = 0
        for (y in 0 until size) {
            var ai = (ay + y) * width + ax
            var bi = (by + y) * width + bx
            for (x in 0 until size) {
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

    fun encode(
        channel: StreamChannel,
        source: IntArray,
        reconstruction: IntArray,
        geometry: Geometry,
        options: Options = Options(),
        frameIndex: Int = 0,
        workspace: Workspace? = null,
    ): Stats {
        val width = geometry.frameWidth
        val height = geometry.frameHeight
        require(source.size == width * height) { "source frame does not match the geometry" }
        require(reconstruction.size == source.size) { "reconstruction does not match the geometry" }

        val ws = workspace ?: Workspace(geometry)
        val modes = ws.modes
        val mvx = ws.mvx
        val mvy = ws.mvy
        val splitMvx = ws.splitMvx
        val splitMvy = ws.splitMvy
        val residuals = ws.residuals
        val stillErr = ws.stillErr
        val predErr = ws.predErr
        val resultErr = ws.resultErr
        val forcedArr = ws.forced
        java.util.Arrays.fill(forcedArr, false)

        val refreshFrom = if (options.refreshPerFrame > 0) {
            (frameIndex.toLong() * options.refreshPerFrame % geometry.cus).toInt()
        } else {
            -1
        }

        val decisionStart = System.nanoTime()
        IntStream.range(0, geometry.cus).parallel().forEach { index ->
            run {
                val cx = index % geometry.cusX
                val cy = index / geometry.cusX
                val ax = cx * CU
                val ay = cy * CU

                val scattered = (index.toLong() * geometry.refreshStride % geometry.cus).toInt()
                val forced = refreshFrom >= 0 &&
                    (scattered - refreshFrom + geometry.cus) % geometry.cus < options.refreshPerFrame
                val phase = frameIndex and 1
                val still = sadEstimate(source, reconstruction, width, ax, ay, ax, ay, Int.MAX_VALUE, CU, phase)
                if (forced && !(options.refreshGate && still <= options.skipThreshold)) {
                    modes[index] = MODE_INTRA
                    forcedArr[index] = true
                    return@forEach
                }
                if (still <= options.skipThreshold) {
                    stillErr[index] = still
                    modes[index] = MODE_SKIP
                    return@forEach
                }

                var best = still
                var bestX = 0
                var bestY = 0
                var step = 4
                while (step >= 1) {
                    var movedX = bestX
                    var movedY = bestY
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = bestX + dx * step
                            val ny = bestY + dy * step
                            if (Math.abs(nx) > minOf(options.searchRange, MV_RANGE)) continue
                            if (Math.abs(ny) > minOf(options.searchRange, MV_RANGE)) continue
                            if (ax + nx < 0 || ay + ny < 0) continue
                            if (ax + nx + CU > width || ay + ny + CU > height) continue
                            val error = sadEstimate(source, reconstruction, width, ax, ay, ax + nx, ay + ny, best, CU, phase)
                            if (error < best) {
                                best = error
                                movedX = nx
                                movedY = ny
                            }
                        }
                    }
                    bestX = movedX
                    bestY = movedY
                    step /= 2
                }

                val stillExact = sad(source, reconstruction, width, ax, ay, ax, ay, Int.MAX_VALUE)
                stillErr[index] = stillExact
                if (stillExact <= options.skipThreshold) {
                    modes[index] = MODE_SKIP
                    return@forEach
                }
                var mv2x = bestX * 2
                var mv2y = bestY * 2
                var bestExact = if (mv2x != 0 || mv2y != 0) {
                    sad(source, reconstruction, width, ax, ay, ax + bestX, ay + bestY, Int.MAX_VALUE)
                } else {
                    stillExact
                }
                if (bestExact > options.mcThreshold shr 1 && bestExact < options.mcThreshold shl 2) {
                    for (h in 0 until 4) {
                        val cx2 = mv2x + HALF_DX[h]
                        val cy2 = mv2y + HALF_DY[h]
                        if (!tapFits(ax, cx2, CU, width) || !tapFits(ay, cy2, CU, height)) continue
                        val e = sadHalfPel(source, reconstruction, width, ax, ay, cx2, cy2, CU, bestExact)
                        if (e < bestExact) {
                            bestExact = e
                            mv2x = cx2
                            mv2y = cy2
                        }
                    }
                }
                if (bestExact <= options.mcThreshold && (mv2x != 0 || mv2y != 0) &&
                    bestExact < stillExact - (stillExact shr 4)
                ) {
                    modes[index] = MODE_MC
                    mvx[index] = mv2x
                    mvy[index] = mv2y
                    return@forEach
                }

                val subX = IntArray(4)
                val subY = IntArray(4)
                var splitError = 0
                for (q in 0 until 4) {
                    val qx = ax + q % 2 * SUB
                    val qy = ay + q / 2 * SUB
                    var qBest = sadEstimate(source, reconstruction, width, qx, qy, qx, qy, Int.MAX_VALUE, SUB, phase)
                    var qbx = 0
                    var qby = 0
                    var qStep = 4
                    while (qStep >= 1) {
                        var mx = qbx
                        var my = qby
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                val nx = qbx + dx * qStep
                                val ny = qby + dy * qStep
                                if (Math.abs(nx) > MV_RANGE || Math.abs(ny) > MV_RANGE) continue
                                if (qx + nx < 0 || qy + ny < 0) continue
                                if (qx + nx + SUB > width || qy + ny + SUB > height) continue
                                val e = sadEstimate(source, reconstruction, width, qx, qy, qx + nx, qy + ny, qBest, SUB, phase)
                                if (e < qBest) {
                                    qBest = e
                                    mx = nx
                                    my = ny
                                }
                            }
                        }
                        qbx = mx
                        qby = my
                        qStep /= 2
                    }
                    subX[q] = qbx
                    subY[q] = qby
                    splitError += qBest
                }

                var splitExact = 0
                for (q in 0 until 4) {
                    val qx = ax + q % 2 * SUB
                    val qy = ay + q / 2 * SUB
                    var m2x = subX[q] * 2
                    var m2y = subY[q] * 2
                    var qExact = sadHalfPel(source, reconstruction, width, qx, qy, m2x, m2y, SUB, Int.MAX_VALUE)
                    if (qExact > options.mcThreshold shr 3 && qExact < options.mcThreshold) {
                        for (h in 0 until 4) {
                            val cx2 = m2x + HALF_DX[h]
                            val cy2 = m2y + HALF_DY[h]
                            if (!tapFits(qx, cx2, SUB, width) || !tapFits(qy, cy2, SUB, height)) continue
                            val e = sadHalfPel(source, reconstruction, width, qx, qy, cx2, cy2, SUB, qExact)
                            if (e < qExact) {
                                qExact = e
                                m2x = cx2
                                m2y = cy2
                            }
                        }
                    }
                    subX[q] = m2x
                    subY[q] = m2y
                    splitExact += qExact
                }
                val splitMoves = subX.any { it != 0 } || subY.any { it != 0 }
                val splitAcceptable = splitExact <= options.mcThreshold && splitMoves &&
                    splitExact < stillExact - (stillExact shr 4) && splitExact < bestExact
                if (splitAcceptable) {
                    modes[index] = MODE_SPLIT
                    for (q in 0 until 4) {
                        splitMvx[index * 4 + q] = subX[q]
                        splitMvy[index * 4 + q] = subY[q]
                    }
                    return@forEach
                }

                val useSplit = splitExact < bestExact
                val predError = if (useSplit) splitExact else bestExact
                val fitX = if (useSplit) subX else intArrayOf(mv2x, mv2x, mv2x, mv2x)
                val fitY = if (useSplit) subY else intArrayOf(mv2y, mv2y, mv2y, mv2y)
                predErr[index] = predError
                for (q in 0 until 4) {
                    splitMvx[index * 4 + q] = fitX[q]
                    splitMvy[index * 4 + q] = fitY[q]
                }
                val fitError = residualFit(
                    source, reconstruction, width, ax, ay, fitX, fitY,
                    residuals, index * RESIDUAL_WORDS, geometry.lumaStep, geometry.chromaStep,
                )

                val bestForIntra = bestExact
                var residualMoves = splitMoves || mv2x != 0 || mv2y != 0
                if (!residualMoves) {
                    for (w in 0 until RESIDUAL_WORDS) {
                        if (residuals[index * RESIDUAL_WORDS + w] != RESIDUAL_BIAS) {
                            residualMoves = true
                            break
                        }
                    }
                }

                var intraErr = -1
                if (fitError < predError - (predError shr 4) && residualMoves) {
                    var accept = fitError <= options.mcThreshold
                    if (!accept) {
                        intraErr = intraError(source, width, ax, ay)
                        accept = fitError < intraErr * options.residualBias
                    }
                    if (accept) {
                        modes[index] = MODE_RESIDUAL
                        resultErr[index] = fitError
                        return@forEach
                    }
                }
                if (splitAcceptable) {
                    modes[index] = MODE_SPLIT
                    for (q in 0 until 4) {
                        splitMvx[index * 4 + q] = subX[q]
                        splitMvy[index * 4 + q] = subY[q]
                    }
                    return@forEach
                }
                if (intraErr < 0) intraErr = intraError(source, width, ax, ay)
                if (intraErr < bestForIntra * options.intraGain) {
                    modes[index] = MODE_INTRA
                    resultErr[index] = intraErr
                } else {
                    modes[index] = MODE_SKIP
                }
            }
        }

        ws.decisionNanos = System.nanoTime() - decisionStart
        val emitStart = System.nanoTime()
        if (options.payloadBudget > 0) {
            var budget = options.payloadBudget
            for (index in 0 until geometry.cus) {
                if (forcedArr[index]) budget -= POOL_ENTRY_WORDS
            }
            val candidates = ArrayList<Int>()
            for (index in 0 until geometry.cus) {
                if (forcedArr[index]) continue
                if (modes[index] == MODE_RESIDUAL || modes[index] == MODE_INTRA) candidates.add(index)
            }
            candidates.sortByDescending { index ->
                val cost = if (modes[index] == MODE_RESIDUAL) RESIDUAL_WORDS + SPLIT_WORDS else POOL_ENTRY_WORDS
                (stillErr[index] - resultErr[index]).toDouble() / cost
            }
            for (index in candidates) {
                val cost = if (modes[index] == MODE_RESIDUAL) RESIDUAL_WORDS + SPLIT_WORDS else POOL_ENTRY_WORDS
                if (budget >= cost) {
                    budget -= cost
                    continue
                }
                val moved = (0 until 4).any {
                    splitMvx[index * 4 + it] != 0 || splitMvy[index * 4 + it] != 0
                }
                modes[index] = if (moved && predErr[index] < stillErr[index] - (stillErr[index] shr 3)) {
                    MODE_SPLIT
                } else {
                    MODE_SKIP
                }
            }
        }

        var claimed = 0
        var skip = 0
        var mc = 0
        var split = 0
        var residual = 0
        var intra = 0
        val entries = ws.entries
        java.util.Arrays.fill(entries, -1)
        for (index in 0 until geometry.cus) {
            when (modes[index]) {
                MODE_SKIP -> skip++
                MODE_MC -> mc++
                MODE_SPLIT -> split++
                MODE_RESIDUAL -> residual++
                else -> {
                    if (claimed < geometry.poolEntries) {
                        entries[index] = claimed++
                        intra++
                    } else {
                        modes[index] = MODE_SKIP
                        skip++
                    }
                }
            }
        }

        for (index in 0 until geometry.cus) {
            val base = index * PLANE_WORDS
            val mode = modes[index]
            val entry = entries[index]
            write(channel, base, mode)
            write(channel, base + 1, if (mode == MODE_MC) mvx[index] + MV_BIAS else 0)
            write(channel, base + 2, if (mode == MODE_MC) mvy[index] + MV_BIAS else 0)
            write(channel, base + 3, if (entry >= 0) entry and 0x7F else 0)
            write(channel, base + 4, if (entry >= 0) (entry shr 7) and 0x7F else 0)

            if (mode == MODE_SPLIT || mode == MODE_RESIDUAL) {
                val at = geometry.splitIndex(index)
                for (q in 0 until 4) {
                    write(channel, at + q * 2, splitMvx[index * 4 + q] + MV_BIAS)
                    write(channel, at + q * 2 + 1, splitMvy[index * 4 + q] + MV_BIAS)
                }
            }
            if (mode == MODE_RESIDUAL) {
                val at = geometry.residualIndex(index)
                for (w in 0 until RESIDUAL_WORDS) {
                    write(channel, at + w, residuals[index * RESIDUAL_WORDS + w])
                }
            }
        }

        IntStream.range(0, geometry.cus).parallel().forEach { index ->
            if (modes[index] != MODE_INTRA) return@forEach
            val entry = entries[index]
            if (entry < 0) return@forEach
            val cx = index % geometry.cusX
            val cy = index / geometry.cusX
            codeIntra(channel, source, width, cx * CU, cy * CU, geometry.poolIndex(entry))
        }

        ws.emitNanos = System.nanoTime() - emitStart
        val applyStart = System.nanoTime()
        applyTo(reconstruction, width, geometry, modes, mvx, mvy, splitMvx, splitMvy, entries, channel, ws.previous)
        ws.applyNanos = System.nanoTime() - applyStart
        return Stats(skip, mc, split, residual, intra, claimed)
    }

    private fun quantise(value: Int, step: Int, zeroBin: Int): Int {
        val a = if (value < 0) -value else value
        if (a < zeroBin) return 0
        val q = ((a - zeroBin) / step + 1).coerceAtMost(63)
        return if (value < 0) -q else q
    }

    private fun clamp8(value: Int): Int = if (value < 0) 0 else if (value > 255) 255 else value

    private fun residualFit(
        source: IntArray,
        reference: IntArray,
        width: Int,
        ax: Int,
        ay: Int,
        subX: IntArray,
        subY: IntArray,
        out: IntArray,
        outBase: Int,
        lumaStep: Int,
        chromaStep: Int,
    ): Int {
        val stepC = 64 * lumaStep
        val zeroBinC = stepC / 2 + stepC / 4
        val zeroBinDc = (chromaStep * 3) / 4
        val srcR = IntArray(64)
        val srcG = IntArray(64)
        val srcB = IntArray(64)
        val refR = IntArray(64)
        val refG = IntArray(64)
        val refB = IntArray(64)
        val errY = IntArray(64)
        val recY = IntArray(64)
        val coef = IntArray(COEFFS)
        val pred = IntArray(64)
        var error = 0

        for (b in 0 until 4) {
            val bx = ax + b % 2 * RES_BLOCK
            val by = ay + b / 2 * RES_BLOCK
            val dx = subX[b]
            val dy = subY[b]
            predictBlock(reference, width, bx, by, RES_BLOCK, dx, dy, pred)
            var k = 0
            for (py in 0 until RES_BLOCK) {
                var si = (by + py) * width + bx
                for (px in 0 until RES_BLOCK) {
                    val s = source[si++]
                    val r = pred[k]
                    val sr = (s shr 16) and 0xFF
                    val sg = (s shr 8) and 0xFF
                    val sb = s and 0xFF
                    val rr = (r shr 16) and 0xFF
                    val rg = (r shr 8) and 0xFF
                    val rb = r and 0xFF
                    srcR[k] = sr
                    srcG[k] = sg
                    srcB[k] = sb
                    refR[k] = rr
                    refG[k] = rg
                    refB[k] = rb
                    errY[k] = ((sr - rr) + 2 * (sg - rg) + (sb - rb)) / 4
                    k++
                }
            }

            val base = outBase + b * RESIDUAL_BLOCK_WORDS
            for (v in 0 until KEPT) {
                for (u in 0 until KEPT) {
                    var c = 0
                    var i = 0
                    for (y in 0 until RES_BLOCK) {
                        val sv = SIGN[y][v]
                        for (x in 0 until RES_BLOCK) {
                            c += errY[i++] * SIGN[x][u] * sv
                        }
                    }
                    val q = quantise(c, stepC, zeroBinC)
                    coef[v * KEPT + u] = q * lumaStep
                    out[base + v * KEPT + u] = q + RESIDUAL_BIAS
                }
            }

            var i = 0
            for (y in 0 until RES_BLOCK) {
                for (x in 0 until RES_BLOCK) {
                    var d = 0
                    for (v in 0 until KEPT) {
                        val sv = SIGN[y][v]
                        val row = v * KEPT
                        for (u in 0 until KEPT) {
                            val cq = coef[row + u]
                            if (cq != 0) d += cq * SIGN[x][u] * sv
                        }
                    }
                    recY[i++] = d
                }
            }

            var sr = 0
            var sg = 0
            var sb = 0
            for (p in 0 until 64) {
                sr += srcR[p] - refR[p] - recY[p]
                sg += srcG[p] - refG[p] - recY[p]
                sb += srcB[p] - refB[p] - recY[p]
            }
            val qR = quantise(sr / 64, chromaStep, zeroBinDc)
            val qG = quantise(sg / 64, chromaStep, zeroBinDc)
            val qB = quantise(sb / 64, chromaStep, zeroBinDc)
            out[base + COEFFS] = qR + RESIDUAL_BIAS
            out[base + COEFFS + 1] = qG + RESIDUAL_BIAS
            out[base + COEFFS + 2] = qB + RESIDUAL_BIAS
            val aR = qR * chromaStep
            val aG = qG * chromaStep
            val aB = qB * chromaStep
            for (p in 0 until 64) {
                error += Math.abs(srcR[p] - clamp8(refR[p] + recY[p] + aR)) +
                    Math.abs(srcG[p] - clamp8(refG[p] + recY[p] + aG)) +
                    Math.abs(srcB[p] - clamp8(refB[p] + recY[p] + aB))
            }
        }
        return error
    }

    private fun intraError(source: IntArray, width: Int, ox: Int, oy: Int): Int {
        val block = IntArray(48)
        val rows = IntArray(4)
        val palette = IntArray(12)
        var total = 0L
        for (diag in 0 until CU / StreamBlocks.BLOCK) {
            var i = 0
            for (py in 0 until StreamBlocks.BLOCK) {
                var index = (oy + diag * StreamBlocks.BLOCK + py) * width + ox + diag * StreamBlocks.BLOCK
                for (px in 0 until StreamBlocks.BLOCK) {
                    val rgb = source[index++]
                    block[i++] = (rgb shr 16) and 0xFF
                    block[i++] = (rgb shr 8) and 0xFF
                    block[i++] = rgb and 0xFF
                }
            }
            rangeFit(block, rows, palette)
            for (p in 0 until 16) {
                val choice = (rows[p / 4] ushr ((p % 4) * 2)) and 3
                val at = p * 3
                val c = choice * 3
                total += Math.abs(block[at] - palette[c]) +
                    Math.abs(block[at + 1] - palette[c + 1]) +
                    Math.abs(block[at + 2] - palette[c + 2])
            }
        }
        return (total * (CU / StreamBlocks.BLOCK)).toInt()
    }

    private fun codeIntra(channel: StreamChannel, source: IntArray, width: Int, ox: Int, oy: Int, address: Int) {
        val block = IntArray(48)
        val rows = IntArray(4)
        val palette = IntArray(12)
        val words = IntArray(StreamBlocks.WORDS_PER_BLOCK)
        var at = address
        for (by in 0 until CU / StreamBlocks.BLOCK) {
            for (bx in 0 until CU / StreamBlocks.BLOCK) {
                var i = 0
                for (py in 0 until StreamBlocks.BLOCK) {
                    var index = (oy + by * StreamBlocks.BLOCK + py) * width + ox + bx * StreamBlocks.BLOCK
                    for (px in 0 until StreamBlocks.BLOCK) {
                        val rgb = source[index++]
                        block[i++] = (rgb shr 16) and 0xFF
                        block[i++] = (rgb shr 8) and 0xFF
                        block[i++] = rgb and 0xFF
                    }
                }
                val packed = rangeFit(block, rows, palette, 2)
                val e0 = ((packed shr 16) and 0xFFFF).toInt()
                val e1 = (packed and 0xFFFF).toInt()
                StreamBlocks.split32((e0 and 0xFFFF) or ((e1 and 0xFFFF) shl 16), words, 0)
                StreamBlocks.split32(
                    (rows[0] and 0xFF) or ((rows[1] and 0xFF) shl 8) or
                        ((rows[2] and 0xFF) shl 16) or ((rows[3] and 0xFF) shl 24),
                    words,
                    5,
                )
                for (w in words.indices) write(channel, at + w, words[w])
                at += StreamBlocks.WORDS_PER_BLOCK
            }
        }
    }

    private fun applyTo(
        reconstruction: IntArray,
        width: Int,
        geometry: Geometry,
        modes: IntArray,
        mvx: IntArray,
        mvy: IntArray,
        splitMvx: IntArray,
        splitMvy: IntArray,
        entries: IntArray,
        channel: StreamChannel,
        previous: IntArray,
    ) {
        System.arraycopy(reconstruction, 0, previous, 0, reconstruction.size)
        IntStream.range(0, geometry.cus).parallel().forEach { index ->
            reconstructUnit(
                channel, previous, reconstruction, width, geometry, index,
                modes[index], mvx[index], mvy[index], splitMvx, splitMvy, entries[index],
            )
        }
    }

    fun reconstructUnit(
        channel: StreamChannel,
        previous: IntArray,
        out: IntArray,
        width: Int,
        geometry: Geometry,
        index: Int,
        mode: Int,
        dx: Int,
        dy: Int,
        splitMvx: IntArray,
        splitMvy: IntArray,
        entry: Int,
    ) {
        val ox = index % geometry.cusX * CU
        val oy = index / geometry.cusX * CU
        when (mode) {
            MODE_SKIP -> for (y in 0 until CU) {
                val row = (oy + y) * width + ox
                System.arraycopy(previous, row, out, row, CU)
            }
            MODE_MC -> predictIntoFrame(previous, out, width, ox, oy, CU, dx, dy)
            MODE_SPLIT, MODE_RESIDUAL -> {
                for (q in 0 until 4) {
                    val qx = ox + q % 2 * SUB
                    val qy = oy + q / 2 * SUB
                    predictIntoFrame(previous, out, width, qx, qy, SUB, splitMvx[index * 4 + q], splitMvy[index * 4 + q])
                }
                if (mode == MODE_RESIDUAL) applyResidual(channel, out, width, geometry, index, ox, oy)
            }
            else -> decodeIntra(channel, out, width, ox, oy, geometry.poolIndex(entry))
        }
    }

    private fun applyResidual(
        channel: StreamChannel,
        out: IntArray,
        width: Int,
        geometry: Geometry,
        index: Int,
        ox: Int,
        oy: Int,
    ) {
        val at = geometry.residualIndex(index)
        val coef = IntArray(COEFFS)
        for (b in 0 until 4) {
            val base = at + b * RESIDUAL_BLOCK_WORDS
            var live = false
            for (k in 0 until COEFFS) {
                val c = (read(channel, base + k) - RESIDUAL_BIAS) * geometry.lumaStep
                coef[k] = c
                if (c != 0) live = true
            }
            val dcR = (read(channel, base + COEFFS) - RESIDUAL_BIAS) * geometry.chromaStep
            val dcG = (read(channel, base + COEFFS + 1) - RESIDUAL_BIAS) * geometry.chromaStep
            val dcB = (read(channel, base + COEFFS + 2) - RESIDUAL_BIAS) * geometry.chromaStep
            if (!live && dcR == 0 && dcG == 0 && dcB == 0) continue
            val bx = ox + b % 2 * RES_BLOCK
            val by = oy + b / 2 * RES_BLOCK
            for (py in 0 until RES_BLOCK) {
                var i = (by + py) * width + bx
                for (px in 0 until RES_BLOCK) {
                    var d = 0
                    for (v in 0 until KEPT) {
                        val sv = SIGN[py][v]
                        val row = v * KEPT
                        for (u in 0 until KEPT) {
                            val c = coef[row + u]
                            if (c != 0) d += c * SIGN[px][u] * sv
                        }
                    }
                    val p = out[i]
                    out[i] = (clamp8(((p shr 16) and 0xFF) + d + dcR) shl 16) or
                        (clamp8(((p shr 8) and 0xFF) + d + dcG) shl 8) or
                        clamp8((p and 0xFF) + d + dcB)
                    i++
                }
            }
        }
    }

    fun decodeIntra(channel: StreamChannel, out: IntArray, width: Int, ox: Int, oy: Int, address: Int) {
        val palette = IntArray(12)
        var at = address
        for (by in 0 until CU / StreamBlocks.BLOCK) {
            for (bx in 0 until CU / StreamBlocks.BLOCK) {
                val lo = StreamBlocks.join32(
                    read(channel, at), read(channel, at + 1), read(channel, at + 2),
                    read(channel, at + 3), read(channel, at + 4),
                )
                val hi = StreamBlocks.join32(
                    read(channel, at + 5), read(channel, at + 6), read(channel, at + 7),
                    read(channel, at + 8), read(channel, at + 9),
                )
                MosaicBc1.fillPalette(lo and 0xFFFF, (lo ushr 16) and 0xFFFF, palette)
                for (py in 0 until StreamBlocks.BLOCK) {
                    val selectors = (hi ushr (py * 8)) and 0xFF
                    var index = (oy + by * StreamBlocks.BLOCK + py) * width + ox + bx * StreamBlocks.BLOCK
                    for (px in 0 until StreamBlocks.BLOCK) {
                        val choice = (selectors ushr (px * 2)) and 3
                        val c = choice * 3
                        out[index++] = (palette[c] shl 16) or (palette[c + 1] shl 8) or palette[c + 2]
                    }
                }
                at += StreamBlocks.WORDS_PER_BLOCK
            }
        }
    }

    fun glsl(geometry: Geometry): String = buildString {
        append("#define SHADR_CU ").append(CU).append('\n')
        append("#define SHADR_CU_PLANE_WORDS ").append(PLANE_WORDS).append('\n')
        append("#define SHADR_CU_POOL_ENTRY_WORDS ").append(POOL_ENTRY_WORDS).append('\n')
        append("#define SHADR_CU_MODE_SKIP ").append(MODE_SKIP).append('\n')
        append("#define SHADR_CU_MODE_MC ").append(MODE_MC).append('\n')
        append("#define SHADR_CU_MODE_SPLIT ").append(MODE_SPLIT).append('\n')
        append("#define SHADR_CU_MODE_RESIDUAL ").append(MODE_RESIDUAL).append('\n')
        append("#define SHADR_CU_MODE_INTRA ").append(MODE_INTRA).append('\n')
        append("#define SHADR_CU_SUB ").append(SUB).append('\n')
        append("#define SHADR_CU_SPLIT_WORDS ").append(SPLIT_WORDS).append('\n')
        append("#define SHADR_CU_RES_BLOCK ").append(RES_BLOCK).append('\n')
        append("#define SHADR_CU_KEPT ").append(KEPT).append('\n')
        append("#define SHADR_CU_RESIDUAL_WORDS ").append(RESIDUAL_WORDS).append('\n')
        append("#define SHADR_CU_RESIDUAL_BIAS ").append(RESIDUAL_BIAS).append('\n')
        append("#define SHADR_CU_LUMA_STEP ").append(geometry.lumaStep).append('\n')
        append("#define SHADR_CU_CHROMA_STEP ").append(geometry.chromaStep).append('\n')
        append("#define SHADR_CU_MV_BIAS ").append(MV_BIAS).append('\n')
        append("#define SHADR_CU_SLOT_PAYLOAD ").append(SLOT_PAYLOAD).append('\n')
        append("#define SHADR_CU_COLUMNS ").append(geometry.cusX).append('\n')
        append("#define SHADR_CU_ROWS ").append(geometry.cusY).append('\n')
        append("#define SHADR_CU_SPLIT_BASE ").append(geometry.splitBase).append('\n')
        append("#define SHADR_CU_RESIDUAL_BASE ").append(geometry.residualBase).append('\n')
        append("#define SHADR_CU_POOL_BASE ").append(geometry.poolBase).append('\n')
        append("#define SHADR_FRAME_WIDTH ").append(geometry.frameWidth).append('\n')
        append("#define SHADR_FRAME_HEIGHT ").append(geometry.frameHeight).append('\n')
        append("const int SHADR_CU_SIGN[32] = int[32](")
        for (x in 0 until RES_BLOCK) {
            for (k in 0 until KEPT) {
                if (x * KEPT + k > 0) append(',')
                append(SIGN[x][k])
            }
        }
        append(");\n")
        append(
            """
            int shadr_cu_arena(sampler2D source, ivec2 regionOrigin, int columns, int address) {
                int slot = address / SHADR_CU_SLOT_PAYLOAD;
                int off = address - slot * SHADR_CU_SLOT_PAYLOAD;
                int r = 1 + off / SHADR_MAP_EDGE;
                int c = off - (r - 1) * SHADR_MAP_EDGE;
                ivec2 slotOrigin = shadr_stream_slot_origin(regionOrigin, slot, columns);
                ivec2 px = ivec2(slotOrigin.x + c, slotOrigin.y + SHADR_MAP_EDGE - 1 - r);
                return shadr_map_word(texelFetch(source, px, 0));
            }

            int shadr_cu_join32(int w0, int w1, int w2, int w3, int w4) {
                return w0 | (w1 << 7) | (w2 << 14) | (w3 << 21) | ((w4 & 15) << 28);
            }

            ivec3 shadr_cu_endpoint(int p) {
                int r = (p >> 11) & 31;
                int g = (p >> 5) & 63;
                int b = p & 31;
                return ivec3((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2));
            }
            """.trimIndent(),
        )
        append('\n')
    }
}
