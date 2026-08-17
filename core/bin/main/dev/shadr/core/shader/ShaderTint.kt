/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.shader

import dev.shadr.core.Rgb
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object ShaderTint {

    const val SCALE_MIN = 0.05
    const val SCALE_MAX = 64.0
    const val STEPS = 63

    fun encode(color: Rgb, scale: Double): Int {
        val q = quantise(scale)
        val r = (color.r and 0xFC) or ((q shr 4) and 0x03)
        val g = (color.g and 0xFC) or ((q shr 2) and 0x03)
        val b = (color.b and 0xFC) or (q and 0x03)
        return (r shl 16) or (g shl 8) or b
    }

    fun decodeScale(packed: Int): Double {
        val q = (((packed shr 16) and 3) shl 4) or (((packed shr 8) and 3) shl 2) or (packed and 3)
        return SCALE_MIN * (SCALE_MAX / SCALE_MIN).pow(q.toDouble() / STEPS)
    }

    fun decodeColor(packed: Int): Rgb = Rgb(packed and 0xFCFCFC)

    private fun quantise(scale: Double): Int {
        val clamped = scale.coerceIn(SCALE_MIN, SCALE_MAX)
        val t = ln(clamped / SCALE_MIN) / ln(SCALE_MAX / SCALE_MIN)
        return (t * STEPS).roundToInt().coerceIn(0, STEPS)
    }
}
