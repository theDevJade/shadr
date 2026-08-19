/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.InputPlaceholders
import dev.shadr.core.page.Page
import dev.shadr.core.page.PlaceholderScanner
import dev.shadr.core.page.TemplateResolver
import dev.shadr.core.page.TextInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextInputTest {

    private fun resolve(vararg blocks: Map<String, Any?>) =
        TemplateResolver().resolve(blocks.toList(), dev.shadr.core.page.ScreenDef())

    private fun field(extra: Map<String, Any?> = emptyMap()) = resolve(
        mapOf(
            "type" to "text_input",
            "id" to "amount",
            "position" to mapOf("x" to 100, "y" to 100),
            "size" to mapOf("width" to 240, "height" to 36),
            "placeholder" to "Amount",
        ) + extra,
    ).single()

    @Test
    fun `a text input parses its own keys`() {
        val element = field(mapOf("maxLength" to 12, "lines" to 2, "secret" to true))
        assertEquals(ElementType.TEXT_INPUT, element.type)
        val input = assertNotNull(element.input, "the field carries no input model")
        assertEquals("Amount", input.placeholder)
        assertEquals(12, input.maxLength)
        assertEquals(2, input.clampedLines)
        assertTrue(input.secret)
    }

    @Test
    fun `only a text input carries an input model`() {
        val block = resolve(mapOf("type" to "block", "id" to "b", "placeholder" to "nope")).single()
        assertNull(block.input, "a plain block picked up text input state")
    }

    @Test
    fun `a field is rounded by default so it reads as a field`() {
        assertTrue(ElementType.TEXT_INPUT.roundedByDefault)
        assertTrue(ElementType.TEXT_INPUT.supportsRounding)
    }

    @Test
    fun `lines are clamped to what a sign can carry`() {
        assertEquals(TextInput.SIGN_LINES, field(mapOf("lines" to 99)).input!!.clampedLines)
        assertEquals(1, field(mapOf("lines" to 0)).input!!.clampedLines)
    }

    @Test
    fun `the value is clamped to maxLength`() {
        val input = field(mapOf("maxLength" to 5)).input!!
        assertEquals("12345", input.clamp("1234567890"))
    }

    @Test
    fun `a secret field never shows what was typed`() {
        val input = field(mapOf("secret" to true)).input!!
        assertEquals("••••", input.display("hunter2".take(4)))
        assertTrue(!input.display("hunter2").contains("hunter"))
    }

    @Test
    fun `an empty field shows the placeholder instead`() {
        assertEquals("Amount", field().input!!.display(""))
    }

    @Test
    fun `a field renders its box and its value as separate draws`() {
        val element = field()
        val withValue = element.copy(input = element.input!!.copy(value = "42"))
        val page = Page(name = "t", elements = listOf(withValue))

        val draws = PageRenderer().render(page).draws
        val keys = draws.map { it.key }.toSet()

        assertTrue("amount" in keys, "the field background is missing, got $keys")
        assertTrue("amount__value" in keys, "the field value is missing, got $keys")
        assertTrue(
            draws.all { it.elementId == "amount" },
            "every draw a field emits must report the field as its element",
        )
        assertTrue(
            draws.single { it.key == "amount__value" }.content.contains("42"),
            "the value draw does not carry the typed text",
        )
    }

    @Test
    fun `the value draws in front of the background and the outline behind it`() {
        val element = field().let {
            it.copy(
                outline = dev.shadr.core.page.Outline(size = 1.0, color = Rgb(0x2A2A36)),
                input = it.input!!.copy(value = "x"),
            )
        }
        val draws = PageRenderer().render(Page(name = "t", elements = listOf(element)))
            .draws.associateBy { it.key }

        val box = assertNotNull(draws["amount"]).translation.z
        val value = assertNotNull(draws["amount__value"]).translation.z
        val outline = assertNotNull(draws["amount__outline"]).translation.z

        assertTrue(
            value < box,
            "lower z is nearer the camera, so the value at $value must be below the box at $box " +
                "or the field renders its own background over the text",
        )
        assertTrue(
            outline > box,
            "the outline at $outline must sit behind the box at $box",
        )
    }

    @Test
    fun `input placeholders resolve by element id and ignore case`() {
        val resolver = InputPlaceholders { _, id -> if (id.equals("amount", true)) "42" else null }
        val player = PlayerId("00000000-0000-0000-0000-000000000000")

        assertEquals("you sent 42", PlaceholderScanner.apply("you sent %input_amount%", player, resolver))
    }

    @Test
    fun `an unknown input resolves to empty rather than staying on screen`() {
        val resolver = InputPlaceholders { _, _ -> null }
        val player = PlayerId("00000000-0000-0000-0000-000000000000")

        assertEquals("[]", PlaceholderScanner.apply("[%input_missing%]", player, resolver))
    }

    @Test
    fun `a field added by the editor survives a YAML round trip`() {
        val dir = kotlin.io.path.createTempDirectory("shadr-input").toFile()
        val pages = java.io.File(dir, "pages").apply { mkdirs() }
        java.io.File(dir, "components").mkdirs()
        java.io.File(dir, "effects").mkdirs()
        val file = java.io.File(pages, "form.yml").apply {
            writeText("name: form\nscreen:\n  width: 1920\n  height: 1080\n\nblocks: []\n")
        }
        val loader = dev.shadr.core.page.PageLoader(
            pages, java.io.File(dir, "components"), java.io.File(dir, "effects"),
        )

        val original = loader.loadPage(file)!!
        val added = field(mapOf("maxLength" to 12, "lines" to 3, "secret" to true))
        val result = dev.shadr.core.editor.PageWriter()
            .save(file, original, original.copy(elements = listOf(added)))
        assertTrue(result.ok, "the writer refused the field: ${result.skipped}")

        val reloaded = loader.loadPage(file)!!.elements.single()
        val input = assertNotNull(reloaded.input, "the round trip dropped the input model")
        assertEquals(ElementType.TEXT_INPUT, reloaded.type)
        assertEquals("Amount", input.placeholder)
        assertEquals(12, input.maxLength)
        assertEquals(3, input.clampedLines)
        assertTrue(input.secret)
    }

    @Test
    fun `the input resolver ignores placeholders that are not inputs`() {
        val resolver = InputPlaceholders { _, _ -> "unexpected" }
        val player = PlayerId("00000000-0000-0000-0000-000000000000")

        assertEquals("%shadr_player%", PlaceholderScanner.apply("%shadr_player%", player, resolver))
    }
}
