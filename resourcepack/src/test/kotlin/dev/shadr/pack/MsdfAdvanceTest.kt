/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.pack.msdf.MsdfFont
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MsdfAdvanceTest {
    private val ttf = File("../assets/font/nerd_mono.ttf").canonicalFile

    private fun opaqueWidth(baked: MsdfFont.Baked, index: Int, cell: Int, columns: Int): Int {
        val x0 = (index % columns) * cell
        val y0 = (index / columns) * cell
        var widest = 0
        for (y in y0 until y0 + cell) {
            for (x in x0 + cell - 1 downTo x0) {
                if (baked.image.getRGB(x, y) ushr 24 != 0) {
                    widest = maxOf(widest, x - x0 + 1)
                    break
                }
            }
        }
        return widest
    }

    @Test
    fun `every glyph advances, and a monospace font advances uniformly`() {
        if (!ttf.isFile) return

        val cell = 64
        val columns = 16
        val baked = MsdfFont(ttf, cell = cell, spread = 4.0, columns = columns).bake()
        val charset = MsdfFont.DEFAULT_CHARSET

        val space = opaqueWidth(baked, charset.indexOf(' '), cell, columns)
        assertTrue(space > 0, "a space with no opaque pixels advances by nothing, so words run together")

        for (character in listOf('!', 'A', 'W', 'i', 'm', '.')) {
            assertEquals(
                space, opaqueWidth(baked, charset.indexOf(character), cell, columns),
                "'$character' does not advance like the rest of a monospace font",
            )
        }
    }

    @Test
    fun `every baked atlas ships filtering and clamping alongside it`() {
        val root = createTempDirectory("shadr-msdf-meta").toFile()
        if (!ttf.isFile) return

        MsdfFont(ttf, cell = 32, spread = 4.0).write(
            packRoot = root,
            texturePath = "font/shadr/probe.png",
            codepointStart = 0,
        )

        val meta = File(root, "assets/minecraft/textures/font/shadr/probe.png.mcmeta")
        assertTrue(meta.isFile, "the atlas shipped without texture metadata")
        val text = meta.readText()
        assertTrue(text.contains("\"blur\": true"), "a distance field must be filtered: $text")
        assertTrue(text.contains("\"clamp\": true"), "a distance field must not wrap: $text")
    }

    @Test
    fun `the field stops before it reaches the next glyph`() {
        if (!ttf.isFile) return

        val cell = 64
        val columns = 16
        val baked = MsdfFont(ttf, cell = cell, spread = 4.0, columns = columns).bake()
        val at = MsdfFont.DEFAULT_CHARSET.indexOf('A')
        val x0 = (at % columns) * cell
        val y0 = (at / columns) * cell

        fun median(argb: Int): Double {
            val r = ((argb shr 16) and 0xFF) / 255.0
            val g = ((argb shr 8) and 0xFF) / 255.0
            val b = (argb and 0xFF) / 255.0
            return maxOf(minOf(r, g), minOf(maxOf(r, g), b))
        }

        val advance = opaqueWidth(baked, at, cell, columns)
        val field = (0 until cell).filter { x ->
            (0 until cell).any { y -> median(baked.image.getRGB(x0 + x, y0 + y)) > 0.02 }
        }.maxOrNull() ?: -1

        assertTrue(
            field < cell - 1,
            "the field runs to the edge of the cell, so it overlaps the next glyph",
        )
        assertTrue(
            field > advance,
            "the field was clipped inside the advance, which would cut the antialiasing",
        )
    }
}
