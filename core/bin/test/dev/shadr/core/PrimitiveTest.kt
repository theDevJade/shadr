/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.hud.HudPositionCalculator
import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Page
import dev.shadr.core.page.Rounding
import dev.shadr.core.page.TemplateResolver
import dev.shadr.core.text.Glyphs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrimitiveTest {
    private val resolver get() = TemplateResolver()

    private fun resolve(vararg blocks: Map<String, Any?>) =
        resolver.resolve(blocks.toList(), dev.shadr.core.page.ScreenDef())

    @Test
    fun `a plain block rounds when it carries a rounding block`() {
        val element = resolve(
            mapOf(
                "type" to "block",
                "id" to "a",
                "size" to mapOf("width" to 100, "height" to 60),
                "rounding" to mapOf("size" to "large"),
            ),
        ).single()

        assertNotNull(element.rounding, "a block was denied its own rounding")
        assertEquals(dev.shadr.core.RoundingSize.LARGE, element.rounding!!.size)
    }

    @Test
    fun `a plain block with no rounding block stays sharp`() {
        val element = resolve(
            mapOf("type" to "block", "id" to "a", "size" to mapOf("width" to 100, "height" to 60)),
        ).single()
        assertEquals(null, element.rounding)
    }

    @Test
    fun `block_rounded still defaults its rounding on`() {
        val element = resolve(
            mapOf(
                "type" to "block_rounded",
                "id" to "a",
                "size" to mapOf("width" to 100, "height" to 60),
            ),
        ).single()
        assertNotNull(element.rounding)
    }

    @Test
    fun `block plus rounding renders identically to block_rounded`() {
        fun draws(type: String, rounding: Map<String, Any?>?) = PageRenderer()
            .render(
                Page(
                    name = "t",
                    elements = resolve(
                        buildMap {
                            put("type", type)
                            put("id", "a")
                            put("position", mapOf("x" to 10, "y" to 20))
                            put("size", mapOf("width" to 100, "height" to 60))
                            if (rounding != null) put("rounding", rounding)
                        },
                    ),
                ),
            )
            .draws

        val explicit = draws("block", mapOf("size" to "regular"))
        val legacy = draws("block_rounded", null)

        assertEquals(legacy.size, explicit.size, "different draw counts")
        assertEquals(
            legacy.map { it.translation to it.scale },
            explicit.map { it.translation to it.scale },
        )
        assertEquals(
            1, explicit.size,
            "rounding is a distance field, so it must cost exactly one display entity",
        )
        assertTrue(
            explicit.single().distanceField,
            "a rounded box that is not a distance field is the old glyph mosaic",
        )
    }

    @Test
    fun `a sharp block is still a single quad`() {
        val draws = PageRenderer()
            .render(Page(name = "t", elements = listOf(Element(id = "a", type = ElementType.BLOCK))))
            .draws
        assertEquals(1, draws.size)
    }

    @Test
    fun `rounding set to none costs no extra entities`() {
        val draws = PageRenderer()
            .render(
                Page(
                    name = "t",
                    elements = listOf(
                        Element(
                            id = "a",
                            type = ElementType.BLOCK,
                            rounding = Rounding(size = dev.shadr.core.RoundingSize.NONE, radius = 0.0),
                        ),
                    ),
                ),
            )
            .draws
        assertEquals(1, draws.size)
    }

    @Test
    fun `each primitive picks up its own glyph`() {
        val expected = mapOf(
            "circle" to Glyphs.CIRCLE,
            "gradient" to Glyphs.GRADIENT,
            "progress" to Glyphs.SLIDER,
            "block" to Glyphs.BACKGROUND,
        )
        for ((type, glyph) in expected) {
            val element = resolve(mapOf("type" to type, "id" to type)).single()
            assertEquals(glyph.toString(), element.unicode, "$type used the wrong glyph")
        }
    }

    @Test
    fun `an explicit unicode still wins over the type's glyph`() {
        val element = resolve(mapOf("type" to "circle", "id" to "a", "unicode" to "")).single()
        assertEquals("", element.unicode)
    }

    @Test
    fun `a circle is one quad and takes no rounding`() {
        val element = resolve(
            mapOf("type" to "circle", "id" to "a", "rounding" to mapOf("size" to "large")),
        ).single()

        assertEquals(null, element.rounding)
        assertEquals(1, PageRenderer().render(Page(name = "t", elements = listOf(element))).draws.size)
    }

    @Test
    fun `every shape glyph the font ships is reachable from a type`() {
        val reachable = ElementType.entries.map { it.defaultGlyph.code }.toSet()
        val shipped = Glyphs.SHAPE_TEXTURES.map { it.first }.toSet()
        val orphaned = shipped - reachable - setOf(

            Glyphs.ROUNDED_SOFT.code, Glyphs.ROUNDED_SOFT2.code, Glyphs.ROUNDED_SOFT3.code,
        )
        assertTrue(orphaned.isEmpty(), "shipped but unreachable glyphs: $orphaned")
    }

    @Test
    fun `a rounded box sits where any other item display of that size sits`() {
        fun render(extra: Map<String, Any?>) = PageRenderer()
            .render(
                Page(
                    name = "t",
                    elements = resolve(
                        buildMap {
                            put("id", "a")
                            put("position", mapOf("x" to 10, "y" to 20))
                            put("size", mapOf("width" to 100, "height" to 60))
                            putAll(extra)
                        },
                    ),
                ),
            )
            .draws
            .single()

        val item = render(mapOf("type" to "item", "item" to "minecraft:stone"))
        val rounded = render(mapOf("type" to "block", "rounding" to mapOf("size" to "regular")))

        assertTrue(rounded.distanceField, "rounding should be a distance field")
        assertEquals(
            item.translation.y,
            rounded.translation.y + HudPositionCalculator.FIELD_BAND,
            1e-9,
            "the rounded box and a plain item display disagree on where their quad sits",
        )
        assertEquals(item.translation.x, rounded.translation.x, 1e-9)
    }

    @Test
    fun `a rounded box covers the same area as the glyph it replaces`() {
        fun render(extra: Map<String, Any?>) = PageRenderer()
            .render(
                Page(
                    name = "t",
                    elements = resolve(
                        buildMap {
                            put("id", "a")
                            put("position", mapOf("x" to 0, "y" to 0))
                            put("size", mapOf("width" to 200, "height" to 80))
                            putAll(extra)
                        },
                    ),
                ),
            )
            .draws
            .single()

        val square = render(mapOf("type" to "block"))
        val rounded = render(mapOf("type" to "block", "rounding" to mapOf("size" to "regular")))

        val glyphBlocks = PageRenderer.SDF_QUAD_SCALE
        assertEquals(
            square.scale.x * glyphBlocks, rounded.scale.x, 1e-9,
            "a rounded box draws smaller than the square one it replaces",
        )
        assertEquals(square.scale.y * glyphBlocks, rounded.scale.y, 1e-9)
    }

    @Test
    fun `a rounded box does not sit on a square outline`() {
        val draws = PageRenderer()
            .render(
                Page(
                    name = "t",
                    elements = resolve(
                        mapOf(
                            "type" to "block",
                            "id" to "a",
                            "position" to mapOf("x" to 0, "y" to 0),
                            "size" to mapOf("width" to 200, "height" to 80),
                            "rounding" to mapOf("size" to "regular"),
                            "outline" to mapOf("size" to 4, "color" to "ff0000"),
                        ),
                    ),
                ),
            )
            .draws

        assertEquals(2, draws.size)
        assertTrue(
            draws.all { it.distanceField },
            "the outline is still a square glyph behind a rounded box",
        )

        val outline = draws.single { it.key.endsWith("__outline") }
        val box = draws.single { !it.key.endsWith("__outline") }
        assertTrue(
            outline.cornerFraction!! > 0.0 && box.cornerFraction!! > 0.0,
            "both quads should be rounded",
        )
        assertTrue(outline.scale.x > box.scale.x, "the outline should be the larger quad")
    }
}
