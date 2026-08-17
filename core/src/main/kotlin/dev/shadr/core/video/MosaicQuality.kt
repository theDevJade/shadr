/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

import kotlin.math.log10
import kotlin.math.min

/**
 * How close a reconstruction is.
 */
object MosaicQuality {

    data class Frame(val index: Int, val psnr: Double, val ssim: Double)

    data class Report(
        val psnr: Double,
        val ssim: Double,
        val frames: List<Frame>,
    ) {
        val worstPsnr: Frame? get() = frames.minByOrNull { it.psnr }

        val worstSsim: Frame? get() = frames.minByOrNull { it.ssim }

        override fun toString(): String = buildString {
            append("PSNR %.1f dB, SSIM %.3f".format(psnr, ssim))
            worstPsnr?.let { append(", worst frame %.1f dB at %d".format(it.psnr, it.index)) }
        }
    }

    private const val PEAK = 255.0

    private val C1 = (0.01 * PEAK) * (0.01 * PEAK)
    private val C2 = (0.03 * PEAK) * (0.03 * PEAK)

    private const val WINDOW = 8

    fun compare(
        reference: List<IntArray>,
        decoded: List<IntArray>,
        width: Int,
        height: Int,
    ): Report {
        val count = min(reference.size, decoded.size)
        var squaredError = 0.0
        var samples = 0L
        val frames = ArrayList<Frame>(count)

        for (index in 0 until count) {
            val a = reference[index]
            val b = decoded[index]
            val error = squaredError(a, b)
            squaredError += error
            samples += a.size.toLong() * 3

            frames += Frame(
                index = index,
                psnr = toPsnr(error / (a.size * 3)),
                ssim = ssim(a, b, width, height),
            )
        }

        return Report(
            psnr = toPsnr(if (samples == 0L) 0.0 else squaredError / samples),
            ssim = if (frames.isEmpty()) 1.0 else frames.sumOf { it.ssim } / frames.size,
            frames = frames,
        )
    }

    fun psnr(a: IntArray, b: IntArray): Double =
        toPsnr(squaredError(a, b) / (a.size * 3))

    /**
     * Mean structural similarity.
     */
    fun ssim(a: IntArray, b: IntArray, width: Int, height: Int): Double {
        if (width < WINDOW || height < WINDOW) return 1.0

        val lumaA = luma(a)
        val lumaB = luma(b)

        var total = 0.0
        var windows = 0

        var y = 0
        while (y + WINDOW <= height) {
            var x = 0
            while (x + WINDOW <= width) {
                total += window(lumaA, lumaB, width, x, y)
                windows++
                x += WINDOW / 2
            }
            y += WINDOW / 2
        }
        return if (windows == 0) 1.0 else total / windows
    }

    private fun window(a: DoubleArray, b: DoubleArray, width: Int, ox: Int, oy: Int): Double {
        var sumA = 0.0
        var sumB = 0.0
        var sumAA = 0.0
        var sumBB = 0.0
        var sumAB = 0.0

        for (dy in 0 until WINDOW) {
            var at = (oy + dy) * width + ox
            for (dx in 0 until WINDOW) {
                val va = a[at]
                val vb = b[at]
                sumA += va
                sumB += vb
                sumAA += va * va
                sumBB += vb * vb
                sumAB += va * vb
                at++
            }
        }

        val n = (WINDOW * WINDOW).toDouble()
        val meanA = sumA / n
        val meanB = sumB / n
        val varianceA = sumAA / n - meanA * meanA
        val varianceB = sumBB / n - meanB * meanB
        val covariance = sumAB / n - meanA * meanB

        return ((2 * meanA * meanB + C1) * (2 * covariance + C2)) /
            ((meanA * meanA + meanB * meanB + C1) * (varianceA + varianceB + C2))
    }

    /** BT.601 luma */
    private fun luma(pixels: IntArray): DoubleArray {
        val out = DoubleArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            out[i] = 0.299 * ((c ushr 16) and 0xFF) +
                0.587 * ((c ushr 8) and 0xFF) +
                0.114 * (c and 0xFF)
        }
        return out
    }

    private fun squaredError(a: IntArray, b: IntArray): Double {
        var total = 0.0
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            val dr = ((x ushr 16) and 0xFF) - ((y ushr 16) and 0xFF)
            val dg = ((x ushr 8) and 0xFF) - ((y ushr 8) and 0xFF)
            val db = (x and 0xFF) - (y and 0xFF)
            total += (dr * dr + dg * dg + db * db).toDouble()
        }
        return total
    }

    private fun toPsnr(meanSquaredError: Double): Double =
        if (meanSquaredError <= 0.0) 99.0 else 10.0 * log10(PEAK * PEAK / meanSquaredError)
}
