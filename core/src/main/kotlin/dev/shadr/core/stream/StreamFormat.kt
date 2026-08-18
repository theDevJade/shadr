/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.stream

object StreamFormat {

    const val VERSION = 2

    const val MAGIC = 0x2B55

    const val MAGIC_LOW = MAGIC and 0x7F

    const val MAGIC_HIGH = (MAGIC shr 7) and 0x7F

    const val HEADER_WORDS = 16

    const val W_MAGIC_LOW = 0

    const val W_MAGIC_HIGH = 1

    const val W_VERSION = 2

    const val W_SLOT = 3

    const val W_STREAM = 4

    const val W_SERIAL_LOW = 5

    const val W_SERIAL_HIGH = 6

    const val W_REGION_X_LOW = 7

    const val W_REGION_X_HIGH = 8

    const val W_REGION_Y_LOW = 9

    const val W_REGION_Y_HIGH = 10

    const val W_SLOT_COLUMNS = 11

    const val W_SLOT_ROWS = 12

    const val W_FLAGS = 13

    const val FLAG_ACTIVE = 1

    const val MAX_SLOTS = 128

    const val MAX_STREAMS = 128

    const val SERIAL_MODULUS = 1 shl 14

    const val DESIGN_WIDTH = 1920

    const val DESIGN_HEIGHT = 1080

    fun splitLow(value: Int): Int = value and 0x7F

    fun splitHigh(value: Int): Int = (value shr 7) and 0x7F

    fun join(low: Int, high: Int): Int = (high shl 7) or low

    fun slotColumns(slots: Int): Int {
        require(slots in 1..MAX_SLOTS) { "slots $slots out of range" }
        var columns = 1
        while (columns * columns < slots) columns++
        return columns
    }

    fun slotRows(slots: Int): Int {
        val columns = slotColumns(slots)
        return (slots + columns - 1) / columns
    }

    fun regionWidth(slots: Int): Int = slotColumns(slots) * MapPalette.MAP_EDGE

    fun regionHeight(slots: Int): Int = slotRows(slots) * MapPalette.MAP_EDGE

    fun gridColumns(screenWidth: Int, screenHeight: Int, target: Int): Int {
        val maxColumns = (screenWidth / MapPalette.MAP_EDGE).coerceAtLeast(1)
        val maxRows = (screenHeight / MapPalette.MAP_EDGE).coerceAtLeast(1)
        val capacity = maxColumns * maxRows
        val slots = target.coerceAtMost(capacity)
        return maxColumns.coerceAtMost(slots).coerceAtLeast((slots + maxRows - 1) / maxRows)
    }

    fun writeHeader(
        words: IntArray,
        slot: Int,
        stream: Int,
        serial: Int,
        regionX: Int,
        regionY: Int,
        columns: Int,
        rows: Int,
        flags: Int,
    ) {
        require(words.size >= HEADER_WORDS) { "header needs $HEADER_WORDS words" }
        require(columns in 1..MapPalette.WORDS - 1 && rows in 1..MapPalette.WORDS - 1) {
            "grid ${columns}x$rows does not fit the header channel"
        }
        require(slot in 0 until MAX_SLOTS) { "slot $slot out of range" }
        require(stream in 0 until MAX_STREAMS) { "stream $stream out of range" }
        words[W_MAGIC_LOW] = MAGIC_LOW
        words[W_MAGIC_HIGH] = MAGIC_HIGH
        words[W_VERSION] = VERSION
        words[W_SLOT] = slot
        words[W_STREAM] = stream
        words[W_SERIAL_LOW] = splitLow(serial % SERIAL_MODULUS)
        words[W_SERIAL_HIGH] = splitHigh(serial % SERIAL_MODULUS)
        words[W_REGION_X_LOW] = splitLow(regionX)
        words[W_REGION_X_HIGH] = splitHigh(regionX)
        words[W_REGION_Y_LOW] = splitLow(regionY)
        words[W_REGION_Y_HIGH] = splitHigh(regionY)
        words[W_SLOT_COLUMNS] = columns
        words[W_SLOT_ROWS] = rows
        words[W_FLAGS] = flags
    }

    fun writeHeader(words: IntArray, slot: Int, stream: Int, serial: Int, regionX: Int, regionY: Int, slots: Int, flags: Int) =
        writeHeader(words, slot, stream, serial, regionX, regionY, slotColumns(slots), slotRows(slots), flags)

    fun readHeaderMagic(words: IntArray): Boolean =
        words.size >= HEADER_WORDS &&
            words[W_MAGIC_LOW] == MAGIC_LOW &&
            words[W_MAGIC_HIGH] == MAGIC_HIGH &&
            words[W_VERSION] == VERSION

    fun readSerial(words: IntArray): Int = join(words[W_SERIAL_LOW], words[W_SERIAL_HIGH])

    fun readRegionX(words: IntArray): Int = join(words[W_REGION_X_LOW], words[W_REGION_X_HIGH])

    fun readRegionY(words: IntArray): Int = join(words[W_REGION_Y_LOW], words[W_REGION_Y_HIGH])

    fun glsl(): String {
        val builder = StringBuilder()
        builder.append("#define SHADR_STREAM_VERSION ").append(VERSION).append('\n')
        builder.append("#define SHADR_STREAM_MAGIC_LOW ").append(MAGIC_LOW).append('\n')
        builder.append("#define SHADR_STREAM_MAGIC_HIGH ").append(MAGIC_HIGH).append('\n')
        builder.append("#define SHADR_STREAM_HEADER_WORDS ").append(HEADER_WORDS).append('\n')
        builder.append("#define SHADR_STREAM_W_MAGIC_LOW ").append(W_MAGIC_LOW).append('\n')
        builder.append("#define SHADR_STREAM_W_MAGIC_HIGH ").append(W_MAGIC_HIGH).append('\n')
        builder.append("#define SHADR_STREAM_W_VERSION ").append(W_VERSION).append('\n')
        builder.append("#define SHADR_STREAM_W_SLOT ").append(W_SLOT).append('\n')
        builder.append("#define SHADR_STREAM_W_STREAM ").append(W_STREAM).append('\n')
        builder.append("#define SHADR_STREAM_W_SERIAL_LOW ").append(W_SERIAL_LOW).append('\n')
        builder.append("#define SHADR_STREAM_W_SERIAL_HIGH ").append(W_SERIAL_HIGH).append('\n')
        builder.append("#define SHADR_STREAM_W_REGION_X_LOW ").append(W_REGION_X_LOW).append('\n')
        builder.append("#define SHADR_STREAM_W_REGION_X_HIGH ").append(W_REGION_X_HIGH).append('\n')
        builder.append("#define SHADR_STREAM_W_REGION_Y_LOW ").append(W_REGION_Y_LOW).append('\n')
        builder.append("#define SHADR_STREAM_W_REGION_Y_HIGH ").append(W_REGION_Y_HIGH).append('\n')
        builder.append("#define SHADR_STREAM_W_SLOT_COLUMNS ").append(W_SLOT_COLUMNS).append('\n')
        builder.append("#define SHADR_STREAM_W_SLOT_ROWS ").append(W_SLOT_ROWS).append('\n')
        builder.append("#define SHADR_STREAM_W_FLAGS ").append(W_FLAGS).append('\n')
        builder.append("#define SHADR_STREAM_FLAG_ACTIVE ").append(FLAG_ACTIVE).append('\n')
        builder.append("#define SHADR_STREAM_DESIGN_WIDTH ").append(DESIGN_WIDTH).append(".0\n")
        builder.append("#define SHADR_STREAM_DESIGN_HEIGHT ").append(DESIGN_HEIGHT).append(".0\n")
        return builder.toString()
    }
}
