/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

object VideoFormat {

    const val MARKER_ALPHA = 0xFA

    const val KEY_BITS = 4

    const val UV_BITS = 10

    const val UV_MAX = (1 shl UV_BITS) - 1

    const val UV_CONTINUITY = 16

    fun key(pixelX: Int, pixelY: Int): Int = ((pixelX and 3) shl 2) or (pixelY and 3)

    fun pack(u: Double, v: Double, pixelX: Int, pixelY: Int): Triple<Int, Int, Int> {
        val x = quantise(u)
        val y = quantise(v)
        return Triple(
            (key(pixelX, pixelY) shl 4) or (x shr 6),
            ((x and 0x3F) shl 2) or (y shr 8),
            y and 0xFF,
        )
    }

    fun unpack(r: Int, g: Int, b: Int, pixelX: Int, pixelY: Int): Pair<Int, Int>? {
        if ((r shr 4) != key(pixelX, pixelY)) return null
        return Pair(
            ((r and 0x0F) shl 6) or (g shr 2),
            ((g and 0x03) shl 8) or b,
        )
    }

    private fun quantise(value: Double): Int =
        Math.round(value.coerceIn(0.0, 1.0) * UV_MAX).toInt().coerceIn(0, UV_MAX)
}
