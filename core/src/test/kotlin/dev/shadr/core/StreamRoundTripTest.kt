/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.stream.MapPalette
import dev.shadr.core.stream.StreamFormat
import dev.shadr.core.stream.StreamGeometry
import dev.shadr.core.stream.StreamLayout
import dev.shadr.core.stream.StreamSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamRoundTripTest {

    private class Client(val width: Int, val height: Int) {
        val maps = HashMap<Int, ByteArray>()
        val main = IntArray(width * height)

        fun applyPatch(mapId: Int, startX: Int, startY: Int, w: Int, h: Int, colors: ByteArray) {
            val canvas = maps.getOrPut(mapId) { ByteArray(MapPalette.MAP_WORDS) }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    canvas[startX + x + (startY + y) * MapPalette.MAP_EDGE] = colors[x + y * w]
                }
            }
        }

        fun drawSlot(mapId: Int, slotOrigin: StreamLayout.Origin) {
            val canvas = maps[mapId] ?: return
            for (localY in 0 until MapPalette.MAP_EDGE) {
                for (localX in 0 until MapPalette.MAP_EDGE) {
                    val pixelX = slotOrigin.x + localX
                    val pixelY = slotOrigin.y + localY
                    val word = StreamLayout.wordAt(slotOrigin, pixelX, pixelY)
                    val packed = canvas[word.y * MapPalette.MAP_EDGE + word.x].toInt() and 0xFF
                    main[pixelY * width + pixelX] = MapPalette.argb(packed)
                }
            }
        }

        fun readWord(pixelX: Int, pixelY: Int): Int {
            val argb = main[pixelY * width + pixelX]
            return MapPalette.redToWord[argb shr 16 and 0xFF]
        }
    }

    private fun expectedWord(index: Int, slot: Int, columns: Int, rows: Int): Int? = when {
        index >= StreamFormat.HEADER_WORDS -> index % MapPalette.WORDS
        index == StreamFormat.W_MAGIC_LOW -> StreamFormat.MAGIC_LOW
        index == StreamFormat.W_MAGIC_HIGH -> StreamFormat.MAGIC_HIGH
        index == StreamFormat.W_VERSION -> StreamFormat.VERSION
        index == StreamFormat.W_SLOT -> slot
        index == StreamFormat.W_SLOT_COLUMNS -> columns
        index == StreamFormat.W_SLOT_ROWS -> rows
        index == StreamFormat.W_FLAGS -> StreamFormat.FLAG_ACTIVE
        else -> null
    }

    private fun run(geometry: StreamGeometry, width: Int, height: Int) {
        val client = Client(width, height)
        val channel = geometry.channel()
        for (slot in 0 until geometry.slots) channel.ramp(slot)
        geometry.apply(channel, stream = 0, serial = 1)

        val sink = StreamSink { mapId, startX, startY, w, h, colors ->
            client.applyPatch(mapId, startX, startY, w, h, colors)
        }
        channel.flush(sink)

        val region = StreamLayout.regionOrigin(
            width.toDouble(), height.toDouble(),
            geometry.regionX.toDouble(), geometry.regionY.toDouble(),
            geometry.columns, geometry.rows,
        )
        for (slot in 0 until geometry.slots) {
            client.drawSlot(channel.mapId(slot), StreamLayout.slotOrigin(region, slot, geometry.columns))
        }

        var checked = 0
        var skipped = 0
        for (slot in 0 until geometry.slots) {
            val origin = StreamLayout.slotOrigin(region, slot, geometry.columns)
            for (localY in 0 until MapPalette.MAP_EDGE) {
                for (localX in 0 until MapPalette.MAP_EDGE) {
                    val pixelX = origin.x + localX
                    val pixelY = origin.y + localY
                    val word = StreamLayout.wordAt(origin, pixelX, pixelY)
                    val index = word.y * MapPalette.MAP_EDGE + word.x
                    val expected = expectedWord(index, slot, geometry.columns, geometry.rows)
                    if (expected == null) {
                        skipped++
                        continue
                    }
                    assertEquals(
                        expected, client.readWord(pixelX, pixelY),
                        "${width}x$height slot $slot word $index would probe red",
                    )
                    checked++
                }
            }
        }
        assertEquals(geometry.slots * (MapPalette.MAP_WORDS - 9), checked, "wrong number of verified words")
        assertEquals(geometry.slots * 9, skipped, "wrong number of don't-care header words")
    }

    @Test
    fun `the probe would read solid green at 1080p`() {
        run(StreamGeometry.DEFAULT.copy(probe = true), 1920, 1080)
    }

    @Test
    fun `the probe would read solid green with the region moved into the page`() {
        run(StreamGeometry.DEFAULT.copy(probe = true, regionX = 640, regionY = 200), 1920, 1080)
    }

    @Test
    fun `the probe would read solid green at 4k and on an odd window`() {
        run(StreamGeometry.DEFAULT.copy(probe = true), 3840, 2160)
        run(StreamGeometry.DEFAULT.copy(probe = true), 1366, 768)
    }

    @Test
    fun `the broadcast profile round trips too`() {
        run(StreamGeometry.BROADCAST.copy(probe = true), 1920, 1080)
    }

    @Test
    fun `a window too small for the region still places every slot on screen`() {
        val geometry = StreamGeometry.DEFAULT.copy(slots = StreamLayout.maxSlots(854, 480), probe = true)
        assertTrue(geometry.slots >= 1, "854x480 should still hold at least one slot")
        run(geometry, 854, 480)
    }

    @Test
    fun `a corrupted byte is the only thing that makes the probe go red`() {
        val geometry = StreamGeometry.DEFAULT.copy(slots = 1, probe = true)
        val client = Client(1920, 1080)
        val channel = geometry.channel()
        channel.ramp(0)
        geometry.apply(channel, stream = 0, serial = 1)
        channel.flush(
            StreamSink { mapId, startX, startY, w, h, colors ->
                client.applyPatch(mapId, startX, startY, w, h, colors)
            },
        )

        val region = StreamLayout.regionOrigin(1920.0, 1080.0, 0.0, 0.0, geometry.columns, geometry.rows)
        val origin = StreamLayout.slotOrigin(region, 0, geometry.columns)
        val victim = 4097
        assertTrue(victim % MapPalette.WORDS != 0, "the test needs a word whose ramp value is not zero")
        client.maps.getValue(channel.mapId(0))[victim] = MapPalette.encode(0)
        client.drawSlot(channel.mapId(0), origin)

        val word = StreamLayout.Word(victim % MapPalette.MAP_EDGE, victim / MapPalette.MAP_EDGE)
        val pixel = StreamLayout.pixelFor(origin, word)
        assertEquals(0, client.readWord(pixel.x, pixel.y), "the corrupted word should decode as the injected value")
        assertEquals(
            victim % MapPalette.WORDS, MapPalette.decode(MapPalette.encode(victim % MapPalette.WORDS)),
            "the ramp value itself must still round trip",
        )
    }
}
