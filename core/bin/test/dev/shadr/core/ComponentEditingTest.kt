/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.editor.DocumentKind
import dev.shadr.core.editor.DocumentRef
import dev.shadr.core.editor.FileDocumentSource
import dev.shadr.core.editor.PageWriter
import dev.shadr.core.page.PageLoader
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ComponentEditingTest {
    private fun workspace(): Triple<File, File, FileDocumentSource> {
        val dir = createTempDirectory("shadr-component").toFile()
        val pages = File(dir, "pages").apply { mkdirs() }
        val components = File(dir, "components").apply { mkdirs() }
        val effects = File(dir, "effects").apply { mkdirs() }

        File(components, "chip.yml").writeText(
            """
            |params:
            |  id: "chip"
            |  label: "Label"
            |  accent: "4cc9f0"
            |blocks:
            |  # The chip's body.
            |  - type: block_rounded
            |    id: "${'$'}{id}_bg"
            |    layer: 10.0
            |    color: 15151c
            |    position: {x: 0, y: 0}
            |    size: {width: 162, height: 88}
            |    children:
            |      - type: block
            |        id: "${'$'}{id}_accent"
            |        layer: 11.0
            |        color: "${'$'}{accent}"
            |        position: {x: 0, y: 0}
            |        size: {width: 3, height: 88}
            |
            """.trimMargin(),
        )
        File(pages, "demo.yml").writeText(
            """
            |name: demo
            |blocks:
            |  - type: component
            |    component: chip
            |    params: {id: one}
            |
            """.trimMargin(),
        )
        return Triple(dir, File(components, "chip.yml"), FileDocumentSource(pages, components, effects))
    }

    @Test
    fun `a component resolves to what a default instantiation looks like`() {
        val (_, _, source) = workspace()
        val page = source.load(DocumentRef("chip", DocumentKind.COMPONENT))
        assertNotNull(page)

        assertEquals(listOf("chip_bg", "chip_accent"), page.elements.map { it.id })

        assertEquals(162.0, page.elements.first().width)
        assertEquals(0x4CC9F0, page.elements.last().color.packed)
    }

    @Test
    fun `components appear alongside pages in the document list`() {
        val (_, _, source) = workspace()
        val listed = source.list()
        assertTrue(listed.any { it.name == "demo" && it.kind == DocumentKind.PAGE })
        assertTrue(listed.any { it.name == "chip" && it.kind == DocumentKind.COMPONENT })
    }

    @Test
    fun `an edit to a component is written back to the component file`() {
        val (dir, file, source) = workspace()
        val ref = DocumentRef("chip", DocumentKind.COMPONENT)
        val original = source.load(ref)!!

        val widened = original.elements.map {
            if (it.id == "chip_bg") it.copy(width = 200.0) else it
        }
        val result = PageWriter().save(file, original, original.copy(elements = widened))

        assertEquals(1, result.saved)
        assertTrue(result.ok, "unexpected skips: ${result.skipped}")

        val text = file.readText()
        assertTrue(text.contains("width: 200"), "component not updated:\n$text")
        assertTrue(text.contains("# The chip's body."), "lost a comment")
        assertTrue(text.contains("\${id}_bg"), "clobbered the parameter placeholder")

        val loader = PageLoader(File(dir, "pages"), File(dir, "components"), File(dir, "effects"))
        val demo = loader.loadPage(File(File(dir, "pages"), "demo.yml"))!!
        assertEquals(200.0, demo.elements.first { it.id == "one_bg" }.width)
    }

    @Test
    fun `a nested element inside a component saves relative to its parent`() {
        val (_, file, source) = workspace()
        val ref = DocumentRef("chip", DocumentKind.COMPONENT)
        val original = source.load(ref)!!

        val nudged = original.elements.map {
            if (it.id == "chip_accent") it.copy(x = it.x + 12) else it
        }
        PageWriter().save(file, original, original.copy(elements = nudged))

        val reloaded = source.load(ref)!!
        assertEquals(12.0, reloaded.elements.first { it.id == "chip_accent" }.x)
    }
}
