/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.editor.EditorSession
import dev.shadr.core.editor.PageWriter
import dev.shadr.core.page.ActionSpec
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Page
import dev.shadr.core.page.PageLoader
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionEditingTest {
    private fun session() = EditorSession(
        Page(
            name = "t",
            elements = listOf(Element(id = "a", type = ElementType.BLOCK, width = 40.0, height = 20.0)),
        ),
    )

    private fun EditorSession.element() = page.elements.first { it.id == "a" }

    @Test
    fun `actions are edited as one verb and argument per line`() {
        val session = session()
        session.patch("a", mapOf("interaction.onClick" to "sound shadr.click\nclose "))

        assertEquals(
            listOf(ActionSpec("sound", "shadr.click"), ActionSpec("close", "")),
            session.element().interaction.onClick,
        )
    }

    @Test
    fun `blank lines and a bare verb are tolerated`() {
        val session = session()
        session.patch("a", mapOf("interaction.onRightClick" to "\n  close  \n\n"))

        assertEquals(listOf(ActionSpec("close", "")), session.element().interaction.onRightClick)
    }

    @Test
    fun `clearing the text clears the actions`() {
        val session = session()
        session.patch("a", mapOf("interaction.onClick" to "close"))
        session.patch("a", mapOf("interaction.onClick" to ""))

        assertTrue(session.element().interaction.onClick.isEmpty())
    }

    @Test
    fun `hover text and effects round-trip, and blanking them clears them`() {
        val session = session()
        session.patch("a", mapOf("interaction.hoverText" to "Click me"))
        assertEquals("Click me", session.element().interaction.hoverText)

        session.patch("a", mapOf("interaction.hoverText" to ""))
        assertNull(session.element().interaction.hoverText)
    }

    @Test
    fun `an edited action survives a save and a reload`() {
        val dir = createTempDirectory("shadr-actions").toFile()
        val file = File(dir, "page.yml")
        file.writeText(
            """
            name: t
            screen: {width: 1920, height: 1080}
            blocks:
              - type: block
                id: a
                position: {x: 0, y: 0}
                size: {width: 40, height: 20}
            """.trimIndent(),
        )

        val loader = PageLoader(pagesDir = dir, componentsDir = dir, effectsDir = dir)
        val original = loader.loadPage(file) ?: error("page did not load")
        val session = EditorSession(original)
        session.patch("a", mapOf("interaction.onClick" to "sound shadr.click\nclose "))
        session.patch("a", mapOf("interaction.hoverText" to "Press"))

        val result = PageWriter().save(file, original, session.page)
        assertTrue(result.skipped.isEmpty(), "skipped: ${result.skipped}")

        val reread = PageLoader(pagesDir = dir, componentsDir = dir, effectsDir = dir).loadPage(file)
            ?: error("page did not reload")
        val element = reread.elements.first { it.id == "a" }

        assertEquals(
            listOf(ActionSpec("sound", "shadr.click"), ActionSpec("close", "")),
            element.interaction.onClick,
            file.readText(),
        )
        assertEquals("Press", element.interaction.hoverText)
    }

    @Test
    fun `an element with an action takes input, so the round-trip is usable`() {
        val session = session()
        session.patch("a", mapOf("interaction.onClick" to "close"))

        val rendered = dev.shadr.core.hud.PageRenderer().render(session.page)
        assertEquals("a", rendered.hitTest(10.0, 10.0)?.elementId)
    }
}
