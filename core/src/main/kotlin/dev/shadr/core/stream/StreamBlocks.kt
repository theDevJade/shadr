/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.stream

import dev.shadr.core.video.MosaicBc1
import java.util.stream.IntStream

object StreamBlocks {

    const val BLOCK = 4

    const val WORDS_PER_BLOCK = 10

    const val BLOCKS_X = 48

    const val BLOCKS_Y = 27

    data class Layout(val blocksX: Int, val blocksY: Int) {
        val tileWidth: Int get() = blocksX * BLOCK
        val tileHeight: Int get() = blocksY * BLOCK
        val words: Int get() = blocksX * blocksY * WORDS_PER_BLOCK
        fun fits(): Boolean = PAYLOAD_BASE + words <= MapPalette.MAP_WORDS
        fun width(columns: Int): Int = columns * tileWidth
        fun height(rows: Int): Int = rows * tileHeight
        fun wordIndex(bx: Int, by: Int): Int = PAYLOAD_BASE + (by * blocksX + bx) * WORDS_PER_BLOCK
    }

    val DEFAULT = Layout(BLOCKS_X, BLOCKS_Y)

    const val PAYLOAD_BASE = MapPalette.MAP_EDGE

    const val TILE_WIDTH = BLOCKS_X * BLOCK

    const val TILE_HEIGHT = BLOCKS_Y * BLOCK

    fun width(columns: Int): Int = columns * TILE_WIDTH

    fun height(rows: Int): Int = rows * TILE_HEIGHT

    fun wordIndex(bx: Int, by: Int): Int = PAYLOAD_BASE + (by * BLOCKS_X + bx) * WORDS_PER_BLOCK

    fun fits(): Boolean = wordIndex(BLOCKS_X - 1, BLOCKS_Y - 1) + WORDS_PER_BLOCK <= MapPalette.MAP_WORDS

    fun split32(value: Int, out: IntArray, at: Int) {
        out[at] = value and 0x7F
        out[at + 1] = (value ushr 7) and 0x7F
        out[at + 2] = (value ushr 14) and 0x7F
        out[at + 3] = (value ushr 21) and 0x7F
        out[at + 4] = (value ushr 28) and 0x0F
    }

    fun join32(w0: Int, w1: Int, w2: Int, w3: Int, w4: Int): Int =
        (w0 and 0x7F) or ((w1 and 0x7F) shl 7) or ((w2 and 0x7F) shl 14) or
            ((w3 and 0x7F) shl 21) or ((w4 and 0x0F) shl 28)

    class Stats(val coded: Int, val skipped: Int) {
        val total: Int get() = coded + skipped
    }

    @JvmOverloads
    fun encode(
        channel: StreamChannel,
        pixels: IntArray,
        imageWidth: Int,
        imageHeight: Int,
        cluster: Boolean = false,
        layout: Layout = DEFAULT,
        skipThreshold: Double = 0.0,
    ): Stats {
        require(imageWidth == layout.width(channel.columns) && imageHeight == layout.height(channel.rows)) {
            "image is ${imageWidth}x$imageHeight, the channel wants " +
                "${layout.width(channel.columns)}x${layout.height(channel.rows)}"
        }
        require(pixels.size == imageWidth * imageHeight) { "pixel buffer does not match the stated size" }

        val skipped = java.util.concurrent.atomic.AtomicInteger()
        IntStream.range(0, channel.slots).parallel().forEach { slot ->
            val words = channel.slot(slot)
            val palette = IntArray(12)
            var localSkips = 0
            val originX = slot % channel.columns * layout.tileWidth
            val originY = slot / channel.columns * layout.tileHeight
            val block = IntArray(48)
            val rows = IntArray(4)
            for (by in 0 until layout.blocksY) {
                for (bx in 0 until layout.blocksX) {
                    var at = 0
                    for (py in 0 until BLOCK) {
                        val y = originY + by * BLOCK + py
                        var index = y * imageWidth + originX + bx * BLOCK
                        for (px in 0 until BLOCK) {
                            val rgb = pixels[index++]
                            block[at++] = (rgb shr 16) and 0xFF
                            block[at++] = (rgb shr 8) and 0xFF
                            block[at++] = rgb and 0xFF
                        }
                    }
                    val base = layout.wordIndex(bx, by)
                    if (skipThreshold > 0.0 && keeps(words, base, block, palette, skipThreshold)) {
                        localSkips++
                        continue
                    }
                    val fit = MosaicBc1.fit(block, cluster)
                    MosaicBc1.code(block, fit.palette, rows)
                    split32((fit.e0 and 0xFFFF) or ((fit.e1 and 0xFFFF) shl 16), words, base)
                    split32(
                        (rows[0] and 0xFF) or ((rows[1] and 0xFF) shl 8) or
                            ((rows[2] and 0xFF) shl 16) or ((rows[3] and 0xFF) shl 24),
                        words,
                        base + 5,
                    )
                }
            }
            skipped.addAndGet(localSkips)
        }
        val total = channel.slots * layout.blocksX * layout.blocksY
        return Stats(total - skipped.get(), skipped.get())
    }

    private fun keeps(
        words: IntArray,
        base: Int,
        block: IntArray,
        palette: IntArray,
        threshold: Double,
    ): Boolean {
        val lo = join32(words[base], words[base + 1], words[base + 2], words[base + 3], words[base + 4])
        val hi = join32(words[base + 5], words[base + 6], words[base + 7], words[base + 8], words[base + 9])
        if (lo == 0 && hi == 0) return false
        MosaicBc1.fillPalette(lo and 0xFFFF, (lo ushr 16) and 0xFFFF, palette)

        var error = 0.0
        val limit = threshold * 16.0
        for (i in 0 until 16) {
            val choice = (hi ushr ((i / 4) * 8 + (i % 4) * 2)) and 3
            val at = i * 3
            error += MosaicBc1.distance(block[at], block[at + 1], block[at + 2], palette, choice)
            if (error > limit) return false
        }
        return true
    }

    fun decode(channel: StreamChannel, imageWidth: Int, imageHeight: Int): IntArray {
        val out = IntArray(imageWidth * imageHeight)
        val palette = IntArray(12)
        for (slot in 0 until channel.slots) {
            val words = channel.slot(slot)
            val originX = slot % channel.columns * TILE_WIDTH
            val originY = slot / channel.columns * TILE_HEIGHT
            for (by in 0 until BLOCKS_Y) {
                for (bx in 0 until BLOCKS_X) {
                    val base = wordIndex(bx, by)
                    val lo = join32(words[base], words[base + 1], words[base + 2], words[base + 3], words[base + 4])
                    val hi = join32(
                        words[base + 5], words[base + 6], words[base + 7], words[base + 8], words[base + 9],
                    )
                    MosaicBc1.fillPalette(lo and 0xFFFF, (lo ushr 16) and 0xFFFF, palette)
                    for (py in 0 until BLOCK) {
                        val selectors = (hi ushr (py * 8)) and 0xFF
                        val y = originY + by * BLOCK + py
                        var index = y * imageWidth + originX + bx * BLOCK
                        for (px in 0 until BLOCK) {
                            val choice = (selectors ushr (px * 2)) and 3
                            val at = choice * 3
                            out[index++] = (palette[at] shl 16) or (palette[at + 1] shl 8) or palette[at + 2]
                        }
                    }
                }
            }
        }
        return out
    }

    fun glsl(): String {
        val builder = StringBuilder()
        builder.append("#define SHADR_BLOCK_EDGE ").append(BLOCK).append('\n')
        builder.append("#define SHADR_BLOCK_WORDS ").append(WORDS_PER_BLOCK).append('\n')
        builder.append("#define SHADR_BLOCK_PAYLOAD_BASE ").append(PAYLOAD_BASE).append('\n')
        builder.append("#define SHADR_BLOCKS_X ").append(BLOCKS_X).append('\n')
        builder.append("#define SHADR_BLOCKS_Y ").append(BLOCKS_Y).append('\n')
        builder.append("#define SHADR_BLOCK_TILE_WIDTH ").append(TILE_WIDTH).append('\n')
        builder.append("#define SHADR_BLOCK_TILE_HEIGHT ").append(TILE_HEIGHT).append('\n')
        return builder.toString()
    }
}
