/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

// Custom-ish format.
object MosaicFormat {

    const val BLOCK = 8

    const val SUPER = 32

    const val BLOCKS_PER_SUPER_EDGE = SUPER / BLOCK

    const val BLOCKS_PER_SUPER = BLOCKS_PER_SUPER_EDGE * BLOCKS_PER_SUPER_EDGE

    const val SHEET_EDGE = 4096

    const val MAX_TEXELS = SHEET_EDGE * SHEET_EDGE

    const val SB_SKIP = 0

    const val SB_CODED = 1

    const val SB_MOTION = 2

    const val BLK_SKIP = 0

    const val BLK_MOTION = 1

    const val BLK_DELTA = 2

    const val BLK_INTRA = 3

    const val MV_BIAS = 128

    const val MV_MAX = 127

    const val INTRA_TEXELS = 8

    const val DELTA_TEXELS = 4

    fun superColumns(width: Int): Int = (width + SUPER - 1) / SUPER

    fun superRows(height: Int): Int = (height + SUPER - 1) / SUPER

    fun superblocksPerFrame(width: Int, height: Int): Int =
        superColumns(width) * superRows(height)

    fun superblockIndex(frame: Int, sx: Int, sy: Int, columns: Int, perFrame: Int): Int =
        frame * perFrame + sy * columns + sx

    fun texelX(index: Int): Int = index % SHEET_EDGE

    fun texelY(index: Int): Int = index / SHEET_EDGE

    fun texel(r: Int, g: Int, b: Int, a: Int): Int =
        ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    fun red(texel: Int): Int = (texel ushr 16) and 0xFF

    fun green(texel: Int): Int = (texel ushr 8) and 0xFF

    fun blue(texel: Int): Int = texel and 0xFF

    fun alpha(texel: Int): Int = (texel ushr 24) and 0xFF

    fun pointer(texel: Int): Int =
        (green(texel) shl 16) or (blue(texel) shl 8) or alpha(texel)

    fun withPointer(mode: Int, pointer: Int): Int = texel(
        r = mode,
        g = (pointer ushr 16) and 0xFF,
        b = (pointer ushr 8) and 0xFF,
        a = pointer and 0xFF,
    )
}
