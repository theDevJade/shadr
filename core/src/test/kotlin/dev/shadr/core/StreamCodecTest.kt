/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.stream.MapPalette
import dev.shadr.core.stream.StreamChannel
import dev.shadr.core.stream.StreamCodec
import dev.shadr.core.stream.StreamFormat
import dev.shadr.core.stream.StreamSink
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamCodecTest {

    private fun geometry(width: Int = 320, height: Int = 192, pool: Int = 256) =
        StreamCodec.Geometry(width, height, pool)

    private fun channelFor(g: StreamCodec.Geometry) = StreamChannel(g.slots, 0)

    private fun pan(width: Int, height: Int, offset: Int): IntArray = IntArray(width * height) { i ->
        val x = (i % width + offset)
        val y = i / width
        val r = (x * 3) and 0xFF
        val g = (y * 5) and 0xFF
        val b = ((x / 8 + y / 8) % 2) * 180 + 40
        (r shl 16) or (g shl 8) or b
    }

    private fun psnr(a: IntArray, b: IntArray): Double {
        var total = 0.0
        for (i in a.indices) {
            for (shift in listOf(16, 8, 0)) {
                val d = ((a[i] shr shift) and 0xFF) - ((b[i] shr shift) and 0xFF)
                total += (d * d).toDouble()
            }
        }
        val mse = total / (a.size * 3)
        return 10.0 * log10(255.0 * 255.0 / mse.coerceAtLeast(1e-9))
    }

    @Test
    fun `the arena addresses every word inside a slot payload`() {
        val g = geometry()
        val channel = channelFor(g)
        for (address in listOf(0, 1, StreamCodec.SLOT_PAYLOAD - 1, StreamCodec.SLOT_PAYLOAD, g.totalWords - 1)) {
            StreamCodec.write(channel, address, 77)
            assertEquals(77, StreamCodec.read(channel, address), "address $address did not round trip")
        }
        for (slot in 0 until g.slots) {
            for (i in 0 until StreamFormat.HEADER_WORDS) {
                assertEquals(0, channel.slot(slot)[i], "the arena wrote into slot $slot's header")
            }
        }
    }

    @Test
    fun `an unchanged frame codes as all skip and sends nothing`() {
        val g = geometry()
        val channel = channelFor(g)
        val frame = pan(g.frameWidth, g.frameHeight, 0)
        val reconstruction = IntArray(frame.size)

        StreamCodec.encode(channel, frame, reconstruction, g, StreamCodec.Options(refreshPerFrame = 0), 0)
        for (slot in 0 until g.slots) channel.header(slot, 0, 1, 0, 0)
        var sent = 0
        val sink = StreamSink { _, _, _, _, _, colors -> sent += colors.size }
        channel.flush(sink)
        assertTrue(sent > 0, "the first frame sent nothing")

        var settled = 0
        var stats = StreamCodec.encode(channel, frame, reconstruction, g, StreamCodec.Options(), 1)
        while (settled < 16) {
            settled++
            for (slot in 0 until g.slots) channel.header(slot, 0, 1, 0, 0)
            channel.flush(sink)
            sent = 0
            stats = StreamCodec.encode(channel, frame, reconstruction, g, StreamCodec.Options(), settled + 1)
            for (slot in 0 until g.slots) channel.header(slot, 0, 1, 0, 0)
            channel.flush(sink)
            if (sent == 0) break
        }
        assertEquals(0, sent, "a static frame never stopped costing bytes (last $stats after $settled rounds)")
    }

    @Test
    fun `a pure translation codes as motion vectors rather than intra`() {
        val g = geometry()
        val channel = channelFor(g)
        val reconstruction = IntArray(g.frameWidth * g.frameHeight)

        StreamCodec.encode(channel, pan(g.frameWidth, g.frameHeight, 0), reconstruction, g, StreamCodec.Options(refreshPerFrame = g.cus), 0)
        val stats = StreamCodec.encode(
            channel, pan(g.frameWidth, g.frameHeight, 4), reconstruction, g,
            StreamCodec.Options(refreshPerFrame = 0), 1,
        )

        assertTrue(
            stats.mc + stats.skip > stats.total * 8 / 10,
            "a 4px pan should be mostly predicted, got $stats",
        )
    }

    @Test
    fun `the reference decoder reproduces the encoder's reconstruction exactly`() {
        val g = geometry()
        val channel = channelFor(g)
        val encoderView = IntArray(g.frameWidth * g.frameHeight)
        val decoderView = IntArray(g.frameWidth * g.frameHeight)

        for (frame in 0 until 6) {
            val source = pan(g.frameWidth, g.frameHeight, frame * 3)
            val options = StreamCodec.Options(refreshPerFrame = if (frame == 0) g.cus else 32)
            StreamCodec.encode(channel, source, encoderView, g, options, frame)
            replay(channel, decoderView, g)
            assertEquals(
                encoderView.toList(), decoderView.toList(),
                "frame $frame: the decoder drifted from the encoder",
            )
        }
    }

    private fun replay(channel: StreamChannel, view: IntArray, g: StreamCodec.Geometry) {
        val previous = view.copyOf()
        val splitX = IntArray(g.cus * 4)
        val splitY = IntArray(g.cus * 4)
        for (index in 0 until g.cus) {
            val base = index * StreamCodec.PLANE_WORDS
            val mode = StreamCodec.read(channel, base)
            val dx = StreamCodec.read(channel, base + 1) - StreamCodec.MV_BIAS
            val dy = StreamCodec.read(channel, base + 2) - StreamCodec.MV_BIAS
            val entry = StreamCodec.read(channel, base + 3) or (StreamCodec.read(channel, base + 4) shl 7)
            if (mode == StreamCodec.MODE_SPLIT || mode == StreamCodec.MODE_RESIDUAL) {
                val at = g.splitIndex(index)
                for (q in 0 until 4) {
                    splitX[index * 4 + q] = StreamCodec.read(channel, at + q * 2) - StreamCodec.MV_BIAS
                    splitY[index * 4 + q] = StreamCodec.read(channel, at + q * 2 + 1) - StreamCodec.MV_BIAS
                }
            }
            StreamCodec.reconstructUnit(
                channel, previous, view, g.frameWidth, g, index, mode, dx, dy, splitX, splitY, entry,
            )
        }
    }

    @Test
    fun `staggered refresh eventually paints every unit`() {
        val g = geometry()
        val channel = channelFor(g)
        val view = IntArray(g.frameWidth * g.frameHeight)
        val source = pan(g.frameWidth, g.frameHeight, 0)
        val perFrame = 32

        var frame = 0
        while (frame * perFrame < g.cus) {
            StreamCodec.encode(channel, source, view, g, StreamCodec.Options(refreshPerFrame = perFrame), frame)
            frame++
        }
        assertTrue(psnr(source, view) > 25.0, "after a full refresh cycle the picture is only %.1f dB".format(psnr(source, view)))
    }

    @Test
    fun `every written word is a legal channel symbol`() {
        val g = geometry()
        val channel = channelFor(g)
        val view = IntArray(g.frameWidth * g.frameHeight)
        StreamCodec.encode(channel, pan(g.frameWidth, g.frameHeight, 0), view, g, StreamCodec.Options(refreshPerFrame = g.cus), 0)
        for (slot in 0 until g.slots) {
            for (word in channel.slot(slot)) {
                assertTrue(word in 0 until MapPalette.WORDS, "word $word is outside the channel alphabet")
            }
        }
    }

    @Test
    fun `the pool cannot be overrun`() {
        val g = geometry(pool = 8)
        val channel = channelFor(g)
        val view = IntArray(g.frameWidth * g.frameHeight)
        val stats = StreamCodec.encode(
            channel, pan(g.frameWidth, g.frameHeight, 0), view, g,
            StreamCodec.Options(refreshPerFrame = g.cus), 0,
        )
        assertTrue(stats.poolUsed <= 8, "pool used ${stats.poolUsed} of 8")
        assertEquals(stats.total, stats.skip + stats.mc + stats.intra)
    }

    @Test
    fun `the generated glsl carries the geometry the encoder used`() {
        val g = geometry()
        val glsl = StreamCodec.glsl(g)
        fun define(name: String) = Regex("#define\\s+$name\\s+(\\S+)").find(glsl)?.groupValues?.get(1)
        assertEquals(g.cusX.toString(), define("SHADR_CU_COLUMNS"))
        assertEquals(g.cusY.toString(), define("SHADR_CU_ROWS"))
        assertEquals(g.poolBase.toString(), define("SHADR_CU_POOL_BASE"))
        assertEquals(StreamCodec.SLOT_PAYLOAD.toString(), define("SHADR_CU_SLOT_PAYLOAD"))
        assertEquals(StreamCodec.MV_BIAS.toString(), define("SHADR_CU_MV_BIAS"))
    }
}
