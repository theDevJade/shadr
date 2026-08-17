/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

import kotlin.math.roundToInt


// This required too much research, and reference code
// I still barely understand how this works.
object MosaicEncoder {

    data class Options(
        /**
         * Error budget
         */
        val quality: Int = 24,
        val gopFrames: Int = Int.MAX_VALUE,
        val motionSearch: Int = 12,
        val ceilingFactor: Double = 6.0,
        val lambdaDivisor: Double = 4.0,
    )

    class Stats {
        var skip = 0
        var motion = 0
        var delta = 0
        var intra = 0
        var superSkip = 0
        var superMotion = 0
        var superCoded = 0

        val quality = mutableListOf<MosaicQuality.Frame>()

        val report: MosaicQuality.Report?
            get() = if (quality.isEmpty()) {
                null
            } else {
                MosaicQuality.Report(
                    psnr = quality.sumOf { it.psnr } / quality.size,
                    ssim = quality.sumOf { it.ssim } / quality.size,
                    frames = quality.toList(),
                )
            }

        val blocks: Int get() = skip + motion + delta + intra

        override fun toString(): String =
            "blocks=$blocks skip=$skip motion=$motion delta=$delta intra=$intra " +
                "superblocks=${superSkip + superMotion + superCoded} " +
                "(skipped $superSkip, moved $superMotion)"
    }

    fun encode(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        fps: Double,
        options: Options = Options(),
        stats: Stats? = null,
    ): MosaicClip? = encode(frames.size, width, height, fps, options, stats) { frames.getOrNull(it) }

    fun encode(
        frameCount: Int,
        width: Int,
        height: Int,
        fps: Double,
        options: Options = Options(),
        stats: Stats? = null,
        next: (Int) -> IntArray?,
    ): MosaicClip? {
        if (frameCount < 1 || width < 1 || height < 1) return null

        val columns = MosaicFormat.superColumns(width)
        val rows = MosaicFormat.superRows(height)

        val perFrame = columns.toLong() * rows
        val plane = frameCount.toLong() * perFrame
        if (plane > MosaicFormat.MAX_TEXELS) return null

        val planeTexels = plane.toInt()
        val superblocks = perFrame.toInt()

        val sheet = Sheet(planeTexels)
        val tolerance = options.quality.toDouble()

        var previous = IntArray(width * height)
        var encoded = 0
        for (index in 0 until frameCount) {
            val source = next(index) ?: break
            if (source.size != width * height) return null
            encoded++
            val current = previous.copyOf()
            val keyframe = index % options.gopFrames == 0

            for (sy in 0 until rows) {
                for (sx in 0 until columns) {
                    val plane = MosaicFormat.superblockIndex(index, sx, sy, columns, superblocks)
                    val coded = encodeSuperblock(
                        sheet, source, previous, current,
                        width, height, sx, sy, keyframe, tolerance, options, stats,
                    ) ?: return null

                    sheet.data[plane] = coded
                }
            }
            if (stats != null) {
                stats.quality += MosaicQuality.Frame(
                    index = index,
                    psnr = MosaicQuality.psnr(source, current),
                    ssim = MosaicQuality.ssim(source, current, width, height),
                )
            }

            previous = current
        }
        if (encoded == 0) return null

        return MosaicClip(width, height, encoded, fps, sheet.data, sheet.top)
    }

    private fun encodeSuperblock(
        sheet: Sheet,
        source: IntArray,
        previous: IntArray,
        current: IntArray,
        width: Int,
        height: Int,
        sx: Int,
        sy: Int,
        keyframe: Boolean,
        tolerance: Double,
        options: Options,
        stats: Stats?,
    ): Int? {
        val originX = sx * MosaicFormat.SUPER
        val originY = sy * MosaicFormat.SUPER

        if (!keyframe &&
            meanSquaredError(
                source, previous, width, height, originX, originY, 0, 0, MosaicFormat.SUPER,
            ) <= tolerance
        ) {
            stats?.let { it.superSkip++; it.skip += MosaicFormat.BLOCKS_PER_SUPER }
            return MosaicFormat.texel(MosaicFormat.SB_SKIP, 0, 0, 0)
        }

        // One vector for the whole square, before spending sixteen commands saying the same
        // thing. Held to the plain tolerance rather than the rate-distortion ceiling: this
        // covers a thousand pixels, so a loose fit here is a large visible patch.
        if (!keyframe) {
            val whole = searchMotion(
                source, previous, width, height, originX, originY, options, MosaicFormat.SUPER,
            )
            if (whole != null && meanSquaredError(
                    source, previous, width, height, originX, originY,
                    whole.dx, whole.dy, MosaicFormat.SUPER,
                ) <= tolerance
            ) {
                forEachPixel(width, height, originX, originY, MosaicFormat.SUPER) { x, y ->
                    current[y * width + x] =
                        sample(previous, width, height, x + whole.dx, y + whole.dy)
                }
                stats?.let { it.superMotion++; it.motion += MosaicFormat.BLOCKS_PER_SUPER }
                return MosaicFormat.texel(
                    MosaicFormat.SB_MOTION,
                    whole.dx + MosaicFormat.MV_BIAS,
                    whole.dy + MosaicFormat.MV_BIAS,
                    0,
                )
            }
        }

        val plans = arrayOfNulls<Plan>(MosaicFormat.BLOCKS_PER_SUPER)
        var anyCoded = false

        for (block in 0 until MosaicFormat.BLOCKS_PER_SUPER) {
            val bx = originX + (block % MosaicFormat.BLOCKS_PER_SUPER_EDGE) * MosaicFormat.BLOCK
            val by = originY + (block / MosaicFormat.BLOCKS_PER_SUPER_EDGE) * MosaicFormat.BLOCK
            if (bx >= width || by >= height) {
                plans[block] = Plan(MosaicFormat.BLK_SKIP)
                continue
            }
            val plan = planBlock(
                source, previous, width, height, bx, by, keyframe, tolerance, options,
            )
            plans[block] = plan
            if (plan.mode != MosaicFormat.BLK_SKIP) anyCoded = true
        }

        if (!anyCoded) {
            stats?.let { it.superSkip++; it.skip += MosaicFormat.BLOCKS_PER_SUPER }
            return MosaicFormat.texel(MosaicFormat.SB_SKIP, 0, 0, 0)
        }

        val commands = sheet.allocate(MosaicFormat.BLOCKS_PER_SUPER) ?: return null
        stats?.superCoded++

        for (block in 0 until MosaicFormat.BLOCKS_PER_SUPER) {
            val plan = plans[block]!!
            val bx = originX + (block % MosaicFormat.BLOCKS_PER_SUPER_EDGE) * MosaicFormat.BLOCK
            val by = originY + (block / MosaicFormat.BLOCKS_PER_SUPER_EDGE) * MosaicFormat.BLOCK

            val command = writeBlock(
                sheet, plan, source, previous, current, width, height, bx, by, stats,
            ) ?: return null
            sheet.data[commands + block] = command
        }
        return MosaicFormat.withPointer(MosaicFormat.SB_CODED, commands)
    }

    private class Plan(val mode: Int, val dx: Int = 0, val dy: Int = 0)

    private fun planBlock(
        source: IntArray,
        previous: IntArray,
        width: Int,
        height: Int,
        bx: Int,
        by: Int,
        keyframe: Boolean,
        tolerance: Double,
        options: Options,
    ): Plan {
        if (keyframe) return Plan(MosaicFormat.BLK_INTRA)

        val samples = countPixels(width, height, bx, by) * 3
        if (samples == 0) return Plan(MosaicFormat.BLK_SKIP)

        val ceiling = tolerance * options.ceilingFactor
        val lambda = tolerance * samples / options.lambdaDivisor

        var best: Plan? = null
        var bestCost = Double.MAX_VALUE

        fun consider(plan: Plan, mse: Double, texels: Int) {
            if (mse > ceiling) return
            val cost = mse * samples + texels * lambda
            if (cost < bestCost) {
                bestCost = cost
                best = plan
            }
        }

        consider(
            Plan(MosaicFormat.BLK_SKIP),
            meanSquaredError(source, previous, width, height, bx, by, 0, 0),
            0,
        )

        val motion = searchMotion(source, previous, width, height, bx, by, options)
        if (motion != null) {
            consider(
                motion,
                meanSquaredError(source, previous, width, height, bx, by, motion.dx, motion.dy),
                0,
            )
        }

        consider(
            Plan(MosaicFormat.BLK_DELTA),
            deltaError(source, previous, width, height, bx, by) / samples,
            MosaicFormat.DELTA_TEXELS,
        )

        consider(
            Plan(MosaicFormat.BLK_INTRA),
            intraError(source, width, height, bx, by) / samples,
            MosaicFormat.INTRA_TEXELS,
        )

        return best ?: Plan(MosaicFormat.BLK_INTRA)
    }

    private fun intraError(
        source: IntArray,
        width: Int,
        height: Int,
        bx: Int,
        by: Int,
    ): Double {
        var total = 0.0
        for (q in 0 until 4) {
            val qx = bx + (q % 2) * 4
            val qy = by + (q / 2) * 4
            val palette = fitQuarter(source, width, height, qx, qy).palette

            forEachPixel(width, height, qx, qy, 4) { x, y ->
                val c = source[y * width + x]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                var bestError = Int.MAX_VALUE
                for (candidate in 0 until 4) {
                    val p = palette[candidate]
                    val dr = r - p[0]
                    val dg = g - p[1]
                    val db = b - p[2]
                    val error = dr * dr + dg * dg + db * db
                    if (error < bestError) bestError = error
                }
                total += bestError.toDouble()
            }
        }
        return total
    }

    private fun searchMotion(
        source: IntArray,
        previous: IntArray,
        width: Int,
        height: Int,
        bx: Int,
        by: Int,
        options: Options,
        size: Int = MosaicFormat.BLOCK,
    ): Plan? {
        var bestX = 0
        var bestY = 0
        var best = meanSquaredError(source, previous, width, height, bx, by, 0, 0, size)

        var step = options.motionSearch.coerceAtMost(MosaicFormat.MV_MAX)
        while (step >= 1) {
            var movedX = bestX
            var movedY = bestY
            for (oy in -1..1) {
                for (ox in -1..1) {
                    if (ox == 0 && oy == 0) continue
                    val dx = bestX + ox * step
                    val dy = bestY + oy * step
                    if (dx !in -MosaicFormat.MV_MAX..MosaicFormat.MV_MAX) continue
                    if (dy !in -MosaicFormat.MV_MAX..MosaicFormat.MV_MAX) continue
                    val error =
                        meanSquaredError(source, previous, width, height, bx, by, dx, dy, size)
                    if (error < best) {
                        best = error
                        movedX = dx
                        movedY = dy
                    }
                }
            }
            bestX = movedX
            bestY = movedY
            step /= 2
        }

        if (bestX == 0 && bestY == 0) return null
        return Plan(MosaicFormat.BLK_MOTION, bestX, bestY)
    }

    private fun writeBlock(
        sheet: Sheet,
        plan: Plan,
        source: IntArray,
        previous: IntArray,
        current: IntArray,
        width: Int,
        height: Int,
        bx: Int,
        by: Int,
        stats: Stats?,
    ): Int? = when (plan.mode) {
        MosaicFormat.BLK_SKIP -> {
            stats?.skip++
            MosaicFormat.texel(MosaicFormat.BLK_SKIP, 0, 0, 0)
        }

        MosaicFormat.BLK_MOTION -> {
            stats?.motion++
            forEachPixel(width, height, bx, by) { x, y ->
                current[y * width + x] = sample(previous, width, height, x + plan.dx, y + plan.dy)
            }
            MosaicFormat.texel(
                MosaicFormat.BLK_MOTION,
                plan.dx + MosaicFormat.MV_BIAS,
                plan.dy + MosaicFormat.MV_BIAS,
                0,
            )
        }

        MosaicFormat.BLK_DELTA -> {
            stats?.delta++
            val at = sheet.allocate(MosaicFormat.DELTA_TEXELS)
            if (at == null) {
                null
            } else {
                writeDelta(sheet, at, source, previous, current, width, height, bx, by)
                MosaicFormat.withPointer(MosaicFormat.BLK_DELTA, at)
            }
        }

        else -> {
            stats?.intra++
            val at = sheet.allocate(MosaicFormat.INTRA_TEXELS)
            if (at == null) {
                null
            } else {
                writeIntra(sheet, at, source, current, width, height, bx, by)
                MosaicFormat.withPointer(MosaicFormat.BLK_INTRA, at)
            }
        }
    }

    private fun writeDelta(
        sheet: Sheet,
        at: Int,
        source: IntArray,
        previous: IntArray,
        current: IntArray,
        width: Int,
        height: Int,
        bx: Int,
        by: Int,
    ) {
        for (q in 0 until 4) {
            val qx = bx + (q % 2) * 4
            val qy = by + (q / 2) * 4

            var sumR = 0
            var sumG = 0
            var sumB = 0
            var count = 0
            forEachPixel(width, height, qx, qy, 4) { x, y ->
                val want = source[y * width + x]
                val have = previous[y * width + x]
                sumR += ((want ushr 16) and 0xFF) - ((have ushr 16) and 0xFF)
                sumG += ((want ushr 8) and 0xFF) - ((have ushr 8) and 0xFF)
                sumB += (want and 0xFF) - (have and 0xFF)
                count++
            }
            if (count == 0) {
                sheet.data[at + q] = MosaicFormat.texel(
                    MosaicFormat.MV_BIAS, MosaicFormat.MV_BIAS, MosaicFormat.MV_BIAS, 0,
                )
                continue
            }

            val offR = (sumR / count).coerceIn(-MosaicFormat.MV_BIAS, MosaicFormat.MV_MAX)
            val offG = (sumG / count).coerceIn(-MosaicFormat.MV_BIAS, MosaicFormat.MV_MAX)
            val offB = (sumB / count).coerceIn(-MosaicFormat.MV_BIAS, MosaicFormat.MV_MAX)

            sheet.data[at + q] = MosaicFormat.texel(
                offR + MosaicFormat.MV_BIAS,
                offG + MosaicFormat.MV_BIAS,
                offB + MosaicFormat.MV_BIAS,
                0,
            )

            forEachPixel(width, height, qx, qy, 4) { x, y ->
                val have = previous[y * width + x]
                current[y * width + x] = rgb(
                    ((have ushr 16) and 0xFF) + offR,
                    ((have ushr 8) and 0xFF) + offG,
                    (have and 0xFF) + offB,
                )
            }
        }
    }

    private fun writeIntra(
        sheet: Sheet,
        at: Int,
        source: IntArray,
        current: IntArray,
        width: Int,
        height: Int,
        bx: Int,
        by: Int,
    ) {
        for (q in 0 until 4) {
            val qx = bx + (q % 2) * 4
            val qy = by + (q / 2) * 4

            val fit = fitQuarter(source, width, height, qx, qy)
            val e0 = fit.e0
            val e1 = fit.e1
            val palette = fit.palette

            val rows = IntArray(4)
            for (dy in 0 until 4) {
                var bits = 0
                for (dx in 0 until 4) {
                    val c = sample(source, width, height, qx + dx, qy + dy)
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF

                    var pick = 0
                    var bestError = Int.MAX_VALUE
                    for (candidate in 0 until 4) {
                        val p = palette[candidate]
                        val dr = r - p[0]
                        val dg = g - p[1]
                        val db = b - p[2]
                        val error = dr * dr + dg * dg + db * db
                        if (error < bestError) {
                            bestError = error
                            pick = candidate
                        }
                    }
                    bits = bits or (pick shl (dx * 2))

                    val x = qx + dx
                    val y = qy + dy
                    if (x < width && y < height) {
                        val p = palette[pick]
                        current[y * width + x] = rgb(p[0], p[1], p[2])
                    }
                }
                rows[dy] = bits
            }

            sheet.data[at + q * 2] = MosaicFormat.texel(
                e0 and 0xFF, (e0 ushr 8) and 0xFF, e1 and 0xFF, (e1 ushr 8) and 0xFF,
            )
            sheet.data[at + q * 2 + 1] = MosaicFormat.texel(rows[0], rows[1], rows[2], rows[3])
        }
    }

    private class Fit(val e0: Int, val e1: Int, val palette: Array<IntArray>)

    private val INDEX_WEIGHT = doubleArrayOf(0.0, 1.0, 1.0 / 3.0, 2.0 / 3.0)

    private const val REFINE_PASSES = 2

    private fun fitQuarter(
        source: IntArray,
        width: Int,
        height: Int,
        qx: Int,
        qy: Int,
    ): Fit {
        val pixels = IntArray(16 * 3)
        var at = 0
        var minR = 255; var minG = 255; var minB = 255
        var maxR = 0; var maxG = 0; var maxB = 0
        for (dy in 0 until 4) {
            for (dx in 0 until 4) {
                val c = sample(source, width, height, qx + dx, qy + dy)
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                pixels[at++] = r
                pixels[at++] = g
                pixels[at++] = b
                if (r < minR) minR = r
                if (g < minG) minG = g
                if (b < minB) minB = b
                if (r > maxR) maxR = r
                if (g > maxG) maxG = g
                if (b > maxB) maxB = b
            }
        }

        var e0 = MosaicReferenceDecoder.pack565(maxR, maxG, maxB)
        var e1 = MosaicReferenceDecoder.pack565(minR, minG, minB)
        var palette = paletteOf(e0, e1)
        var error = quarterError(pixels, palette)

        repeat(REFINE_PASSES) {
            val refined = refine(pixels, palette) ?: return@repeat
            val candidate = paletteOf(refined[0], refined[1])
            val candidateError = quarterError(pixels, candidate)
            if (candidateError >= error) return@repeat
            e0 = refined[0]
            e1 = refined[1]
            palette = candidate
            error = candidateError
        }

        return Fit(e0, e1, palette)
    }

    private fun refine(pixels: IntArray, palette: Array<IntArray>): IntArray? {
        var sumAA = 0.0
        var sumAB = 0.0
        var sumBB = 0.0
        val sumA = DoubleArray(3)
        val sumB = DoubleArray(3)

        for (i in 0 until 16) {
            val at = i * 3
            var pick = 0
            var best = Int.MAX_VALUE
            for (candidate in 0 until 4) {
                val p = palette[candidate]
                val dr = pixels[at] - p[0]
                val dg = pixels[at + 1] - p[1]
                val db = pixels[at + 2] - p[2]
                val d = dr * dr + dg * dg + db * db
                if (d < best) {
                    best = d
                    pick = candidate
                }
            }
            val w = INDEX_WEIGHT[pick]
            val a = 1.0 - w
            sumAA += a * a
            sumAB += a * w
            sumBB += w * w
            for (channel in 0 until 3) {
                sumA[channel] += a * pixels[at + channel]
                sumB[channel] += w * pixels[at + channel]
            }
        }

        val determinant = sumAA * sumBB - sumAB * sumAB
        if (kotlin.math.abs(determinant) < 1e-6) return null

        val c0 = IntArray(3)
        val c1 = IntArray(3)
        for (channel in 0 until 3) {
            c0[channel] = ((sumBB * sumA[channel] - sumAB * sumB[channel]) / determinant)
                .roundToInt().coerceIn(0, 255)
            c1[channel] = ((sumAA * sumB[channel] - sumAB * sumA[channel]) / determinant)
                .roundToInt().coerceIn(0, 255)
        }

        return intArrayOf(
            MosaicReferenceDecoder.pack565(c0[0], c0[1], c0[2]),
            MosaicReferenceDecoder.pack565(c1[0], c1[1], c1[2]),
        )
    }

    private fun paletteOf(e0: Int, e1: Int): Array<IntArray> {
        val c0 = MosaicReferenceDecoder.expand565(e0)
        val c1 = MosaicReferenceDecoder.expand565(e1)
        return arrayOf(
            c0,
            c1,
            intArrayOf(
                (2 * c0[0] + c1[0]) / 3, (2 * c0[1] + c1[1]) / 3, (2 * c0[2] + c1[2]) / 3,
            ),
            intArrayOf(
                (c0[0] + 2 * c1[0]) / 3, (c0[1] + 2 * c1[1]) / 3, (c0[2] + 2 * c1[2]) / 3,
            ),
        )
    }

    private fun quarterError(pixels: IntArray, palette: Array<IntArray>): Int {
        var total = 0
        for (i in 0 until 16) {
            val at = i * 3
            var best = Int.MAX_VALUE
            for (candidate in 0 until 4) {
                val p = palette[candidate]
                val dr = pixels[at] - p[0]
                val dg = pixels[at + 1] - p[1]
                val db = pixels[at + 2] - p[2]
                val d = dr * dr + dg * dg + db * db
                if (d < best) best = d
            }
            total += best
        }
        return total
    }

    private fun meanSquaredError(
        source: IntArray,
        previous: IntArray,
        width: Int,
        height: Int,
        bx: Int,
        by: Int,
        dx: Int,
        dy: Int,
        size: Int = MosaicFormat.BLOCK,
    ): Double {
        var total = 0L
        var count = 0
        forEachPixel(width, height, bx, by, size) { x, y ->
            val want = source[y * width + x]
            val have = sample(previous, width, height, x + dx, y + dy)
            val dr = ((want ushr 16) and 0xFF) - ((have ushr 16) and 0xFF)
            val dg = ((want ushr 8) and 0xFF) - ((have ushr 8) and 0xFF)
            val db = (want and 0xFF) - (have and 0xFF)
            total += (dr * dr + dg * dg + db * db).toLong()
            count++
        }
        return if (count == 0) 0.0 else total.toDouble() / (count * 3)
    }

    private fun deltaError(
        source: IntArray,
        previous: IntArray,
        width: Int,
        height: Int,
        bx: Int,
        by: Int,
    ): Double {
        var total = 0.0
        for (q in 0 until 4) {
            val qx = bx + (q % 2) * 4
            val qy = by + (q / 2) * 4

            var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
            forEachPixel(width, height, qx, qy, 4) { x, y ->
                val want = source[y * width + x]
                val have = previous[y * width + x]
                sumR += ((want ushr 16) and 0xFF) - ((have ushr 16) and 0xFF)
                sumG += ((want ushr 8) and 0xFF) - ((have ushr 8) and 0xFF)
                sumB += (want and 0xFF) - (have and 0xFF)
                count++
            }
            if (count == 0) continue

            val offR = (sumR / count).coerceIn(-MosaicFormat.MV_BIAS, MosaicFormat.MV_MAX)
            val offG = (sumG / count).coerceIn(-MosaicFormat.MV_BIAS, MosaicFormat.MV_MAX)
            val offB = (sumB / count).coerceIn(-MosaicFormat.MV_BIAS, MosaicFormat.MV_MAX)

            forEachPixel(width, height, qx, qy, 4) { x, y ->
                val want = source[y * width + x]
                val have = previous[y * width + x]
                val dr = ((want ushr 16) and 0xFF) - (((have ushr 16) and 0xFF) + offR).coerceIn(0, 255)
                val dg = ((want ushr 8) and 0xFF) - (((have ushr 8) and 0xFF) + offG).coerceIn(0, 255)
                val db = (want and 0xFF) - ((have and 0xFF) + offB).coerceIn(0, 255)
                total += (dr * dr + dg * dg + db * db).toDouble()
            }
        }
        return total
    }

    private fun countPixels(width: Int, height: Int, bx: Int, by: Int): Int {
        var count = 0
        forEachPixel(width, height, bx, by) { _, _ -> count++ }
        return count
    }

    private inline fun forEachPixel(
        width: Int,
        height: Int,
        bx: Int,
        by: Int,
        size: Int = MosaicFormat.BLOCK,
        body: (Int, Int) -> Unit,
    ) {
        for (dy in 0 until size) {
            val y = by + dy
            if (y >= height) break
            for (dx in 0 until size) {
                val x = bx + dx
                if (x >= width) break
                body(x, y)
            }
        }
    }

    private fun sample(pixels: IntArray, width: Int, height: Int, x: Int, y: Int): Int =
        pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private class Sheet(planeTexels: Int) {
        var data = IntArray(minOf(MosaicFormat.MAX_TEXELS, maxOf(planeTexels * 2, 1 shl 16)))
        var top = planeTexels

        fun allocate(count: Int): Int? {
            if (top + count > MosaicFormat.MAX_TEXELS) return null
            if (top + count > data.size) {
                val grown = minOf(MosaicFormat.MAX_TEXELS, maxOf(data.size * 2, top + count))
                data = data.copyOf(grown)
            }
            val at = top
            top += count
            return at
        }
    }
}
