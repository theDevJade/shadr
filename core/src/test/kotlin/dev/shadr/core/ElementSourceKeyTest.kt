/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.editor.PageWriter
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.PageLoader
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ElementSourceKeyTest {
    private val source = """
        |name: media
        |screen:
        |  width: 1920
        |  height: 1080
        |
        |blocks:
        |  # the panel that plays a clip
        |  - type: video
        |    id: panel
        |    layer: 30.0
        |    video: demo
        |    position:
        |      x: 0
        |      y: 0
        |    size:
        |      width: 1920
        |      height: 1080
        |
        |  - type: shader
        |    id: swirl
        |    layer: 20.0
        |    shader: portal
        |    position:
        |      x: 10
        |      y: 20
        |    size:
        |      width: 64
        |      height: 64
        |
    """.trimMargin()

    private fun workspace(): Triple<File, File, PageLoader> {
        val dir = createTempDirectory("shadr-source-key").toFile()
        val pages = File(dir, "pages").apply { mkdirs() }
        File(dir, "components").mkdirs()
        File(dir, "effects").mkdirs()
        val file = File(pages, "media.yml").apply { writeText(source) }
        return Triple(dir, file, PageLoader(pages, File(dir, "components"), File(dir, "effects")))
    }

    @Test
    fun `each type reads its own key into item`() {
        val (_, file, loader) = workspace()
        val page = loader.loadPage(file)!!
        assertEquals("demo", page.elements.first { it.id == "panel" }.item)
        assertEquals("portal", page.elements.first { it.id == "swirl" }.item)
    }

    @Test
    fun `editing a clip writes video and leaves the shader alone`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!

        val edited = original.elements.map {
            if (it.id == "panel") it.copy(item = "intro") else it
        }
        val result = PageWriter().save(file, original, original.copy(elements = edited))
        assertEquals(1, result.saved)
        assertTrue(result.ok, "unexpected skips: ${result.skipped}")

        val text = file.readText()
        assertTrue(text.contains("video: intro"), "the clip was not written:\n$text")
        assertTrue(text.contains("shader: portal"), "clobbered an unrelated element")
        assertTrue(text.contains("# the panel that plays a clip"), "lost a comment")

        val reloaded = loader.loadPage(file)
        assertNotNull(reloaded)
        assertEquals("intro", reloaded.elements.first { it.id == "panel" }.item)
    }

    @Test
    fun `a page opened and saved untouched keeps its clip`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!
        PageWriter().save(file, original, original)

        val reloaded = loader.loadPage(file)!!
        assertEquals("demo", reloaded.elements.first { it.id == "panel" }.item)
        assertTrue(file.readText().contains("video: demo"))
    }

    @Test
    fun `a new element of each type writes the key its loader reads back`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!

        val added = original.elements +
            Element(id = "clip2", type = ElementType.VIDEO, item = "outro", width = 64.0, height = 64.0) +
            Element(id = "glow", type = ElementType.SHADER, item = "explosion", width = 64.0, height = 64.0) +
            Element(id = "prop", type = ElementType.ITEM, item = "minecraft:stone", width = 64.0, height = 64.0)

        val result = PageWriter().save(file, original, original.copy(elements = added))
        assertEquals(3, result.saved)
        assertTrue(result.ok, "unexpected skips: ${result.skipped}")

        val reloaded = loader.loadPage(file)!!
        assertEquals("outro", reloaded.elements.first { it.id == "clip2" }.item)
        assertEquals("explosion", reloaded.elements.first { it.id == "glow" }.item)
        assertEquals("minecraft:stone", reloaded.elements.first { it.id == "prop" }.item)

        val text = file.readText()
        assertTrue(text.contains("video: outro"), "a new video element wrote the wrong key:\n$text")
        assertTrue(text.contains("shader: explosion"), "a new shader element wrote the wrong key:\n$text")
        assertTrue(text.contains("item: minecraft:stone"), "a new item element wrote the wrong key:\n$text")
    }
}
