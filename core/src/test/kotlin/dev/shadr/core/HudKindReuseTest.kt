/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.hud.HudDiff
import dev.shadr.core.hud.HudDraw
import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.page.Page
import dev.shadr.core.page.TemplateResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HudKindReuseTest {

    private fun page(vararg blocks: Map<String, Any?>) =
        Page(name = "p", elements = TemplateResolver().resolve(blocks.toList(), dev.shadr.core.page.ScreenDef()))

    private fun drawsFor(type: String) = PageRenderer().render(
        page(
            mapOf(
                "type" to type, "id" to "title",
                "position" to mapOf("x" to 10, "y" to 10),
                "size" to mapOf("width" to 50, "height" to 50),
                "text" to "hello",
            ),
        ),
    ).draws.associateBy { it.key }

    @Test
    fun `two pages can give the same id different kinds`() {
        val text = drawsFor("text")["title"]!!
        val item = drawsFor("item")["title"]!!

        assertEquals(HudDraw.Kind.TEXT, text.kind)
        assertEquals(HudDraw.Kind.ITEM, item.kind)
    }

    @Test
    fun `a key that comes back as the other kind respawns instead of updating`() {
        val before = mapOf("title" to drawsFor("text")["title"]!!)
        val after = listOf(drawsFor("item")["title"]!!)

        val diff = HudDiff.between(before, after) { key -> key in before }

        assertTrue(
            "title" in diff.spawned.map { it.key },
            "text and item displays share metadata slot 23, so reusing one entity as the other " +
                "would write a text component into an item slot and render garbage; ids repeat " +
                "across pages, so this is reached every time a page is swapped",
        )
        assertTrue("title" !in diff.updated.map { it.key }, "a kind change must never be an update")
    }
}
