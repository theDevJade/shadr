/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.stream

fun interface StreamSink {
    fun mapData(mapId: Int, startX: Int, startY: Int, width: Int, height: Int, colors: ByteArray)
}

class StreamChannel @JvmOverloads constructor(
    val slots: Int,
    val mapIdBase: Int,
    val columns: Int = StreamFormat.slotColumns(slots),
) {

    init {
        require(slots in 1..StreamFormat.MAX_SLOTS) { "slots $slots out of range" }
    }

    private val words = Array(slots) { IntArray(MapPalette.MAP_WORDS) }

    private val staged = Array(slots) { ByteArray(MapPalette.MAP_WORDS) }

    private val sent = arrayOfNulls<ByteArray>(slots)

    val rows: Int = (slots + columns - 1) / columns

    fun mapId(slot: Int): Int = mapIdBase + slot

    fun slot(index: Int): IntArray = words[index]

    fun index(x: Int, y: Int): Int = y * MapPalette.MAP_EDGE + x

    fun header(slot: Int, stream: Int, serial: Int, regionX: Int, regionY: Int, active: Boolean = true) {
        StreamFormat.writeHeader(
            words[slot], slot, stream, serial, regionX, regionY, columns, rows,
            if (active) StreamFormat.FLAG_ACTIVE else 0,
        )
    }

    fun fill(slot: Int, word: Int) {
        val target = words[slot]
        for (i in StreamFormat.HEADER_WORDS until target.size) target[i] = word
    }

    fun ramp(slot: Int) {
        val target = words[slot]
        for (i in StreamFormat.HEADER_WORDS until target.size) target[i] = i % MapPalette.WORDS
    }

    fun invalidate() {
        for (slot in 0 until slots) sent[slot] = null
    }

    fun flush(sink: StreamSink): Int {
        var bytes = 0
        for (slot in 0 until slots) bytes += flushSlot(slot, sink)
        return bytes
    }

    fun flushSlot(slot: Int, sink: StreamSink): Int {
        val source = words[slot]
        val next = staged[slot]
        for (i in source.indices) next[i] = MapPalette.encode(source[i])

        val previous = sent[slot]
        if (previous == null) {
            sink.mapData(mapId(slot), 0, 0, MapPalette.MAP_EDGE, MapPalette.MAP_EDGE, next.copyOf())
            sent[slot] = next.copyOf()
            return next.size
        }

        var bytes = 0
        var row = 0
        while (row < MapPalette.MAP_EDGE) {
            if (!rowDiffers(next, previous, row)) {
                row++
                continue
            }
            var end = row
            while (end + 1 < MapPalette.MAP_EDGE && rowDiffers(next, previous, end + 1)) end++
            val from = row * MapPalette.MAP_EDGE
            val count = (end - row + 1) * MapPalette.MAP_EDGE
            sink.mapData(
                mapId(slot), 0, row, MapPalette.MAP_EDGE, end - row + 1,
                next.copyOfRange(from, from + count),
            )
            next.copyInto(previous, from, from, from + count)
            bytes += count
            row = end + 1
        }
        return bytes
    }

    private fun rowDiffers(next: ByteArray, previous: ByteArray, row: Int): Boolean {
        val from = row * MapPalette.MAP_EDGE
        for (i in from until from + MapPalette.MAP_EDGE) {
            if (next[i] != previous[i]) return true
        }
        return false
    }
}
