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
import dev.shadr.core.stream.StreamGeometry
import dev.shadr.core.stream.StreamImage
import dev.shadr.core.stream.StreamSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamImageTest {

    private fun scene(width: Int, height: Int): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val r = x * 255 / (width - 1)
        val g = y * 255 / (height - 1)
        val b = (x xor y) and 0xFF
        (r shl 16) or (g shl 8) or b
    }

    @Test
    fun `a pixel survives the seven bit word split exactly`() {
        for (value in listOf(0x000000, 0xFFFFFF, 0x123456, 0x7F7F7F, 0x010203, 0xABCDEF)) {
            val parts = StreamImage.split(value)
            for (word in parts) assertTrue(word in 0 until MapPalette.WORDS, "word $word is out of channel range")
            assertEquals(value, StreamImage.join(parts[0], parts[1], parts[2], parts[3]), "value $value did not survive")
        }
    }

    @Test
    fun `every 24 bit colour round trips`() {
        var value = 0
        while (value <= 0xFFFFFF) {
            val p = StreamImage.split(value)
            assertEquals(value, StreamImage.join(p[0], p[1], p[2], p[3]))
            value += 7919
        }
    }

    @Test
    fun `the payload never collides with the slot header`() {
        assertTrue(StreamImage.PAYLOAD_BASE >= StreamFormat.HEADER_WORDS, "payload would overwrite the header")
        val last = StreamImage.wordIndex(StreamImage.TILE_WIDTH - 1, StreamImage.TILE_HEIGHT - 1) +
            StreamImage.WORDS_PER_PIXEL - 1
        assertTrue(last < MapPalette.MAP_WORDS, "the last pixel spills past the slot at word $last")
    }

    @Test
    fun `an image round trips through the channel unchanged`() {
        val geometry = StreamGeometry.DEFAULT
        val channel = geometry.channel()
        val width = StreamImage.width(channel.columns)
        val height = StreamImage.height(channel.rows)
        val source = scene(width, height)

        geometry.apply(channel, stream = 0, serial = 1)
        StreamImage.encode(channel, source, width, height)

        val decoded = StreamImage.decode(channel, width, height)
        assertEquals(source.toList(), decoded.toList(), "the image changed inside the channel")
    }

    @Test
    fun `encoding leaves the header readable`() {
        val geometry = StreamGeometry.DEFAULT.copy(regionX = 320, regionY = 180)
        val channel = geometry.channel()
        val width = StreamImage.width(channel.columns)
        val height = StreamImage.height(channel.rows)

        geometry.apply(channel, stream = 0, serial = 77)
        StreamImage.encode(channel, scene(width, height), width, height)

        for (slot in 0 until channel.slots) {
            val words = channel.slot(slot)
            assertTrue(StreamFormat.readHeaderMagic(words), "slot $slot lost its magic")
            assertEquals(77, StreamFormat.readSerial(words))
            assertEquals(320, StreamFormat.readRegionX(words))
            assertEquals(slot, words[StreamFormat.W_SLOT])
        }
    }

    @Test
    fun `an encoded image survives the wire as map colours`() {
        val geometry = StreamGeometry.DEFAULT
        val channel = geometry.channel()
        val width = StreamImage.width(channel.columns)
        val height = StreamImage.height(channel.rows)
        val source = scene(width, height)

        geometry.apply(channel, stream = 0, serial = 1)
        StreamImage.encode(channel, source, width, height)

        val canvases = HashMap<Int, ByteArray>()
        channel.flush(
            StreamSink { mapId, startX, startY, w, h, colors ->
                val canvas = canvases.getOrPut(mapId) { ByteArray(MapPalette.MAP_WORDS) }
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        canvas[startX + x + (startY + y) * MapPalette.MAP_EDGE] = colors[x + y * w]
                    }
                }
            },
        )

        val received = StreamChannel(channel.slots, channel.mapIdBase)
        for (slot in 0 until channel.slots) {
            val canvas = canvases.getValue(received.mapId(slot))
            val words = received.slot(slot)
            for (i in words.indices) words[i] = MapPalette.decode(canvas[i])
        }
        assertEquals(source.toList(), StreamImage.decode(received, width, height).toList())
    }

    @Test
    fun `the generated glsl matches the kotlin layout`() {
        val glsl = StreamImage.glsl()
        fun define(name: String) = Regex("#define\\s+$name\\s+(\\S+)").find(glsl)?.groupValues?.get(1)
        assertEquals(StreamImage.WORDS_PER_PIXEL.toString(), define("SHADR_IMAGE_WORDS_PER_PIXEL"))
        assertEquals(StreamImage.PAYLOAD_BASE.toString(), define("SHADR_IMAGE_PAYLOAD_BASE"))
        assertEquals(StreamImage.TILE_WIDTH.toString(), define("SHADR_IMAGE_TILE_WIDTH"))
        assertEquals(StreamImage.TILE_HEIGHT.toString(), define("SHADR_IMAGE_TILE_HEIGHT"))
    }
}
