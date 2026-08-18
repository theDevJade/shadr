/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.stream.MapPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MapPaletteTest {

    @Test
    fun `the alphabet is one hundred and twenty eight opaque map colours`() {
        assertEquals(MapPalette.WORDS, MapPalette.alphabet.size)
        for (word in 0 until MapPalette.WORDS) {
            val packedId = MapPalette.packedId(word)
            assertNotEquals(0, MapPalette.argb(packedId) ushr 24, "word $word maps to a transparent map colour")
        }
    }

    @Test
    fun `every alphabet entry has a unique red channel`() {
        val reds = MapPalette.alphabet.map { MapPalette.red(it.toInt() and 0xFF) }
        assertEquals(MapPalette.WORDS, reds.toSet().size)
    }

    @Test
    fun `red is monotonic in the word index`() {
        val reds = MapPalette.alphabet.map { MapPalette.red(it.toInt() and 0xFF) }
        for (i in 1 until reds.size) {
            assertTrue(reds[i] > reds[i - 1], "red is not increasing at word $i")
        }
    }

    @Test
    fun `every word round trips through the map colour palette`() {
        for (word in 0 until MapPalette.WORDS) {
            assertEquals(word, MapPalette.decode(MapPalette.encode(word)), "word $word did not round trip")
        }
    }

    @Test
    fun `reds outside the alphabet decode to no word`() {
        val used = MapPalette.alphabet.map { MapPalette.red(it.toInt() and 0xFF) }.toSet()
        for (red in 0 until MapPalette.PACKED_IDS) {
            if (red in used) continue
            assertEquals(MapPalette.NO_WORD, MapPalette.redToWord[red], "red $red should be unmapped")
        }
    }

    @Test
    fun `the map colour table matches the vanilla packed id contract`() {
        assertEquals(0, MapPalette.argb(0))
        assertEquals(0, MapPalette.argb(1))
        assertEquals(0, MapPalette.argb(248))
        assertEquals(0, MapPalette.argb(255))
        assertEquals(0xFF7FB238.toInt(), MapPalette.argb(4 or 2))
        assertEquals(0xFF597D27.toInt(), MapPalette.argb(4 or 0))
    }

    @Test
    fun `the generated glsl exposes a full lookup table`() {
        val glsl = MapPalette.glsl()
        assertTrue(glsl.startsWith("#define SHADR_MAP_EDGE 128"), "defines must lead the file")
        assertTrue(glsl.contains("const int SHADR_MAP_WORD[256] = int[256]("))
        assertTrue(glsl.contains("int shadr_map_word(vec4 texel)"))
        val body = glsl.substringAfter("int[256](").substringBefore(");")
        assertEquals(MapPalette.PACKED_IDS, body.split(',').size)
    }
}
