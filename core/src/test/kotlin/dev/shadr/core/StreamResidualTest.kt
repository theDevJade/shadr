/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.stream.StreamChannel
import dev.shadr.core.stream.StreamCodec
import kotlin.test.Test
import kotlin.test.assertTrue

class StreamResidualTest {

    @Test
    fun `a smooth luma error codes as a sparse transform residual`() {
        val g = StreamCodec.Geometry(64, 64, 16)
        val channel = StreamChannel(g.slots, 0)
        val reconstruction = IntArray(64 * 64) { 0x808080 }
        val source = IntArray(64 * 64) { i ->
            val x = i % 64
            val y = i / 64
            val bump = (16.0 * Math.sin(Math.PI * x / 32.0) * Math.sin(Math.PI * y / 32.0)).toInt()
            val v = (0x80 + bump).coerceIn(0, 255)
            (v shl 16) or (v shl 8) or v
        }
        val expected = source.copyOf()

        val stats = StreamCodec.encode(channel, source, reconstruction, g, StreamCodec.Options(), 0)
        assertTrue(stats.residual >= g.cus / 2, "smooth error should code as residual, got $stats")

        var nonZero = 0
        var total = 0
        for (cu in 0 until g.cus) {
            if (StreamCodec.read(channel, cu * StreamCodec.PLANE_WORDS) != StreamCodec.MODE_RESIDUAL) continue
            val at = g.residualIndex(cu)
            for (w in 0 until StreamCodec.RESIDUAL_WORDS) {
                total++
                if (StreamCodec.read(channel, at + w) != StreamCodec.RESIDUAL_BIAS) nonZero++
            }
        }
        assertTrue(nonZero > 0, "the residual carries no coefficients at all")
        assertTrue(nonZero * 2 < total, "the residual is dense: $nonZero of $total words")

        var err = 0L
        for (i in expected.indices) {
            for (shift in listOf(16, 8, 0)) {
                err += Math.abs(((expected[i] shr shift) and 0xFF) - ((reconstruction[i] shr shift) and 0xFF))
            }
        }
        val mean = err.toDouble() / (expected.size * 3)
        assertTrue(mean < 3.5, "mean abs error %.2f after one transform residual frame".format(mean))
    }

    @Test
    fun `sequency ordering keeps the low frequencies`() {
        for (k in 0 until StreamCodec.KEPT) {
            val u = StreamCodec.KEPT_U[k]
            var changes = 0
            var last = Integer.bitCount(0 and u) % 2
            for (x in 1 until StreamCodec.RES_BLOCK) {
                val sign = Integer.bitCount(x and u) % 2
                if (sign != last) changes++
                last = sign
            }
            assertTrue(changes == k, "kept index $k has sequency $changes, expected $k")
        }
    }
}
