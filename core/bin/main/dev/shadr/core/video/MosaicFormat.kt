/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

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

    const val BLK_MOTION_DELTA = 4

    const val BLK_MOTION_RESIDUAL = 5

    const val BLK_ENDPOINT_REUSE = 6

    const val BLK_INTRA_VQ = 7

    const val MODES = 8

    const val MV_BIAS = 128

    const val MV_MAX = 127

    const val RESIDUAL_BIAS = 128

    const val HEADER_TEXELS = 2

    const val INTRA_TEXELS = 8

    const val DELTA_TEXELS = 4

    const val RESIDUAL_TEXELS = 8

    const val REUSE_TEXELS = 4

    const val VQ_TEXELS = 4

    const val CODEBOOK = 4096

    const val CODEBOOK_TEXELS = CODEBOOK * 2

    fun payloadTexels(mode: Int): Int = when (mode) {
        BLK_DELTA, BLK_MOTION_DELTA -> DELTA_TEXELS
        BLK_INTRA -> INTRA_TEXELS
        BLK_MOTION_RESIDUAL -> RESIDUAL_TEXELS
        BLK_ENDPOINT_REUSE -> REUSE_TEXELS
        BLK_INTRA_VQ -> VQ_TEXELS
        else -> 0
    }

    fun commandTexels(mode: Int): Int = when (mode) {
        BLK_MOTION, BLK_MOTION_DELTA, BLK_MOTION_RESIDUAL, BLK_ENDPOINT_REUSE -> 1
        else -> 0
    }

    fun headerTexel(modes: IntArray, half: Int): Int {
        val base = half * 8
        return texel(
            modes[base] or (modes[base + 1] shl 4),
            modes[base + 2] or (modes[base + 3] shl 4),
            modes[base + 4] or (modes[base + 5] shl 4),
            modes[base + 6] or (modes[base + 7] shl 4),
        )
    }

    fun nibble(header: Int, block: Int): Int {
        val channel = when ((block and 7) / 2) {
            0 -> red(header)
            1 -> green(header)
            2 -> blue(header)
            else -> alpha(header)
        }
        return (channel ushr ((block and 1) * 4)) and 0xF
    }

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
