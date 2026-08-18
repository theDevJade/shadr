/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.stream.MapPalette
import dev.shadr.core.stream.StreamBlocks
import dev.shadr.core.stream.StreamFormat
import dev.shadr.core.stream.StreamGeometry
import dev.shadr.core.stream.StreamSink
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamBlocksTest {

    private fun scene(width: Int, height: Int): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val r = (x * 255 / (width - 1))
        val g = (y * 255 / (height - 1))
        val b = ((x / 16 + y / 16) % 2) * 200 + 30
        (r shl 16) or (g shl 8) or b
    }

    @Test
    fun `the block payload fits inside a slot without touching the header`() {
        assertTrue(StreamBlocks.fits(), "the block grid spills past the slot")
        assertTrue(StreamBlocks.PAYLOAD_BASE >= StreamFormat.HEADER_WORDS)
        val used = StreamBlocks.BLOCKS_X * StreamBlocks.BLOCKS_Y * StreamBlocks.WORDS_PER_BLOCK
        assertTrue(used <= MapPalette.MAP_WORDS - StreamBlocks.PAYLOAD_BASE, "$used words do not fit")
    }

    @Test
    fun `every 32 bit half round trips through five seven bit words`() {
        val out = IntArray(5)
        for (value in listOf(0, -1, 0x7FFFFFFF, 0x12345678, 0xF0F0F0F0.toInt(), 0x80000000.toInt())) {
            StreamBlocks.split32(value, out, 0)
            for (word in out.take(4)) assertTrue(word in 0 until MapPalette.WORDS, "word $word out of range")
            assertTrue(out[4] in 0..15, "the high word must carry only four bits")
            assertEquals(value, StreamBlocks.join32(out[0], out[1], out[2], out[3], out[4]), "value $value")
        }
    }

    @Test
    fun `a block coded image decodes back close to the source`() {
        val geometry = StreamGeometry.DEFAULT
        val channel = geometry.channel()
        val width = StreamBlocks.width(channel.columns)
        val height = StreamBlocks.height(channel.rows)
        val source = scene(width, height)

        geometry.apply(channel, stream = 0, serial = 1)
        StreamBlocks.encode(channel, source, width, height)
        val decoded = StreamBlocks.decode(channel, width, height)

        var total = 0.0
        for (i in source.indices) {
            for (shift in listOf(16, 8, 0)) {
                val a = (source[i] shr shift) and 0xFF
                val b = (decoded[i] shr shift) and 0xFF
                total += ((a - b) * (a - b)).toDouble()
            }
        }
        val mse = total / (source.size * 3)
        val psnr = 10.0 * kotlin.math.log10(255.0 * 255.0 / mse.coerceAtLeast(1e-9))
        assertTrue(psnr > 30.0, "block coding only reached %.1f dB".format(psnr))
    }

    @Test
    fun `encoding leaves the header intact and every word legal`() {
        val geometry = StreamGeometry.DEFAULT
        val channel = geometry.channel()
        val width = StreamBlocks.width(channel.columns)
        val height = StreamBlocks.height(channel.rows)

        geometry.apply(channel, stream = 0, serial = 5)
        StreamBlocks.encode(channel, scene(width, height), width, height)

        for (slot in 0 until channel.slots) {
            val words = channel.slot(slot)
            assertTrue(StreamFormat.readHeaderMagic(words), "slot $slot lost its magic")
            for (i in words.indices) {
                assertTrue(words[i] in 0 until MapPalette.WORDS, "slot $slot word $i is ${words[i]}")
            }
        }
    }

    @Test
    fun `a block coded frame survives the wire`() {
        val geometry = StreamGeometry.DEFAULT
        val channel = geometry.channel()
        val width = StreamBlocks.width(channel.columns)
        val height = StreamBlocks.height(channel.rows)

        geometry.apply(channel, stream = 0, serial = 1)
        StreamBlocks.encode(channel, scene(width, height), width, height)
        val expected = StreamBlocks.decode(channel, width, height)

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

        val received = geometry.channel()
        for (slot in 0 until channel.slots) {
            val canvas = canvases.getValue(received.mapId(slot))
            val words = received.slot(slot)
            for (i in words.indices) words[i] = MapPalette.decode(canvas[i])
        }
        assertEquals(expected.toList(), StreamBlocks.decode(received, width, height).toList())
    }

    @Test
    fun `a static frame costs nothing to resend`() {
        val geometry = StreamGeometry.DEFAULT
        val channel = geometry.channel()
        val width = StreamBlocks.width(channel.columns)
        val height = StreamBlocks.height(channel.rows)
        val frame = scene(width, height)

        geometry.apply(channel, stream = 0, serial = 1)
        StreamBlocks.encode(channel, frame, width, height)
        var sent = 0
        val sink = StreamSink { _, _, _, _, _, colors -> sent += colors.size }
        channel.flush(sink)
        assertTrue(sent > 0, "the first frame sent nothing")

        sent = 0
        StreamBlocks.encode(channel, frame, width, height)
        geometry.apply(channel, stream = 0, serial = 1)
        channel.flush(sink)
        assertEquals(0, sent, "an unchanged frame still cost $sent bytes")
    }

    @Test
    fun `encoding a frame keeps up with twenty frames a second`() {
        val geometry = StreamGeometry.DEFAULT
        val channel = geometry.channel()
        val width = StreamBlocks.width(channel.columns)
        val height = StreamBlocks.height(channel.rows)
        val base = scene(width, height)
        val frames = List(4) { f -> IntArray(base.size) { i -> base[i] + f } }

        StreamBlocks.encode(channel, frames[0], width, height)

        val started = System.nanoTime()
        val runs = 8
        repeat(runs) { StreamBlocks.encode(channel, frames[it % frames.size], width, height) }
        val perFrameMillis = (System.nanoTime() - started) / 1e6 / runs

        assertTrue(
            perFrameMillis < 50.0,
            "block coding a ${width}x$height frame took %.1f ms, which cannot hold 20 fps".format(perFrameMillis),
        )
        assertTrue(abs(perFrameMillis) >= 0.0)
        println("block coding ${width}x$height: %.1f ms/frame".format(perFrameMillis))
    }
}
