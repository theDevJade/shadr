/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.stream

object StreamImage {

    const val WORDS_PER_PIXEL = 4

    const val PAYLOAD_BASE = MapPalette.MAP_EDGE

    const val TILE_WIDTH = 64

    const val TILE_HEIGHT = (MapPalette.MAP_WORDS - PAYLOAD_BASE) / WORDS_PER_PIXEL / TILE_WIDTH

    const val PIXELS_PER_TILE = TILE_WIDTH * TILE_HEIGHT

    fun width(columns: Int): Int = columns * TILE_WIDTH

    fun height(rows: Int): Int = rows * TILE_HEIGHT

    fun wordIndex(px: Int, py: Int): Int = PAYLOAD_BASE + (py * TILE_WIDTH + px) * WORDS_PER_PIXEL

    fun split(rgb: Int): IntArray {
        val value = rgb and 0xFFFFFF
        return intArrayOf(
            value and 0x7F,
            (value shr 7) and 0x7F,
            (value shr 14) and 0x7F,
            (value shr 21) and 0x7F,
        )
    }

    fun join(w0: Int, w1: Int, w2: Int, w3: Int): Int =
        (w0 and 0x7F) or ((w1 and 0x7F) shl 7) or ((w2 and 0x7F) shl 14) or ((w3 and 0x7F) shl 21)

    fun encode(channel: StreamChannel, pixels: IntArray, imageWidth: Int, imageHeight: Int) {
        require(imageWidth == width(channel.columns)) {
            "image is ${imageWidth}px wide, the ${channel.columns} slot columns want ${width(channel.columns)}"
        }
        require(imageHeight == height(channel.rows)) {
            "image is ${imageHeight}px tall, the ${channel.rows} slot rows want ${height(channel.rows)}"
        }
        require(pixels.size == imageWidth * imageHeight) { "pixel buffer does not match the stated size" }

        for (slot in 0 until channel.slots) {
            val words = channel.slot(slot)
            val originX = slot % channel.columns * TILE_WIDTH
            val originY = slot / channel.columns * TILE_HEIGHT
            for (py in 0 until TILE_HEIGHT) {
                for (px in 0 until TILE_WIDTH) {
                    val rgb = pixels[(originY + py) * imageWidth + originX + px]
                    val parts = split(rgb)
                    val base = wordIndex(px, py)
                    words[base] = parts[0]
                    words[base + 1] = parts[1]
                    words[base + 2] = parts[2]
                    words[base + 3] = parts[3]
                }
            }
        }
    }

    fun decode(channel: StreamChannel, imageWidth: Int, imageHeight: Int): IntArray {
        val out = IntArray(imageWidth * imageHeight)
        for (slot in 0 until channel.slots) {
            val words = channel.slot(slot)
            val originX = slot % channel.columns * TILE_WIDTH
            val originY = slot / channel.columns * TILE_HEIGHT
            for (py in 0 until TILE_HEIGHT) {
                for (px in 0 until TILE_WIDTH) {
                    val base = wordIndex(px, py)
                    out[(originY + py) * imageWidth + originX + px] =
                        join(words[base], words[base + 1], words[base + 2], words[base + 3])
                }
            }
        }
        return out
    }

    fun glsl(): String {
        val builder = StringBuilder()
        builder.append("#define SHADR_IMAGE_WORDS_PER_PIXEL ").append(WORDS_PER_PIXEL).append('\n')
        builder.append("#define SHADR_IMAGE_PAYLOAD_BASE ").append(PAYLOAD_BASE).append('\n')
        builder.append("#define SHADR_IMAGE_TILE_WIDTH ").append(TILE_WIDTH).append('\n')
        builder.append("#define SHADR_IMAGE_TILE_HEIGHT ").append(TILE_HEIGHT).append('\n')
        return builder.toString()
    }
}
