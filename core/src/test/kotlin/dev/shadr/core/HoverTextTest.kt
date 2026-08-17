/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.action.ActionRunner
import dev.shadr.core.hud.HudDiff
import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Interaction
import dev.shadr.core.page.Page
import dev.shadr.core.page.PlaceholderResolver
import dev.shadr.core.page.ScreenDef
import dev.shadr.core.session.UiSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HoverTextTest {
    private val player = PlayerId("00000000-0000-0000-0000-000000000002")

    private object SilentHost : dev.shadr.core.action.ActionHost {
        override fun runAsPlayer(player: PlayerId, command: String) = Unit
        override fun runAsConsole(command: String) = Unit
        override fun message(player: PlayerId, text: String) = Unit
        override fun playSound(player: PlayerId, sound: String, volume: Double) = Unit
        override fun closePage(player: PlayerId) = Unit
        override fun openPage(player: PlayerId, page: String, replacing: Boolean) = Unit
        override fun teleport(player: PlayerId, destination: String) = Unit
        override fun hasPermission(player: PlayerId, permission: String) = true
        override fun scheduleTicks(ticks: Long, task: () -> Unit) = task()
    }

    private fun sessionOf(
        hoverText: String?,
        placeholders: PlaceholderResolver = PlaceholderResolver.NONE,
    ): UiSession = UiSession(
        player = player,
        page = Page(
            name = "t",
            screen = ScreenDef(width = 1000.0, height = 800.0),
            elements = listOf(
                Element(
                    id = "button",
                    type = ElementType.BLOCK,
                    x = 100.0,
                    y = 100.0,
                    width = 200.0,
                    height = 100.0,
                    interaction = Interaction(hoverText = hoverText),
                ),
            ),
        ),
        renderer = PageRenderer(),
        effects = emptyMap(),
        actionRunner = ActionRunner(SilentHost),
        placeholders = placeholders,
    )

    private fun UiSession.tooltip() =
        draws().firstOrNull { it.key == UiSession.HOVER_TEXT_ELEMENT_ID }

    private fun UiSession.hover(x: Double, y: Double) =
        update(ScreenPos(x, y), 0.0, 0.0, 0)

    @Test
    fun `hovering an element with hover text draws it`() {
        val session = sessionOf("Open the shop")
        assertNull(session.tooltip(), "a tooltip was drawn before anything was hovered")

        session.hover(150.0, 140.0)
        val drawn = assertNotNull(session.tooltip(), "hoverText never reached the hud")
        assertTrue(drawn.content.contains("Open the shop"), "wrong text: ${drawn.content}")
    }

    @Test
    fun `the tooltip goes away when the cursor leaves`() {
        val session = sessionOf("Open the shop")
        session.hover(150.0, 140.0)
        assertNotNull(session.tooltip())

        session.hover(900.0, 700.0)
        assertNull(session.tooltip(), "the tooltip outlived the hover")
    }

    @Test
    fun `an element without hover text draws no tooltip`() {
        val session = sessionOf(null)
        session.hover(150.0, 140.0)
        assertNull(session.tooltip())

        val blank = sessionOf("   ")
        blank.hover(150.0, 140.0)
        assertNull(blank.tooltip(), "whitespace is not a tooltip")
    }

    @Test
    fun `the tooltip follows the cursor`() {
        val session = sessionOf("Open the shop")
        session.hover(150.0, 140.0)
        val first = assertNotNull(session.tooltip()).translation

        session.hover(220.0, 170.0)
        val second = assertNotNull(session.tooltip()).translation

        assertTrue(first != second, "the tooltip stayed put while the cursor moved")
    }

    @Test
    fun `the tooltip sits under the cursor in the draw order`() {
        val session = sessionOf("Open the shop")
        session.hover(150.0, 140.0)
        val draws = session.draws()
        val tooltip = draws.indexOfFirst { it.key == UiSession.HOVER_TEXT_ELEMENT_ID }
        val cursor = draws.indexOfFirst { it.key == UiSession.CURSOR_ELEMENT_ID }
        assertTrue(tooltip in 0 until cursor, "tooltip $tooltip, cursor $cursor")
    }

    @Test
    fun `placeholders in hover text are resolved`() {
        val session = sessionOf(
            "%shadr_online% online",
            PlaceholderResolver { _, name -> if (name == "shadr_online") "128" else null },
        )
        session.hover(150.0, 140.0)
        val drawn = assertNotNull(session.tooltip())
        assertTrue(drawn.content.contains("128 online"), "wrong text: ${drawn.content}")
    }

    @Test
    fun `the tooltip is removed from the hud when the hover ends`() {
        val session = sessionOf("Open the shop")
        session.hover(150.0, 140.0)
        val hovering = session.draws().associateBy { it.key }

        session.hover(900.0, 700.0)
        val diff = HudDiff.between(hovering, session.draws())
        assertEquals(
            listOf(UiSession.HOVER_TEXT_ELEMENT_ID), diff.removed,
            "the hud was never told to drop the tooltip",
        )
    }

    @Test
    fun `the tooltip stays on screen at the right edge`() {
        val session = UiSession(
            player = player,
            page = Page(
                name = "t",
                screen = ScreenDef(width = 1000.0, height = 800.0),
                elements = listOf(
                    Element(
                        id = "edge",
                        type = ElementType.BLOCK,
                        x = 880.0,
                        y = 100.0,
                        width = 120.0,
                        height = 100.0,
                        interaction = Interaction(hoverText = "Open the shop"),
                    ),
                ),
            ),
            renderer = PageRenderer(),
            effects = emptyMap(),
            actionRunner = ActionRunner(SilentHost),
        )

        session.hover(1000.0 - UiSession.HOVER_TEXT_SIZE, 140.0)
        val atLimit = assertNotNull(session.tooltip()).translation

        session.hover(999.0, 140.0)
        val past = assertNotNull(session.tooltip()).translation
        assertEquals(atLimit, past, "the tooltip ran off the right edge")

        session.hover(900.0, 140.0)
        assertTrue(
            assertNotNull(session.tooltip()).translation != atLimit,
            "the tooltip is pinned even where there is room",
        )
    }
}
