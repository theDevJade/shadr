/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.editor.DocumentKind
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentCreationTest {
    private class Workspace(val dir: File) {
        val pages = File(dir, "pages").apply { mkdirs() }
        val components = File(dir, "components").apply { mkdirs() }
        val effects = File(dir, "effects").apply { mkdirs() }
        val source = FileDocumentSource(pages, components, effects)
    }

    private fun workspace() = Workspace(createTempDirectory("shadr-new-doc").toFile())

    @Test
    fun `a new page lands on disk and loads back as an ordinary page`() {
        val workspace = workspace()
        assertNull(workspace.source.create(DocumentRef("menu")))

        assertTrue(File(workspace.pages, "menu.yml").isFile)
        val page = workspace.source.load(DocumentRef("menu"))
        assertNotNull(page)
        assertEquals("menu", page.name)
        assertFalse(page.screen.hud, "a plain page has to keep taking the mouse")
        assertTrue(page.elements.isNotEmpty(), "an empty starter would leave nothing to click")
        assertTrue(workspace.source.list().contains(DocumentRef("menu", DocumentKind.PAGE)))
    }

    @Test
    fun `a page asked for as a hud comes back as one`() {
        val workspace = workspace()
        assertNull(workspace.source.create(DocumentRef("bars"), hud = true))

        val page = workspace.source.load(DocumentRef("bars"))
        assertNotNull(page)
        assertTrue(page.screen.hud)
        assertFalse(page.screen.locksCamera, "a HUD must leave the player free to move")
    }

    @Test
    fun `a page keeps the size it was asked for`() {
        val workspace = workspace()
        assertNull(workspace.source.create(DocumentRef("wide"), width = 2560.0, height = 1440.0))

        val screen = workspace.source.load(DocumentRef("wide"))!!.screen
        assertEquals(2560.0, screen.width)
        assertEquals(1440.0, screen.height)
    }

    @Test
    fun `a new component resolves through the template engine`() {
        val workspace = workspace()
        assertNull(workspace.source.create(DocumentRef("chip", DocumentKind.COMPONENT)))

        val page = workspace.source.load(DocumentRef("chip", DocumentKind.COMPONENT))
        assertNotNull(page)
        assertTrue(
            page.elements.any { it.text == "chip" },
            "the starter's parameters did not reach its blocks: ${page.elements.map { it.id }}",
        )
        assertTrue(page.elements.all { it.width > 0.0 }, "a parameter came through unresolved")
    }

    @Test
    fun `a name already taken is refused rather than overwritten`() {
        val workspace = workspace()
        assertNull(workspace.source.create(DocumentRef("menu")))
        val before = File(workspace.pages, "menu.yml").readText()

        val refusal = workspace.source.create(DocumentRef("menu"))
        assertNotNull(refusal)
        assertTrue(refusal.contains("already exists"), refusal)
        assertEquals(before, File(workspace.pages, "menu.yml").readText())
    }

    @Test
    fun `a name that would escape the pages directory is refused`() {
        val workspace = workspace()
        for (name in listOf("../evil", "Menu", "with space", "", "a/b")) {
            assertNotNull(workspace.source.create(DocumentRef(name)), "'$name' was accepted")
        }
        assertEquals(emptyList(), workspace.pages.listFiles()?.toList() ?: emptyList<File>())
    }

    @Test
    fun `a page and a component may share a name because they live apart`() {
        val workspace = workspace()
        assertNull(workspace.source.create(DocumentRef("card")))
        assertNull(workspace.source.create(DocumentRef("card", DocumentKind.COMPONENT)))

        assertEquals(
            listOf(
                DocumentRef("card", DocumentKind.PAGE),
                DocumentRef("card", DocumentKind.COMPONENT),
            ),
            workspace.source.list(),
        )
    }

    @Test
    fun `renaming a page moves the file and the name inside it`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("menu"))

        assertNull(workspace.source.rename(DocumentRef("menu"), "lobby"))
        assertFalse(File(workspace.pages, "menu.yml").exists())
        assertEquals("lobby", workspace.source.load(DocumentRef("lobby"))?.name)
    }

    @Test
    fun `renaming onto a name in use is refused`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("menu"))
        workspace.source.create(DocumentRef("lobby"))

        val refusal = workspace.source.rename(DocumentRef("menu"), "lobby")
        assertNotNull(refusal)
        assertTrue(File(workspace.pages, "menu.yml").isFile, "the source was moved anyway")
    }

    @Test
    fun `a duplicate is an independent copy under the new name`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("menu"))

        assertNull(workspace.source.duplicate(DocumentRef("menu"), "menu_copy"))
        assertEquals("menu", workspace.source.load(DocumentRef("menu"))?.name)
        assertEquals("menu_copy", workspace.source.load(DocumentRef("menu_copy"))?.name)
    }

    @Test
    fun `deleting removes the file, and deleting nothing says so`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("menu"))

        assertNull(workspace.source.delete(DocumentRef("menu")))
        assertFalse(File(workspace.pages, "menu.yml").exists())
        assertNotNull(workspace.source.delete(DocumentRef("menu")))
    }

    @Test
    fun `a starter page accepts the elements the editor adds to it`() {
        val workspace = workspace()
        workspace.source.create(DocumentRef("menu"))
        val file = File(workspace.pages, "menu.yml")

        val session = EditorSession(workspace.source.load(DocumentRef("menu"))!!)
        session.add("block", 40.0, 60.0, 200.0, 80.0)

        val result = PageWriter().save(file, session.original, session.page)
        assertEquals(emptyMap(), result.skipped)
        assertTrue(result.saved > 0)

        val reloaded = workspace.source.load(DocumentRef("menu"))!!
        assertNotNull(reloaded.elements.firstOrNull { it.x == 40.0 && it.width == 200.0 })
    }
}
