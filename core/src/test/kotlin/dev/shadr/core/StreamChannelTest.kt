/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.stream.MapPalette
import dev.shadr.core.stream.StreamChannel
import dev.shadr.core.stream.StreamFormat
import dev.shadr.core.stream.StreamSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamChannelTest {

    private class Patch(
        val mapId: Int,
        val startX: Int,
        val startY: Int,
        val width: Int,
        val height: Int,
        val colors: ByteArray,
    )

    private class Recorder : StreamSink {
        val patches = mutableListOf<Patch>()

        override fun mapData(mapId: Int, startX: Int, startY: Int, width: Int, height: Int, colors: ByteArray) {
            patches += Patch(mapId, startX, startY, width, height, colors)
        }

        fun bytes(): Int = patches.sumOf { it.colors.size }

        fun clear() = patches.clear()
    }

    private fun applied(patches: List<Patch>, mapId: Int): ByteArray {
        val canvas = ByteArray(MapPalette.MAP_WORDS)
        for (patch in patches.filter { it.mapId == mapId }) {
            for (y in 0 until patch.height) {
                for (x in 0 until patch.width) {
                    canvas[patch.startX + x + (patch.startY + y) * MapPalette.MAP_EDGE] =
                        patch.colors[x + y * patch.width]
                }
            }
        }
        return canvas
    }

    @Test
    fun `the first flush sends every slot in full`() {
        val channel = StreamChannel(slots = 4, mapIdBase = 30_000)
        for (slot in 0 until 4) {
            channel.header(slot, stream = 0, serial = 1, regionX = 0, regionY = 0)
            channel.ramp(slot)
        }
        val sink = Recorder()
        val bytes = channel.flush(sink)

        assertEquals(4, sink.patches.size)
        assertEquals(4 * MapPalette.MAP_WORDS, bytes)
        for (slot in 0 until 4) {
            assertEquals(30_000 + slot, sink.patches[slot].mapId)
            assertEquals(MapPalette.MAP_EDGE, sink.patches[slot].height)
        }
    }

    @Test
    fun `an unchanged slot costs nothing on the wire`() {
        val channel = StreamChannel(slots = 2, mapIdBase = 0)
        for (slot in 0 until 2) {
            channel.header(slot, 0, 1, 0, 0)
            channel.fill(slot, 7)
        }
        val sink = Recorder()
        channel.flush(sink)
        sink.clear()

        assertEquals(0, channel.flush(sink))
        assertTrue(sink.patches.isEmpty(), "a static channel still sent ${sink.patches.size} patches")
    }

    @Test
    fun `only the rows that changed are sent`() {
        val channel = StreamChannel(slots = 1, mapIdBase = 0)
        channel.header(0, 0, 1, 0, 0)
        channel.fill(0, 3)
        val sink = Recorder()
        channel.flush(sink)
        sink.clear()

        val slot = channel.slot(0)
        for (x in 0 until MapPalette.MAP_EDGE) {
            slot[channel.index(x, 40)] = 11
            slot[channel.index(x, 41)] = 11
            slot[channel.index(x, 90)] = 12
        }

        val bytes = channel.flush(sink)
        assertEquals(2, sink.patches.size, "two disjoint runs should be two patches")
        assertEquals(40, sink.patches[0].startY)
        assertEquals(2, sink.patches[0].height)
        assertEquals(90, sink.patches[1].startY)
        assertEquals(1, sink.patches[1].height)
        assertEquals(3 * MapPalette.MAP_EDGE, bytes)
    }

    @Test
    fun `patches replay into exactly the word image the encoder staged`() {
        val channel = StreamChannel(slots = 1, mapIdBase = 500)
        channel.header(0, 3, 9001, 640, 360)
        for (i in StreamFormat.HEADER_WORDS until MapPalette.MAP_WORDS) {
            channel.slot(0)[i] = (i * 31 + 7) % MapPalette.WORDS
        }
        val sink = Recorder()
        channel.flush(sink)

        val canvas = applied(sink.patches, 500)
        for (i in 0 until MapPalette.MAP_WORDS) {
            assertEquals(channel.slot(0)[i], MapPalette.decode(canvas[i]), "word $i survived the patch incorrectly")
        }
        assertTrue(StreamFormat.readHeaderMagic(IntArray(StreamFormat.HEADER_WORDS) { MapPalette.decode(canvas[it]) }))
    }

    @Test
    fun `incremental patches keep the client image in step with the server`() {
        val channel = StreamChannel(slots = 1, mapIdBase = 0)
        channel.header(0, 0, 0, 0, 0)
        channel.fill(0, 0)
        val sink = Recorder()
        channel.flush(sink)
        val client = applied(sink.patches, 0)

        var serial = 1
        repeat(12) { frame ->
            sink.clear()
            channel.header(0, 0, serial++, 0, 0)
            val slot = channel.slot(0)
            val row = frame * 7 % 120 + 8
            for (x in 0 until MapPalette.MAP_EDGE) slot[channel.index(x, row)] = (frame * 13 + x) % MapPalette.WORDS
            channel.flushSlot(0, sink)

            for (patch in sink.patches) {
                for (y in 0 until patch.height) {
                    for (x in 0 until patch.width) {
                        client[patch.startX + x + (patch.startY + y) * MapPalette.MAP_EDGE] =
                            patch.colors[x + y * patch.width]
                    }
                }
            }

            for (i in 0 until MapPalette.MAP_WORDS) {
                assertEquals(slot[i], MapPalette.decode(client[i]), "frame $frame word $i drifted")
            }
        }
    }

    @Test
    fun `invalidate forces a full resend so a late joiner converges at once`() {
        val channel = StreamChannel(slots = 1, mapIdBase = 0)
        channel.header(0, 0, 1, 0, 0)
        channel.fill(0, 5)
        val sink = Recorder()
        channel.flush(sink)
        sink.clear()

        assertEquals(0, channel.flush(sink))
        channel.invalidate()
        assertEquals(MapPalette.MAP_WORDS, channel.flush(sink))
        assertEquals(1, sink.patches.size)
        assertEquals(MapPalette.MAP_EDGE, sink.patches[0].height)
    }

    @Test
    fun `every staged byte is a legal opaque map colour`() {
        val channel = StreamChannel(slots = 1, mapIdBase = 0)
        channel.header(0, 0, 1, 1919, 1079)
        channel.ramp(0)
        val sink = Recorder()
        channel.flush(sink)

        for (patch in sink.patches) {
            for (color in patch.colors) {
                val packed = color.toInt() and 0xFF
                assertTrue(MapPalette.argb(packed) ushr 24 != 0, "byte $packed renders transparent")
                assertTrue(MapPalette.decode(color) != MapPalette.NO_WORD, "byte $packed is not in the alphabet")
            }
        }
    }

    @Test
    fun `slot map ids are contiguous from the configured base`() {
        val channel = StreamChannel(slots = 16, mapIdBase = 32_000)
        assertEquals(32_000, channel.mapId(0))
        assertEquals(32_015, channel.mapId(15))
        assertEquals(4, channel.columns)
        assertEquals(4, channel.rows)
    }
}
