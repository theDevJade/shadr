/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

object MosaicBc1 {

    const val WEIGHT_R = 0.897
    const val WEIGHT_G = 1.761
    const val WEIGHT_B = 0.342

    val INDEX_WEIGHT_4 = doubleArrayOf(0.0, 1.0, 1.0 / 3.0, 2.0 / 3.0)

    val INDEX_WEIGHT_3 = doubleArrayOf(0.0, 1.0, 0.5, 0.5)

    class Fit(val e0: Int, val e1: Int, val palette: IntArray, val error: Double)

    fun fillPalette(e0: Int, e1: Int, out: IntArray) {
        val r0 = (e0 ushr 11) and 0x1F
        val g0 = (e0 ushr 5) and 0x3F
        val b0 = e0 and 0x1F
        val r1 = (e1 ushr 11) and 0x1F
        val g1 = (e1 ushr 5) and 0x3F
        val b1 = e1 and 0x1F

        out[0] = (r0 shl 3) or (r0 ushr 2)
        out[1] = (g0 shl 2) or (g0 ushr 4)
        out[2] = (b0 shl 3) or (b0 ushr 2)
        out[3] = (r1 shl 3) or (r1 ushr 2)
        out[4] = (g1 shl 2) or (g1 ushr 4)
        out[5] = (b1 shl 3) or (b1 ushr 2)

        if (e0 > e1) {
            for (c in 0 until 3) {
                out[6 + c] = (2 * out[c] + out[3 + c]) / 3
                out[9 + c] = (out[c] + 2 * out[3 + c]) / 3
            }
        } else {
            for (c in 0 until 3) {
                out[6 + c] = (out[c] + out[3 + c]) / 2
                out[9 + c] = 0
            }
        }
    }

    fun paletteOf(e0: Int, e1: Int): IntArray = IntArray(12).also { fillPalette(e0, e1, it) }

    fun distance(r: Int, g: Int, b: Int, palette: IntArray, entry: Int): Double {
        val at = entry * 3
        val dr = (r - palette[at]).toDouble()
        val dg = (g - palette[at + 1]).toDouble()
        val db = (b - palette[at + 2]).toDouble()
        return WEIGHT_R * dr * dr + WEIGHT_G * dg * dg + WEIGHT_B * db * db
    }

    fun pick(r: Int, g: Int, b: Int, palette: IntArray): Int {
        var best = 0
        var bestError = Double.MAX_VALUE
        for (candidate in 0 until 4) {
            val error = distance(r, g, b, palette, candidate)
            if (error < bestError) {
                bestError = error
                best = candidate
            }
        }
        return best
    }

    fun code(pixels: IntArray, palette: IntArray, rows: IntArray): Double {
        rows[0] = 0; rows[1] = 0; rows[2] = 0; rows[3] = 0
        var total = 0.0
        for (i in 0 until 16) {
            val at = i * 3
            val choice = pick(pixels[at], pixels[at + 1], pixels[at + 2], palette)
            total += distance(pixels[at], pixels[at + 1], pixels[at + 2], palette, choice)
            rows[i / 4] = rows[i / 4] or (choice shl ((i % 4) * 2))
        }
        return total
    }

    fun error(pixels: IntArray, palette: IntArray): Double = error(pixels, palette, Double.MAX_VALUE)

    fun error(pixels: IntArray, palette: IntArray, limit: Double): Double {
        var total = 0.0
        for (i in 0 until 16) {
            val at = i * 3
            val r = pixels[at]; val g = pixels[at + 1]; val b = pixels[at + 2]
            var best = Double.MAX_VALUE
            for (candidate in 0 until 4) {
                val d = distance(r, g, b, palette, candidate)
                if (d < best) best = d
            }
            total += best
            if (total >= limit) return total
        }
        return total
    }

    fun fit(pixels: IntArray, cluster: Boolean = true): Fit {
        val scratch = SCRATCH.get()
        var minR = 255; var minG = 255; var minB = 255
        var maxR = 0; var maxG = 0; var maxB = 0
        for (i in 0 until 16) {
            val at = i * 3
            val r = pixels[at]; val g = pixels[at + 1]; val b = pixels[at + 2]
            if (r < minR) minR = r
            if (g < minG) minG = g
            if (b < minB) minB = b
            if (r > maxR) maxR = r
            if (g > maxG) maxG = g
            if (b > maxB) maxB = b
        }

        var bestE0 = MosaicReferenceDecoder.pack565(maxR, maxG, maxB)
        var bestE1 = MosaicReferenceDecoder.pack565(minR, minG, minB)
        val bestPalette = scratch.best
        fillPalette(bestE0, bestE1, bestPalette)
        var bestError = error(pixels, bestPalette)

        val candidate = scratch.candidate
        fun consider(e0Raw: Int, e1Raw: Int, ordered: Boolean) {
            var e0 = e0Raw
            var e1 = e1Raw
            if (ordered && e0 < e1) {
                val t = e0; e0 = e1; e1 = t
            }
            if (!ordered && e0 > e1) {
                val t = e0; e0 = e1; e1 = t
            }
            if (e0 == bestE0 && e1 == bestE1) return
            fillPalette(e0, e1, candidate)
            val candidateError = error(pixels, candidate, bestError)
            if (candidateError < bestError) {
                bestError = candidateError
                bestE0 = e0
                bestE1 = e1
                candidate.copyInto(bestPalette)
            }
        }

        repeat(2) {
            val refined = refine(
                pixels, bestPalette, if (bestE0 > bestE1) INDEX_WEIGHT_4 else INDEX_WEIGHT_3,
                scratch,
            )
            if (refined) consider(scratch.refined[0], scratch.refined[1], bestE0 > bestE1)
        }

        val spread = (maxR - minR) + (maxG - minG) + (maxB - minB)
        if (cluster && bestError > CLUSTER_FLOOR && spread > 12) {
            val axis = principalAxis(pixels, scratch)
            if (axis) {
                project(pixels, scratch)
                clusterCandidates(pixels, scratch, GROUP_WEIGHTS_4, true, ::consider)
                clusterCandidates(pixels, scratch, GROUP_WEIGHTS_3, false, ::consider)
            }
        }

        return Fit(bestE0, bestE1, bestPalette.copyOf(), bestError)
    }

    private const val CLUSTER_FLOOR = 96.0

    private class Scratch {
        val best = IntArray(12)
        val candidate = IntArray(12)
        val refined = IntArray(2)
        val axis = DoubleArray(3)
        val cov = DoubleArray(9)
        val projection = DoubleArray(16)
        val order = IntArray(16)
        val sums = DoubleArray(17)
        val squares = DoubleArray(17)
        val sumA = DoubleArray(3)
        val sumB = DoubleArray(3)
    }

    private val SCRATCH = ThreadLocal.withInitial { Scratch() }

    private fun refine(
        pixels: IntArray,
        palette: IntArray,
        weights: DoubleArray,
        scratch: Scratch,
    ): Boolean {
        var sumAA = 0.0
        var sumAB = 0.0
        var sumBB = 0.0
        val sumA = scratch.sumA
        val sumB = scratch.sumB
        sumA.fill(0.0)
        sumB.fill(0.0)

        for (i in 0 until 16) {
            val at = i * 3
            val choice = pick(pixels[at], pixels[at + 1], pixels[at + 2], palette)
            val w = weights[choice]
            val a = 1.0 - w
            sumAA += a * a
            sumAB += a * w
            sumBB += w * w
            for (channel in 0 until 3) {
                sumA[channel] += a * pixels[at + channel]
                sumB[channel] += w * pixels[at + channel]
            }
        }

        return solve(sumAA, sumAB, sumBB, sumA, sumB, scratch)
    }

    private fun solve(
        sumAA: Double,
        sumAB: Double,
        sumBB: Double,
        sumA: DoubleArray,
        sumB: DoubleArray,
        scratch: Scratch,
    ): Boolean {
        val determinant = sumAA * sumBB - sumAB * sumAB
        if (abs(determinant) < 1e-6) return false

        var r0 = 0; var g0 = 0; var b0 = 0
        var r1 = 0; var g1 = 0; var b1 = 0
        for (channel in 0 until 3) {
            val a = ((sumBB * sumA[channel] - sumAB * sumB[channel]) / determinant)
                .roundToInt().coerceIn(0, 255)
            val b = ((sumAA * sumB[channel] - sumAB * sumA[channel]) / determinant)
                .roundToInt().coerceIn(0, 255)
            when (channel) {
                0 -> { r0 = a; r1 = b }
                1 -> { g0 = a; g1 = b }
                else -> { b0 = a; b1 = b }
            }
        }
        scratch.refined[0] = MosaicReferenceDecoder.pack565(r0, g0, b0)
        scratch.refined[1] = MosaicReferenceDecoder.pack565(r1, g1, b1)
        return true
    }

    private val GROUP_WEIGHTS_4 = doubleArrayOf(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0)

    private val GROUP_WEIGHTS_3 = doubleArrayOf(0.0, 0.5, 1.0)

    private const val EXACT_CANDIDATES = 3

    private val AXIS_SCALE = doubleArrayOf(sqrt(WEIGHT_R), sqrt(WEIGHT_G), sqrt(WEIGHT_B))

    private fun principalAxis(pixels: IntArray, scratch: Scratch): Boolean {
        var meanR = 0.0; var meanG = 0.0; var meanB = 0.0
        for (i in 0 until 16) {
            val at = i * 3
            meanR += pixels[at]; meanG += pixels[at + 1]; meanB += pixels[at + 2]
        }
        meanR /= 16.0; meanG /= 16.0; meanB /= 16.0

        val cov = scratch.cov
        cov.fill(0.0)
        for (i in 0 until 16) {
            val at = i * 3
            val dr = (pixels[at] - meanR) * AXIS_SCALE[0]
            val dg = (pixels[at + 1] - meanG) * AXIS_SCALE[1]
            val db = (pixels[at + 2] - meanB) * AXIS_SCALE[2]
            cov[0] += dr * dr; cov[1] += dr * dg; cov[2] += dr * db
            cov[4] += dg * dg; cov[5] += dg * db
            cov[8] += db * db
        }
        cov[3] = cov[1]; cov[6] = cov[2]; cov[7] = cov[5]

        val axis = scratch.axis
        axis[0] = 1.0; axis[1] = 1.0; axis[2] = 1.0
        repeat(8) {
            var nr = 0.0; var ng = 0.0; var nb = 0.0
            for (c in 0 until 3) {
                nr += cov[c] * axis[c]
                ng += cov[3 + c] * axis[c]
                nb += cov[6 + c] * axis[c]
            }
            val norm = sqrt(nr * nr + ng * ng + nb * nb)
            if (norm < 1e-9) return false
            axis[0] = nr / norm; axis[1] = ng / norm; axis[2] = nb / norm
        }

        for (c in 0 until 3) axis[c] *= AXIS_SCALE[c]
        return true
    }

    private fun project(pixels: IntArray, scratch: Scratch) {
        val axis = scratch.axis
        val projection = scratch.projection
        val order = scratch.order
        for (i in 0 until 16) {
            val at = i * 3
            projection[i] =
                pixels[at] * axis[0] + pixels[at + 1] * axis[1] + pixels[at + 2] * axis[2]
            order[i] = i
        }

        for (i in 1 until 16) {
            val key = order[i]
            val value = projection[key]
            var j = i - 1
            while (j >= 0 && projection[order[j]] > value) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = key
        }

        val sums = scratch.sums
        val squares = scratch.squares
        sums[0] = 0.0
        squares[0] = 0.0
        for (i in 0 until 16) {
            val v = projection[order[i]]
            sums[i + 1] = sums[i] + v
            squares[i + 1] = squares[i] + v * v
        }
    }

    private fun clusterCandidates(
        pixels: IntArray,
        scratch: Scratch,
        weights: DoubleArray,
        ordered: Boolean,
        consider: (Int, Int, Boolean) -> Unit,
    ) {
        val order = scratch.order
        val prefixSums = scratch.sums
        val groups = weights.size
        val counts = IntArray(groups)
        val bestCounts = Array(EXACT_CANDIDATES) { IntArray(groups) }
        val bestErrors = DoubleArray(EXACT_CANDIDATES) { Double.MAX_VALUE }
        val totalSquares = scratch.squares[16]

        fun evaluate() {
            var sumAA = 0.0
            var sumAB = 0.0
            var sumBB = 0.0
            var sumAt = 0.0
            var sumBt = 0.0
            var start = 0
            for (g in 0 until groups) {
                val count = counts[g]
                if (count == 0) continue
                val w = weights[g]
                val a = 1.0 - w
                val sumT = prefixSums[start + count] - prefixSums[start]
                sumAA += count * a * a
                sumAB += count * a * w
                sumBB += count * w * w
                sumAt += a * sumT
                sumBt += w * sumT
                start += count
            }
            val determinant = sumAA * sumBB - sumAB * sumAB
            if (abs(determinant) < 1e-9) return
            val e0 = (sumBB * sumAt - sumAB * sumBt) / determinant
            val e1 = (sumAA * sumBt - sumAB * sumAt) / determinant
            val estimate = totalSquares - (e0 * sumAt + e1 * sumBt)

            var slot = -1
            for (k in 0 until EXACT_CANDIDATES) {
                if (estimate < bestErrors[k]) { slot = k; break }
            }
            if (slot < 0) return
            for (k in EXACT_CANDIDATES - 1 downTo slot + 1) {
                bestErrors[k] = bestErrors[k - 1]
                bestCounts[k - 1].copyInto(bestCounts[k])
            }
            bestErrors[slot] = estimate
            counts.copyInto(bestCounts[slot])
        }

        fun enumerate(group: Int, remaining: Int) {
            if (group == groups - 1) {
                counts[group] = remaining
                evaluate()
                return
            }
            for (c in 0..remaining) {
                counts[group] = c
                enumerate(group + 1, remaining - c)
            }
        }
        enumerate(0, 16)

        for (k in 0 until EXACT_CANDIDATES) {
            if (bestErrors[k] == Double.MAX_VALUE) continue
            val chosen = bestCounts[k]
            var sumAA = 0.0
            var sumAB = 0.0
            var sumBB = 0.0
            val sumA = scratch.sumA
            val sumB = scratch.sumB
            sumA.fill(0.0)
            sumB.fill(0.0)
            var start = 0
            for (g in weights.indices) {
                val w = weights[g]
                val a = 1.0 - w
                for (i in start until start + chosen[g]) {
                    val at = order[i] * 3
                    sumAA += a * a
                    sumAB += a * w
                    sumBB += w * w
                    for (channel in 0 until 3) {
                        sumA[channel] += a * pixels[at + channel]
                        sumB[channel] += w * pixels[at + channel]
                    }
                }
                start += chosen[g]
            }
            if (!solve(sumAA, sumAB, sumBB, sumA, sumB, scratch)) continue
            consider(scratch.refined[0], scratch.refined[1], ordered)
        }
    }
}
