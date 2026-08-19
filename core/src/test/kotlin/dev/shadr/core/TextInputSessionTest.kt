/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.action.ActionHost
import dev.shadr.core.action.ActionRunner
import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.page.ActionSpec
import dev.shadr.core.page.Page
import dev.shadr.core.page.TemplateResolver
import dev.shadr.core.session.UiSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import dev.shadr.core.Rgb
import kotlin.test.assertTrue

class TextInputSessionTest {

    private val player = PlayerId("00000000-0000-0000-0000-000000000000")

    private class Host : ActionHost {
        val messages = mutableListOf<String>()
        override fun message(player: PlayerId, text: String) { messages += text }
        override fun runAsPlayer(player: PlayerId, command: String) = Unit
        override fun runAsConsole(command: String) = Unit
        override fun playSound(player: PlayerId, sound: String, volume: Double) = Unit
        override fun closePage(player: PlayerId) = Unit
        override fun openPage(player: PlayerId, page: String, replacing: Boolean) = Unit
        override fun teleport(player: PlayerId, destination: String) = Unit
        override fun hasPermission(player: PlayerId, permission: String) = true
        override fun scheduleTicks(ticks: Long, task: () -> Unit) = task()
    }

    private fun page(vararg extra: Map<String, Any?>): Page {
        val elements = TemplateResolver().resolve(
            listOf(
                mapOf(
                    "type" to "text_input",
                    "id" to "amount",
                    "position" to mapOf("x" to 0, "y" to 0),
                    "size" to mapOf("width" to 200, "height" to 40),
                    "maxLength" to 8,
                    "onSubmitAction" to listOf("message: got %input_amount%"),
                ),
            ) + extra.toList(),
            dev.shadr.core.page.ScreenDef(),
        )
        return Page(name = "form", elements = elements)
    }

    private fun session(host: Host = Host()) =
        UiSession(player, page(), PageRenderer(), emptyMap(), ActionRunner(host))

    @Test
    fun `nothing is focused until a field is focused`() {
        assertNull(session().focusedInput)
    }

    @Test
    fun `focusing a field that is not an input is refused`() {
        val session = session()
        session.focusInput("nope")
        assertNull(session.focusedInput, "focus landed on an element that cannot take text")
    }

    @Test
    fun `focus can be set and cleared`() {
        val session = session()
        session.focusInput("amount")
        assertEquals("amount", session.focusedInput)
        session.focusInput(null)
        assertNull(session.focusedInput)
    }

    @Test
    fun `a typed value is stored and clamped to maxLength`() {
        val session = session()
        session.setInputValue("amount", "1234567890")
        assertEquals("12345678", session.inputValue("amount"))
    }

    @Test
    fun `setting an unknown field changes nothing`() {
        val session = session()
        assertTrue(!session.setInputValue("ghost", "x"))
        assertNull(session.inputValue("ghost"))
    }

    @Test
    fun `setting the same value twice reports no change`() {
        val session = session()
        assertTrue(session.setInputValue("amount", "42"))
        assertTrue(!session.setInputValue("amount", "42"), "an unchanged value still forced a rerender")
    }

    @Test
    fun `the stored value reaches the rendered draw`() {
        val session = session()
        session.setInputValue("amount", "42")
        val value = session.draws().single { it.key == "amount__value" }
        assertTrue(value.content.contains("42"), "the draw shows ${value.content} instead of the typed value")
    }

    @Test
    fun `submitting runs the field's own actions`() {
        val host = Host()
        val session = UiSession(player, page(), PageRenderer(), emptyMap(), ActionRunner(host))
        session.setInputValue("amount", "42")
        session.submitInput("amount")
        assertEquals(listOf("got %input_amount%"), host.messages)
    }

    @Test
    fun `opening a new page clears focus and every value`() {
        val session = session()
        session.setInputValue("amount", "42")
        session.focusInput("amount")

        session.openPage(page())

        assertNull(session.focusedInput, "focus survived a page change")
        assertNull(session.inputValue("amount"), "a typed value survived a page change")
    }

    private fun bareField() = TemplateResolver().resolve(
        listOf(
            mapOf(
                "type" to "text_input", "id" to "amount",
                "position" to mapOf("x" to 100, "y" to 100),
                "size" to mapOf("width" to 200, "height" to 40),
            ),
        ),
        dev.shadr.core.page.ScreenDef(),
    )

    @Test
    fun `a field with nothing bound is still a hit target`() {
        val rendered = PageRenderer().render(Page(name = "form", elements = bareField()))
        val region = rendered.hitRegions.single { it.elementId == "amount" }
        assertTrue(
            region.interactive,
            "a text field is focused by clicking it, which is not an action a page binds, so it " +
                "must take input without one; otherwise it never hovers, focuses or opens",
        )
    }

    @Test
    fun `clicking a field with nothing bound focuses it`() {
        val session = UiSession(
            player, Page(name = "form", elements = bareField()),
            PageRenderer(), emptyMap(), ActionRunner(Host()),
        )
        session.update(ScreenPos(200.0, 120.0), 0.0, 0.0, 0)
        session.click(false)

        assertEquals(
            "amount", session.focusedInput,
            "clicking the field did not focus it, so no editor would ever open",
        )
    }

    private fun outlineOf(session: UiSession): Int =
        session.currentPage.elements.single { it.id == "amount" }.outline!!.color.packed

    @Test
    fun `the field border lifts on hover and lifts further on focus`() {
        val base = Rgb(0x2A2A36)
        val input = page().elements.single().input!!

        val resting = input.outlineFor(base, hovered = false, focused = false)
        val hovered = input.outlineFor(base, hovered = true, focused = false)
        val focused = input.outlineFor(base, hovered = true, focused = true)

        assertEquals(base, resting, "an untouched field must keep its authored border")
        assertTrue(hovered.r > resting.r, "hovering did not brighten the border")
        assertTrue(focused.r > hovered.r, "focus must read as stronger than hover")
    }

    @Test
    fun `an authored hover colour wins over the automatic lift`() {
        val chosen = Rgb(0x00FF88)
        val input = dev.shadr.core.page.TextInput(hoverOutline = chosen)
        assertEquals(chosen, input.outlineFor(Rgb(0x2A2A36), hovered = true, focused = false))
    }

    @Test
    fun `focusing a field brightens the border it actually renders with`() {
        val elements = TemplateResolver().resolve(
            listOf(
                mapOf(
                    "type" to "text_input", "id" to "amount",
                    "position" to mapOf("x" to 0, "y" to 0),
                    "size" to mapOf("width" to 200, "height" to 40),
                    "outline" to mapOf("size" to 1, "color" to "2a2a36"),
                ),
            ),
            dev.shadr.core.page.ScreenDef(),
        )
        val session = UiSession(
            player, Page(name = "form", elements = elements),
            PageRenderer(), emptyMap(), ActionRunner(Host()),
        )

        val resting = session.draws().single { it.key == "amount__outline" }.tint
        session.focusInput("amount")
        val focused = session.draws().single { it.key == "amount__outline" }.tint

        assertTrue(resting != focused, "focusing the field did not change the rendered border")
    }

    @Test
    fun `inputs are exposed as a snapshot that cannot be mutated behind the session`() {
        val session = session()
        session.setInputValue("amount", "42")
        val snapshot = session.inputs()
        session.setInputValue("amount", "7")
        assertEquals("42", snapshot["amount"], "inputs() handed out a live view of session state")
        assertEquals("7", session.inputValue("amount"))
    }
}
