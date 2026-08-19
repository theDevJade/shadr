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
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Page
import dev.shadr.core.page.Slider
import dev.shadr.core.page.TemplateResolver
import dev.shadr.core.session.UiSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlsTest {

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

    private fun pageOf(vararg blocks: Map<String, Any?>) =
        Page(name = "p", elements = TemplateResolver().resolve(blocks.toList(), dev.shadr.core.page.ScreenDef()))

    private fun toggleBlock(extra: Map<String, Any?> = emptyMap()) = mapOf(
        "type" to "toggle", "id" to "sound",
        "position" to mapOf("x" to 100, "y" to 100),
        "size" to mapOf("width" to 60, "height" to 30),
    ) + extra

    private fun sliderBlock(extra: Map<String, Any?> = emptyMap()) = mapOf(
        "type" to "slider", "id" to "volume",
        "position" to mapOf("x" to 100, "y" to 100),
        "size" to mapOf("width" to 200, "height" to 20),
    ) + extra

    @Test
    fun `a toggle parses its own keys`() {
        val element = pageOf(toggleBlock(mapOf("value" to true))).elements.single()
        assertEquals(ElementType.TOGGLE, element.type)
        assertTrue(element.toggle!!.value)
    }

    @Test
    fun `a toggle draws a track and a knob`() {
        val draws = PageRenderer().render(pageOf(toggleBlock())).draws.associateBy { it.key }
        assertTrue("sound" in draws, "the track is missing, got ${draws.keys}")
        assertTrue("sound__knob" in draws, "the knob is missing, got ${draws.keys}")
    }

    @Test
    fun `the knob slides across when the toggle is on`() {
        fun knobX(on: Boolean): Double = PageRenderer()
            .render(pageOf(toggleBlock(mapOf("value" to on))))
            .draws.single { it.key == "sound__knob" }.translation.x

        assertTrue(knobX(true) != knobX(false), "the knob does not move, so the state is invisible")
    }

    @Test
    fun `clicking a toggle flips it and fires its action`() {
        val host = Host()
        val session = UiSession(
            player, pageOf(toggleBlock(mapOf("onChangeAction" to listOf("message: flipped")))),
            PageRenderer(), emptyMap(), ActionRunner(host),
        )
        session.update(ScreenPos(120.0, 110.0), 0.0, 0.0, 0)
        session.click(false)

        assertEquals("true", session.inputValue("sound"))
        assertEquals(listOf("flipped"), host.messages)

        session.click(false)
        assertEquals("false", session.inputValue("sound"), "a second click did not turn it back off")
    }

    @Test
    fun `a slider draws a track, a fill and a knob`() {
        val draws = PageRenderer().render(pageOf(sliderBlock(mapOf("value" to 50)))).draws.associateBy { it.key }
        for (key in listOf("volume", "volume__fill", "volume__knob")) {
            assertTrue(key in draws, "$key is missing, got ${draws.keys}")
        }
    }

    @Test
    fun `clicking along a slider sets the value from where it was clicked`() {
        val session = UiSession(
            player, pageOf(sliderBlock()), PageRenderer(), emptyMap(), ActionRunner(Host()),
        )
        session.update(ScreenPos(300.0, 110.0), 0.0, 0.0, 0)
        session.click(false)
        assertEquals("100", session.inputValue("volume"), "clicking the far end did not reach the max")

        session.update(ScreenPos(100.0, 110.0), 0.0, 0.0, 0)
        session.click(false)
        assertEquals("0", session.inputValue("volume"), "clicking the near end did not reach the min")
    }

    @Test
    fun `a slider snaps to its step`() {
        val slider = Slider(min = 0.0, max = 10.0, step = 5.0)
        assertEquals(5.0, slider.clamp(6.0))
        assertEquals(10.0, slider.clamp(9.0))
        assertEquals(0.0, slider.clamp(-3.0), "a value below the range must clamp, not wrap")
    }

    @Test
    fun `a slider reports where it sits as a fraction`() {
        val slider = Slider(min = 0.0, max = 200.0)
        assertEquals(0.5, slider.fractionOf(100.0))
        assertEquals(1.0, slider.fractionOf(9999.0), "an out of range value must clamp to the end")
    }

    @Test
    fun `controls take input with nothing bound`() {
        for (block in listOf(toggleBlock(), sliderBlock())) {
            val rendered = PageRenderer().render(pageOf(block))
            assertTrue(
                rendered.hitRegions.single().interactive,
                "${block["type"]} is driven by clicking it, so it must take input without an action",
            )
        }
    }

    @Test
    fun `a control's value is readable as a placeholder`() {
        val session = UiSession(
            player, pageOf(toggleBlock(), sliderBlock()), PageRenderer(), emptyMap(), ActionRunner(Host()),
        )
        session.update(ScreenPos(120.0, 110.0), 0.0, 0.0, 0)
        session.click(false)

        val resolver = dev.shadr.core.page.InputPlaceholders { _, id ->
            session.inputs().entries.firstOrNull { it.key.equals(id, true) }?.value
        }
        assertEquals(
            "sound is true",
            dev.shadr.core.page.PlaceholderScanner.apply("sound is %input_sound%", player, resolver),
        )
    }
}
