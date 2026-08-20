/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.page.ActionSpec
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Interaction
import dev.shadr.core.page.Page
import dev.shadr.core.page.PageLoader
import dev.shadr.core.page.Rounding
import dev.shadr.core.text.CodepointRange
import dev.shadr.core.text.FontMetrics
import dev.shadr.core.text.MetricsTable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RenderBoxTest {

    private val metrics = MetricsTable(
        fonts = mapOf(
            "shadr" to FontMetrics(
                advance = 5.0, ascent = 8.5, descent = 2.5, lineHeight = 9.0,
                advances = mapOf(0xE000 to 65.0),
                coverage = listOf(CodepointRange(0x20, 0x7E), CodepointRange(0xE000, 0xE000)),
            ),
            "shadr_sharp" to FontMetrics(
                advance = 6.0, ascent = 10.0, descent = 4.0, lineHeight = 9.0,
                coverage = listOf(CodepointRange(0x20, 0x7E)),
            ),
        ),
    )

    private val renderer = PageRenderer(metrics = metrics)

    private fun render(vararg elements: Element) =
        renderer.render(Page(name = "t", elements = elements.toList()))

    private fun text(
        id: String = "t",
        x: Double = 0.0,
        y: Double = 0.0,
        scale: Double = 64.0,
        body: String = "shadr",
        font: String = "shadr",
        align: TextAlignment = TextAlignment.LEFT,
        lineWidth: Int = 200,
    ) = Element(
        id = id, type = ElementType.TEXT,
        x = x, y = y, width = scale, height = scale,
        text = body, font = font, textAlignment = align, lineWidth = lineWidth,
    )

    @Test
    fun `a block covers the rectangle it was authored at`() {
        val box = assertNotNull(
            render(
                Element(
                    id = "card", type = ElementType.BLOCK,
                    x = 100.0, y = 200.0, width = 520.0, height = 340.0,
                ),
            ).renderBoxes["card"],
            "no render box was produced",
        )
        assertEquals(100.0, box.x)
        assertEquals(200.0, box.y)
        assertEquals(520.0, box.width)
        assertEquals(340.0, box.height)
    }

    @Test
    fun `a rounded block covers the same rectangle as a plain one`() {
        fun boxOf(type: ElementType) = render(
            Element(
                id = "b", type = type,
                x = 40.0, y = 60.0, width = 180.0, height = 44.0,
                rounding = Rounding(size = RoundingSize.SMALL),
            ),
        ).renderBoxes.getValue("b")

        assertEquals(boxOf(ElementType.BLOCK), boxOf(ElementType.BLOCK_ROUNDED))
    }

    @Test
    fun `text measures its glyphs, not the font scale square`() {
        val box = render(text()).renderBoxes.getValue("t")

        assertEquals(25.0, box.width)
        assertEquals(9.0, box.height)
        assertEquals(0.0, box.x)
    }

    @Test
    fun `the two font families do not lay a string out the same way`() {
        fun widthOf(font: String) =
            render(text(font = font)).renderBoxes.getValue("t").width

        assertEquals(25.0, widthOf("shadr"))
        assertEquals(30.0, widthOf("shadr_sharp"))
    }

    @Test
    fun `alignment anchors the run on the authored point`() {
        fun boxOf(align: TextAlignment) =
            render(text(x = 500.0, align = align)).renderBoxes.getValue("t")

        assertEquals(500.0, boxOf(TextAlignment.LEFT).x)
        assertEquals(475.0, boxOf(TextAlignment.RIGHT).x)
        assertEquals(487.5, boxOf(TextAlignment.CENTER).x)
    }

    @Test
    fun `wrapping happens at the line width the display is given`() {
        val box = render(text(body = "aaaa bbbb cccc", lineWidth = 25))
            .renderBoxes.getValue("t")

        assertEquals(3, (box.height / 9.0).toInt(), "expected three wrapped lines")
        assertTrue(box.width <= 25.0, "a wrapped line came out wider than the wrap width")
    }

    @Test
    fun `a hit region follows the drawn rectangle and the author's nudge`() {
        val rendered = render(
            Element(
                id = "b", type = ElementType.BLOCK,
                x = 10.0, y = 20.0, width = 100.0, height = 50.0,
                interaction = Interaction(
                    hitboxOffsetX = 5.0,
                    hitboxOffsetY = -5.0,
                    onClick = listOf(ActionSpec("message", "hi")),
                ),
            ),
        )
        val region = rendered.hitRegions.single { it.elementId == "b" }
        assertEquals(15.0, region.x)
        assertEquals(15.0, region.y)
        assertEquals(100.0, region.width)
        assertTrue(region.interactive)
    }

    @Test
    fun `a text hit region is the glyph run, not the font scale square`() {
        val rendered = render(
            text(x = 0.0, y = 0.0).copy(
                interaction = Interaction(onClick = listOf(ActionSpec("message", "hi"))),
            ),
        )
        val region = rendered.hitRegions.single { it.elementId == "t" }

        assertEquals(25.0, region.width)
        assertTrue(!region.contains(60.0, 30.0), "the whole font scale square still takes clicks")
    }

    @Test
    fun `a rotated element is clickable where it was rotated to`() {
        val rendered = render(
            Element(
                id = "b", type = ElementType.BLOCK,
                x = 0.0, y = 0.0, width = 100.0, height = 20.0,
                rotationDeg = 90.0,
                interaction = Interaction(onClick = listOf(ActionSpec("message", "hi"))),
            ),
        )
        val region = rendered.hitRegions.single { it.elementId == "b" }
        assertTrue(region.contains(50.0, 40.0), "the rotated long axis is not clickable")
        assertTrue(!region.contains(95.0, 10.0), "the unrotated long axis is still clickable")
    }

    @Test
    fun `every shipped page produces a box for every element it renders`() {
        val protocol = File("../protocol")
        val loader = PageLoader(
            pagesDir = File(protocol, "pages"),
            componentsDir = File(protocol, "components"),
            effectsDir = File(protocol, "effects"),
        )
        val pages = loader.loadPages(loader.loadComponents())
        assertTrue(pages.isNotEmpty(), "no shipped pages were found at ${protocol.canonicalPath}")

        for ((name, page) in pages) {
            val rendered = renderer.render(page)
            for (element in page.elements.filter { it.enabled }) {
                assertNotNull(
                    rendered.renderBoxes[element.id],
                    "$name/${element.id} draws with no rectangle for the editor to paint",
                )
            }
        }
    }
}
