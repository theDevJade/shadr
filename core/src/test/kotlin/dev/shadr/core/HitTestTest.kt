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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HitTestTest {
    private fun page(vararg elements: Element) = Page(name = "t", elements = elements.toList())

    private val clicks = Interaction(onClick = listOf(ActionSpec("run", "say hi")))

    @Test
    fun `a label sitting on a button does not swallow the click`() {
        val rendered = PageRenderer().render(
            page(
                Element(
                    id = "button", type = ElementType.BLOCK_ROUNDED,
                    x = 0.0, y = 0.0, width = 200.0, height = 60.0, layer = 12.0,
                    interaction = clicks,
                ),
                Element(
                    id = "label", type = ElementType.TEXT,
                    x = 20.0, y = 20.0, width = 64.0, height = 64.0, layer = 13.0,
                    text = "Press me",
                ),
            ),
        )

        assertEquals("button", rendered.hitTest(40.0, 30.0)?.elementId)
    }

    @Test
    fun `a blur panel never takes input, whatever layer it was authored at`() {
        val rendered = PageRenderer().render(
            page(
                Element(
                    id = "button", type = ElementType.BLOCK,
                    x = 0.0, y = 0.0, width = 100.0, height = 100.0, layer = 1.0,
                    interaction = clicks,
                ),
                Element(
                    id = "panel", type = ElementType.BLUR,
                    x = 0.0, y = 0.0, width = 1920.0, height = 1080.0, layer = 14.0,
                ),
            ),
        )

        assertEquals("button", rendered.hitTest(50.0, 50.0)?.elementId)
        assertNull(rendered.hitTest(900.0, 900.0), "the backdrop should not be hoverable")
    }

    @Test
    fun `an element that reacts still takes input`() {
        for (interaction in listOf(
            clicks,
            Interaction(hoverText = "tip"),
            Interaction(hoverEffect = "glow"),
            Interaction(clickEffect = "press"),
        )) {
            val rendered = PageRenderer().render(
                page(
                    Element(
                        id = "a", type = ElementType.BLOCK,
                        x = 0.0, y = 0.0, width = 50.0, height = 50.0,
                        interaction = interaction,
                    ),
                ),
            )
            assertEquals("a", rendered.hitTest(10.0, 10.0)?.elementId, "$interaction")
        }
    }

    @Test
    fun `an explicit hitbox takes input even with nothing bound to it yet`() {
        val rendered = PageRenderer().render(
            page(
                Element(
                    id = "area", type = ElementType.HITBOX,
                    x = 0.0, y = 0.0, width = 50.0, height = 50.0,
                ),
            ),
        )
        assertEquals("area", rendered.hitTest(10.0, 10.0)?.elementId)
    }

    @Test
    fun `disableHitbox still wins over everything`() {
        val rendered = PageRenderer().render(
            page(
                Element(
                    id = "a", type = ElementType.HITBOX,
                    x = 0.0, y = 0.0, width = 50.0, height = 50.0,
                    interaction = Interaction(disableHitbox = true),
                ),
            ),
        )
        assertNull(rendered.hitTest(10.0, 10.0))
    }

    @Test
    fun `the shipped demo page routes a click on the button to the button`() {
        val root = File("..").canonicalFile
        val loader = dev.shadr.core.page.PageLoader(
            pagesDir = File(root, "protocol/pages"),
            componentsDir = File(root, "protocol/components"),
            effectsDir = File(root, "protocol/effects"),
        )
        val page = loader.loadPage(File(root, "protocol/pages/demo.yml")) ?: error("demo did not load")
        val rendered = PageRenderer().render(page)

        val button = page.elements.first { it.id == "card_button" }
        val hit = rendered.hitTest(button.x + button.width / 2, button.y + button.height / 2)
        assertTrue(
            hit?.elementId == "card_button",
            "a click in the middle of the button landed on ${hit?.elementId}",
        )
    }
}
