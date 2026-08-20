/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.text.Glyphs
import dev.shadr.core.text.MetricsTable
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FontMetricsTest {

    private val fonts = File("../assets/font").canonicalFile

    private fun build(): MetricsTable {
        val root = createTempDirectory("shadr-metrics").toFile()
        return FontAssets.writeAll(root, fonts)
    }

    @Test
    fun `the scale unit is the display maths, not a tuned constant`() {
        val perFontPixel = dev.shadr.core.hud.HudPositionCalculator.YAML_TO_HUD_SIZE_FACTOR /
            2.0 / MetricsTable.TEXT_DISPLAY_PIXELS_PER_BLOCK
        assertEquals(1.0 / MetricsTable.SCALE_UNIT, perFontPixel)
    }

    @Test
    fun `a shape glyph advances by the cell it is drawn in`() {
        val table = build()
        val ui = assertNotNull(table.fonts[Glyphs.FONT_UI], "the ui font has no metrics")

        // the bitmap providers are 64 pixels tall and the client adds one pixel of spacing
        assertEquals(65.0, ui.advanceOf(Glyphs.BACKGROUND.code))
        assertEquals(65.0, ui.advanceOf(Glyphs.CIRCLE.code))
        assertEquals(65.0, ui.advanceOf(Glyphs.corner(0, 0).code))
    }

    @Test
    fun `the typeface advance follows the size the provider is baked at`() {
        val table = build()
        val ui = assertNotNull(table.fonts[Glyphs.FONT_UI])

        assertEquals(MetricsTable.TTF_PIXEL_SIZE, ui.ascent + ui.descent, 0.001)
        assertTrue(ui.advance > 0.0, "the typeface advances by nothing")
        assertEquals(ui.advance, ui.advanceOf('m'.code), "the shipped typeface is monospace")
        assertEquals(ui.advance, ui.advanceOf('i'.code), "the shipped typeface is monospace")
    }

    @Test
    fun `the sharp weights lay out differently from the typeface ones`() {
        val table = build()
        val ui = assertNotNull(table.fonts[Glyphs.FONT_UI])
        val sharp = assertNotNull(
            table.fonts[Glyphs.FONT_UI_SHARP],
            "the distance field font has no metrics, so the editor would draw it as the ttf",
        )
        assertTrue(
            ui.advance != sharp.advance,
            "the two providers agreed, which would make this test pointless rather than passing",
        )
    }

    @Test
    fun `the sharp weights cover ascii and nothing else`() {
        val table = build()
        assertTrue(table.covers(Glyphs.FONT_UI_SHARP, 'A'.code))
        assertTrue(!table.covers(Glyphs.FONT_UI_SHARP, 0x00E9), "e-acute is not in the atlas")
    }

    @Test
    fun `measuring a string is the sum of its advances`() {
        val table = build()
        val ui = assertNotNull(table.fonts[Glyphs.FONT_UI])
        assertEquals(ui.advance * 5, table.measure(Glyphs.FONT_UI, "shadr"), 0.001)
        assertEquals(
            ui.advance * 5 * 64.0 / MetricsTable.SCALE_UNIT,
            table.measureDesign(Glyphs.FONT_UI, "shadr", 64.0),
            0.001,
        )
    }

    @Test
    fun `wrapping breaks on a space and drops it`() {
        val table = build()
        val ui = assertNotNull(table.fonts[Glyphs.FONT_UI])
        val lines = table.wrap(Glyphs.FONT_UI, "aaa bbb ccc", (ui.advance * 4).toInt())

        assertEquals(listOf("aaa", "bbb", "ccc"), lines)
    }

    @Test
    fun `every shipped page can be measured with the metrics the pack writes`() {
        val table = build()
        val protocol = File("../protocol")
        val loader = dev.shadr.core.page.PageLoader(
            pagesDir = File(protocol, "pages"),
            componentsDir = File(protocol, "components"),
            effectsDir = File(protocol, "effects"),
        )
        val pages = loader.loadPages(loader.loadComponents())

        for ((name, page) in pages) {
            for (element in page.elements) {
                if (element.type != dev.shadr.core.page.ElementType.TEXT) continue
                assertNotNull(
                    table.fonts[element.font],
                    "$name/${element.id} uses ${element.font}, which the pack writes no metrics for",
                )
            }
        }
    }
}
