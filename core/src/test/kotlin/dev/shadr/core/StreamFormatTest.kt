/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.stream.MapPalette
import dev.shadr.core.stream.StreamFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamFormatTest {

    private fun header(
        slot: Int = 0,
        stream: Int = 0,
        serial: Int = 0,
        regionX: Int = 0,
        regionY: Int = 0,
        slots: Int = 16,
        flags: Int = StreamFormat.FLAG_ACTIVE,
    ): IntArray {
        val words = IntArray(MapPalette.MAP_WORDS)
        StreamFormat.writeHeader(words, slot, stream, serial, regionX, regionY, slots, flags)
        return words
    }

    @Test
    fun `every header word fits the seven bit channel`() {
        val words = header(slot = 63, stream = 127, serial = 16383, regionX = 1919, regionY = 1079, slots = 64)
        for (i in 0 until StreamFormat.HEADER_WORDS) {
            assertTrue(words[i] in 0 until MapPalette.WORDS, "header word $i is ${words[i]}")
        }
    }

    @Test
    fun `split and join round trip the whole fourteen bit range`() {
        for (value in 0 until StreamFormat.SERIAL_MODULUS) {
            val low = StreamFormat.splitLow(value)
            val high = StreamFormat.splitHigh(value)
            assertEquals(value, StreamFormat.join(low, high), "value $value did not round trip")
        }
    }

    @Test
    fun `headers are readable back`() {
        val words = header(slot = 5, stream = 3, serial = 9001, regionX = 640, regionY = 360, slots = 16)
        assertTrue(StreamFormat.readHeaderMagic(words))
        assertEquals(9001, StreamFormat.readSerial(words))
        assertEquals(640, StreamFormat.readRegionX(words))
        assertEquals(360, StreamFormat.readRegionY(words))
        assertEquals(5, words[StreamFormat.W_SLOT])
        assertEquals(3, words[StreamFormat.W_STREAM])
        assertEquals(4, words[StreamFormat.W_SLOT_COLUMNS])
        assertEquals(4, words[StreamFormat.W_SLOT_ROWS])
    }

    @Test
    fun `an all zero slot never passes the magic check`() {
        assertFalse(StreamFormat.readHeaderMagic(IntArray(MapPalette.MAP_WORDS)))
    }

    @Test
    fun `the serial wraps instead of overflowing the channel`() {
        val words = header(serial = StreamFormat.SERIAL_MODULUS + 7)
        assertEquals(7, StreamFormat.readSerial(words))
    }

    @Test
    fun `slot grids stay square and cover every slot`() {
        for (slots in 1..StreamFormat.MAX_SLOTS) {
            val columns = StreamFormat.slotColumns(slots)
            val rows = StreamFormat.slotRows(slots)
            assertTrue(columns * rows >= slots, "$slots slots do not fit ${columns}x$rows")
            assertTrue((columns - 1) * rows < slots, "$slots slots waste a whole column in ${columns}x$rows")
        }
        assertEquals(4, StreamFormat.slotColumns(16))
        assertEquals(512, StreamFormat.regionWidth(16))
        assertEquals(512, StreamFormat.regionHeight(16))
    }

    @Test
    fun `the generated glsl agrees with the kotlin constants`() {
        val glsl = StreamFormat.glsl()
        fun define(name: String): String =
            Regex("#define\\s+$name\\s+(\\S+)").find(glsl)?.groupValues?.get(1)
                ?: error("$name is not defined in the generated stream header")

        assertEquals(StreamFormat.VERSION.toString(), define("SHADR_STREAM_VERSION"))
        assertEquals(StreamFormat.MAGIC_LOW.toString(), define("SHADR_STREAM_MAGIC_LOW"))
        assertEquals(StreamFormat.MAGIC_HIGH.toString(), define("SHADR_STREAM_MAGIC_HIGH"))
        assertEquals(StreamFormat.W_SLOT.toString(), define("SHADR_STREAM_W_SLOT"))
        assertEquals(StreamFormat.W_SLOT_COLUMNS.toString(), define("SHADR_STREAM_W_SLOT_COLUMNS"))
        assertEquals(StreamFormat.W_SLOT_ROWS.toString(), define("SHADR_STREAM_W_SLOT_ROWS"))
        assertEquals(StreamFormat.W_FLAGS.toString(), define("SHADR_STREAM_W_FLAGS"))
        assertEquals(StreamFormat.FLAG_ACTIVE.toString(), define("SHADR_STREAM_FLAG_ACTIVE"))
        assertEquals("1920.0", define("SHADR_STREAM_DESIGN_WIDTH"))
        assertEquals("1080.0", define("SHADR_STREAM_DESIGN_HEIGHT"))
    }

    @Test
    fun `the stream include only reads the palette through the generated lookup`() {
        val overlay = java.io.File("../shaders/overlays/mc_26_2")
        val stream = java.io.File(overlay, "include/shadr_stream.glsl")
        val vertex = java.io.File(overlay, "include/shadr_stream_vertex.glsl")
        check(stream.isFile) { "mc_26_2 has no shadr_stream.glsl" }
        check(vertex.isFile) { "mc_26_2 has no shadr_stream_vertex.glsl" }

        val streamSource = stream.readText()
        assertTrue(streamSource.contains("#moj_import <shadr_map.glsl>"), "stream include must pull the generated table")
        assertTrue(streamSource.indexOf("#define") < streamSource.indexOf("#moj_import"), "defines must lead the file")

        val vertexSource = vertex.readText()
        assertTrue(vertexSource.contains("textureSize(source, 0) == ivec2(SHADR_MAP_EDGE, SHADR_MAP_EDGE)"))
        assertTrue(vertexSource.contains("shadrMode = SHADR_MODE_STREAM"))
        assertTrue(vertexSource.contains("shadr_map_word"), "header words must decode through the palette table")
    }

    @Test
    fun `the text programs route maps into the stream path`() {
        val overlay = java.io.File("../shaders/overlays/mc_26_2")
        val vsh = java.io.File(overlay, "core/text.vsh").readText()
        val fsh = java.io.File(overlay, "core/text.fsh").readText()

        assertTrue(vsh.contains("#moj_import <shadr_stream_vertex.glsl>"))
        assertTrue(vsh.contains("uniform sampler2D Sampler0;"), "the vertex stage needs Sampler0 to read the header")
        assertTrue(vsh.contains("shadr_stream_place(Sampler0, UV0)"))
        assertTrue(
            vsh.indexOf("shadr_stream_place") < vsh.indexOf("make_hud()"),
            "the stream hook must run before the HUD reprojection resets shadrMode",
        )

        assertTrue(fsh.contains("shadr_is_stream()"))
        assertTrue(
            fsh.indexOf("shadr_is_stream()") < fsh.indexOf("color.a < 0.1"),
            "the raw ingest branch must bypass the alpha discard",
        )
    }

    @Test
    fun `mode flags are independent bits on both sides`() {
        val hud = java.io.File("../shaders/overlays/_shared/include/hud.glsl").readText()
        val fragment = java.io.File("../shaders/overlays/_shared/include/hud_fragment.glsl").readText()

        assertTrue(hud.contains("#define SHADR_MODE_STREAM 8.0"))
        assertTrue(fragment.contains("bool shadr_has_mode(float bit)"))
        assertFalse(
            fragment.contains("shadr_mode() >= 4.0"),
            "blur must be a bit test, otherwise the stream flag reads as a blur panel",
        )
        assertTrue(fragment.contains("shadr_has_mode(8.0)"))
    }
}
