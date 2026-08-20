/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.editor.DocumentRef
import dev.shadr.core.editor.EditorSession
import dev.shadr.core.editor.FileDocumentSource
import dev.shadr.core.editor.PageWriter
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScreenEditingTest {
    private class Workspace(dir: File) {
        val pages = File(dir, "pages").apply { mkdirs() }
        val components = File(dir, "components").apply { mkdirs() }
        val effects = File(dir, "effects").apply { mkdirs() }
        val source = FileDocumentSource(pages, components, effects)

        fun file(name: String) = File(pages, "$name.yml")
    }

    private fun workspace(): Workspace =
        Workspace(createTempDirectory("shadr-screen").toFile())

    private fun sessionFor(workspace: Workspace, name: String) =
        EditorSession(workspace.source.load(DocumentRef(name))!!)

    @Test
    fun `turning on hud mode frees the camera`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("menu"))
        val session = sessionFor(workspace, "menu")

        assertTrue(session.page.screen.locksCamera)
        assertTrue(session.patchScreen(mapOf("hud" to "true")))
        assertTrue(session.page.screen.hud)
        assertFalse(session.page.screen.locksCamera)
    }

    @Test
    fun `leaving hud mode hands the cursor back, because a cursorless page reloads as a hud`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("bars"), hud = true)
        val session = sessionFor(workspace, "bars")
        assertEquals(0.0, session.page.screen.cursorSize)

        session.patchScreen(mapOf("hud" to "false"))
        assertFalse(session.page.screen.hud)
        assertTrue(
            session.page.screen.cursorSize > 0.0,
            "a page with no cursor comes back from disk as a HUD, undoing the switch",
        )
    }

    @Test
    fun `taking the cursor away is the other way to ask for a hud`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("menu"))
        val session = sessionFor(workspace, "menu")

        session.patchScreen(mapOf("cursorSize" to "0"))
        assertTrue(session.page.screen.hud, "the editor would have shown a menu that loads as a HUD")
    }

    @Test
    fun `a screen edit is undoable, and does not disturb the elements`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("menu"))
        val session = sessionFor(workspace, "menu")
        val elements = session.page.elements

        session.patchScreen(mapOf("hud" to "true"))
        assertTrue(session.canUndo)
        session.undo()
        assertFalse(session.page.screen.hud)
        assertEquals(elements, session.page.elements)
    }

    @Test
    fun `an unchanged screen is not an edit`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("menu"))
        val session = sessionFor(workspace, "menu")

        assertFalse(session.patchScreen(mapOf("hud" to "false")))
        assertFalse(session.isDirty)
    }

    @Test
    fun `a saved hud switch survives the round trip to disk`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("menu"))
        val session = sessionFor(workspace, "menu")

        session.patchScreen(mapOf("hud" to "true", "width" to "1280", "cursorSpeed" to "3"))
        val result = PageWriter().save(workspace.file("menu"), session.original, session.page)
        assertEquals(emptyMap(), result.skipped)

        val reloaded = workspace.source.load(DocumentRef("menu"))!!.screen
        assertTrue(reloaded.hud)
        assertEquals(1280.0, reloaded.width)
        assertEquals(3.0, reloaded.cursorSpeed)
    }

    @Test
    fun `a page with no screen block at all grows one`() {
        val workspace = workspace()
        workspace.file("bare").writeText(
            """
            |name: bare
            |blocks:
            |  - type: block
            |    id: only
            |    position: {x: 0, y: 0}
            |    size: {width: 10, height: 10}
            |
            """.trimMargin(),
        )
        val session = sessionFor(workspace, "bare")

        session.patchScreen(mapOf("hud" to "true"))
        val result = PageWriter().save(workspace.file("bare"), session.original, session.page)
        assertEquals(emptyMap(), result.skipped)

        assertTrue(workspace.source.load(DocumentRef("bare"))!!.screen.hud)
    }

    @Test
    fun `the hud flag is written as a boolean, not as quoted text`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("menu"))
        val session = sessionFor(workspace, "menu")

        session.patchScreen(mapOf("hud" to "true"))
        PageWriter().save(workspace.file("menu"), session.original, session.page)

        val body = workspace.file("menu").readText()
        assertNotNull(Regex("""(?m)^\s*hud:\s*true\s*$""").find(body), body)
    }

    @Test
    fun `the playhead being down refuses a screen edit like any other`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("menu"))
        val session = sessionFor(workspace, "menu")

        session.scrub(3)
        assertFalse(session.patchScreen(mapOf("hud" to "true")))
        assertFalse(session.page.screen.hud)
    }
}
