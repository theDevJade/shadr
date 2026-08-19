/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.editor.PageWriter
import dev.shadr.core.page.PageLoader
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComponentMoveTest {

    private val component = """
        |blocks:
        |  - type: block_rounded
        |    id: card_bg
        |    layer: 10.0
        |    color: '15151c'
        |    position: {x: 0, y: 0}
        |    size: {width: 200, height: 60}
        |
        |  - type: text
        |    id: card_label
        |    layer: 20.0
        |    color: 'ffffff'
        |    position: {x: 20, y: 20}
        |    size: {width: 24, height: 24}
        |    text: 'hi'
        |
    """.trimMargin()

    private val page = """
        |name: demo
        |screen:
        |  width: 1920
        |  height: 1080
        |
        |blocks:
        |  - type: component
        |    component: card
        |    position:
        |      x: 100
        |      y: 50
        |
    """.trimMargin()

    private fun workspace(): Triple<File, File, PageLoader> {
        val dir = createTempDirectory("shadr-component-move").toFile()
        val pages = File(dir, "pages").apply { mkdirs() }
        val components = File(dir, "components").apply { mkdirs() }
        val effects = File(dir, "effects").apply { mkdirs() }
        File(components, "card.yml").writeText(component)
        val file = File(pages, "demo.yml").apply { writeText(page) }
        return Triple(dir, file, PageLoader(pages, components, effects))
    }

    @Test
    fun `moving a component's parts writes the move onto the instance`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!
        assertEquals(2, original.elements.size, "the component did not expand")

        val moved = original.elements.map { it.copy(x = it.x + 40.0, y = it.y - 15.0) }
        val result = PageWriter().save(file, original, original.copy(elements = moved))

        assertTrue(result.ok, "the move was refused: ${result.skipped}")
        assertEquals(1, result.saved, "the instance should be written once, not once per part")

        val reloaded = loader.loadPage(file)!!
        val before = original.elements.associateBy { it.id }
        for (element in reloaded.elements) {
            assertEquals(before[element.id]!!.x + 40.0, element.x, "${element.id} did not move")
            assertEquals(before[element.id]!!.y - 15.0, element.y, "${element.id} did not move")
        }
        assertTrue(file.readText().contains("x: 140"), "the instance x was not rewritten:\n${file.readText()}")
        assertTrue(file.readText().contains("y: 35"), "the instance y was not rewritten:\n${file.readText()}")
    }

    @Test
    fun `dragging one part of a component moves the whole instance`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!

        val nudged = original.elements.map {
            if (it.id == "card_label") it.copy(x = it.x + 10.0) else it
        }
        val result = PageWriter().save(file, original, original.copy(elements = nudged))

        assertTrue(result.ok, "the drag was refused: ${result.skipped}")

        val reloaded = loader.loadPage(file)!!.elements.associate { it.id to it.x }
        assertEquals(110.0, reloaded["card_bg"], "the part that was not dragged stayed behind")
        assertEquals(130.0, reloaded["card_label"], "the dragged part did not move")
    }

    @Test
    fun `parts that disagree on the move are refused rather than fighting`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!

        val conflicting = original.elements.map {
            if (it.id == "card_label") it.copy(x = it.x + 10.0) else it.copy(x = it.x + 40.0)
        }
        val result = PageWriter().save(file, original, original.copy(elements = conflicting))

        assertTrue(!result.ok, "two different deltas cannot both be written to one instance")
        assertTrue(
            result.skipped.values.any { it.contains("cannot move independently") },
            "unhelpful reason: ${result.skipped}",
        )
    }

    @Test
    fun `resizing a component's part is refused and says where to change it`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!

        val resized = original.elements.map {
            if (it.id == "card_bg") it.copy(width = it.width + 50.0) else it
        }
        val result = PageWriter().save(file, original, original.copy(elements = resized))

        assertTrue(!result.ok)
        assertTrue(
            result.skipped.values.any { it.contains("component file") },
            "unhelpful reason: ${result.skipped}",
        )
    }

    @Test
    fun `a plain element still saves normally alongside a component`() {
        val (_, file, loader) = workspace()
        file.writeText(
            file.readText() + "\n  - type: block\n    id: solo\n    position: {x: 5, y: 5}\n" +
                "    size: {width: 10, height: 10}\n",
        )
        val original = loader.loadPage(file)!!
        val moved = original.elements.map { if (it.id == "solo") it.copy(x = 400.0) else it }

        val result = PageWriter().save(file, original, original.copy(elements = moved))

        assertTrue(result.ok, "unexpected skips: ${result.skipped}")
        assertEquals(400.0, loader.loadPage(file)!!.elements.single { it.id == "solo" }.x)
    }
}
