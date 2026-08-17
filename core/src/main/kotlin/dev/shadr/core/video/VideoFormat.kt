/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

object VideoFormat {

    const val MARKER_ALPHA = 0xFA

    /**
     * Top [KEY_BITS] of red for a video pixel once it has landed in `minecraft:main`.
     */
    const val KEY = 0x2

    const val KEY_BITS = 2

    const val UV_BITS = 11

    const val UV_MAX = (1 shl UV_BITS) - 1

    const val UV_CONTINUITY = 64

    fun pack(u: Double, v: Double): Triple<Int, Int, Int> {
        val x = quantise(u)
        val y = quantise(v)
        return Triple(
            (KEY shl 6) or (x shr 5),
            ((x and 0x1F) shl 3) or (y shr 8),
            y and 0xFF,
        )
    }

    fun unpack(r: Int, g: Int, b: Int): Pair<Int, Int>? {
        if ((r shr 6) != KEY) return null
        return Pair(
            ((r and 0x3F) shl 5) or (g shr 3),
            ((g and 0x07) shl 8) or b,
        )
    }

    private fun quantise(value: Double): Int =
        Math.round(value.coerceIn(0.0, 1.0) * UV_MAX).toInt().coerceIn(0, UV_MAX)
}
