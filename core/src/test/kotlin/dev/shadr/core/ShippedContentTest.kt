/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.page.PageLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShippedContentTest {
    private val protocol = File("../protocol").canonicalFile

    private fun loader() = PageLoader(
        pagesDir = File(protocol, "pages"),
        componentsDir = File(protocol, "components"),
        effectsDir = File(protocol, "effects"),
    )

    private fun pageFiles(): List<File> =
        File(protocol, "pages").listFiles { f -> f.isFile && f.extension == "yml" }
            .orEmpty()
            .sortedBy { it.name }

    @Test
    fun `every shipped page loads without an issue`() {
        val loader = loader()
        val components = loader.loadComponents()
        val pages = pageFiles()
        assertTrue(pages.size >= 5, "expected a page library, found ${pages.size} page(s)")

        for (file in pages) {
            val page = loader.loadPage(file, components)
            assertNotNull(page, "${file.name} did not load at all")
            assertTrue(page.elements.isNotEmpty(), "${file.name} resolved to no elements")
        }
        assertTrue(
            loader.issues.isEmpty(),
            "the shipped pages do not load cleanly:\n" + loader.issues.joinToString("\n"),
        )
    }

    @Test
    fun `every shipped component is referenced by at least one page`() {
        val components = File(protocol, "components").listFiles { f -> f.extension == "yml" }
            .orEmpty()
            .map { it.nameWithoutExtension }
        assertTrue(components.size >= 10, "expected a component library, found ${components.size}")

        val referenced = pageFiles().flatMap { file ->
            Regex("""component:\s*(\w+)""").findAll(file.readText()).map { it.groupValues[1] }
        }.toSet()

        val orphans = components.filterNot { it in referenced }
        assertTrue(
            orphans.isEmpty(),
            "these components are shipped but no page uses them, so nothing proves they work: $orphans",
        )
    }

    @Test
    fun `every shipped effect is used by at least one page or component`() {
        val effects = File(protocol, "effects").listFiles { f -> f.extension == "yml" }
            .orEmpty()
            .map { it.nameWithoutExtension }
        assertTrue(effects.size >= 5, "expected an effects library, found ${effects.size}")

        val sources = (pageFiles() + File(protocol, "components").listFiles().orEmpty())
            .filter { it.isFile }
            .joinToString("\n") { it.readText() }
        val used = Regex("""(?:hover|click)Effect:\s*(\S+)""").findAll(sources)
            .map { it.groupValues[1] }
            .toSet()

        val orphans = effects.filterNot { it in used }
        assertTrue(orphans.isEmpty(), "these effects are shipped but nothing uses them: $orphans")
    }

    @Test
    fun `no shipped page has two elements with the same id`() {
        val loader = loader()
        val components = loader.loadComponents()

        for (file in pageFiles()) {
            val page = loader.loadPage(file, components) ?: continue
            val ids = page.elements.map { it.id }.filter { it.isNotBlank() }
            val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue(
                duplicates.isEmpty(),
                "${file.name} has repeated element ids, so each pair collapses to one entity: $duplicates",
            )
        }
    }

    @Test
    fun `every shipped page renders to a sane draw list`() {
        val loader = loader()
        val components = loader.loadComponents()
        val renderer = PageRenderer()

        for (file in pageFiles()) {
            val page = loader.loadPage(file, components) ?: continue
            val rendered = renderer.render(page)
            assertTrue(rendered.draws.isNotEmpty(), "${file.name} rendered nothing")

            for (element in page.elements) {
                val where = "${file.name}:${element.id}"
                assertTrue(element.x.isFinite() && element.y.isFinite(), "$where resolved to a non-finite position")
                assertTrue(
                    element.width.isFinite() && element.height.isFinite(),
                    "$where resolved to a non-finite size",
                )

                assertTrue(
                    element.x > -4000 && element.x < 6000,
                    "$where is at x=${element.x}, which is far outside the design space",
                )
                assertTrue(
                    element.y > -4000 && element.y < 5000,
                    "$where is at y=${element.y}, which is far outside the design space",
                )
            }
        }
    }

    @Test
    fun `no resolved element still carries a template placeholder`() {
        val loader = loader()
        val components = loader.loadComponents()

        for (file in pageFiles()) {
            val page = loader.loadPage(file, components) ?: continue
            for (element in page.elements) {
                for ((what, value) in listOf("id" to element.id, "text" to element.text)) {
                    assertTrue(
                        !value.contains("${'$'}{") && !value.contains("{{"),
                        "${file.name}: ${element.id} has an unsubstituted placeholder in $what: '$value'",
                    )
                }
            }
        }
    }

    @Test
    fun `the shipped pages demonstrate live values`() {
        val withPlaceholders = pageFiles().filter {
            dev.shadr.core.page.PlaceholderScanner.hasPlaceholder(it.readText())
        }
        assertTrue(
            withPlaceholders.size >= 3,
            "only ${withPlaceholders.size} shipped page(s) show a live value; the library is " +
                "back to being a wall of literals",
        )
    }

    @Test
    fun `every placeholder the library uses is one shadr can answer`() {
        val known = dev.shadr.core.page.BuiltinPlaceholders.NAMES.toSet()
        val used = mutableMapOf<String, MutableSet<String>>()

        val loader = loader()
        val components = loader.loadComponents()
        val inputsByPage = mutableMapOf<String, Set<String>>()
        for (file in pageFiles()) {
            val page = loader.loadPage(file, components) ?: continue
            inputsByPage[file.name] = page.elements
                .filter { it.type == dev.shadr.core.page.ElementType.TEXT_INPUT }
                .map { dev.shadr.core.page.InputPlaceholders.PREFIX + it.id.lowercase() }
                .toSet()
            for (element in page.elements) {
                val sources = listOf(element.text) +
                    element.dynamic.values +
                    element.interaction.onClick.map { it.argument } +
                    element.interaction.onLeftClick.map { it.argument } +
                    element.interaction.onRightClick.map { it.argument } +
                    element.input?.onSubmit.orEmpty().map { it.argument }
                for (source in sources) {
                    Regex("""%([A-Za-z0-9_:.\-]+)%""").findAll(source).forEach { match ->
                        used.getOrPut(match.groupValues[1].lowercase()) { mutableSetOf() } += file.name
                    }
                }
            }
        }

        val unknown = used.filterKeys { it !in known }.filterNot { (name, pages) ->
            name.startsWith(dev.shadr.core.page.InputPlaceholders.PREFIX) &&
                pages.all { name in inputsByPage[it].orEmpty() }
        }
        assertTrue(
            unknown.isEmpty(),
            "these placeholders would render as literal text on a bare server " +
                "(an %input_x% must name a text_input on the same page): " +
                unknown.entries.joinToString { "%${it.key}% in ${it.value}" },
        )
    }

    @Test
    fun `percent signs in the library's prose survive resolution`() {
        val loader = loader()
        val components = loader.loadComponents()
        val resolver = dev.shadr.core.page.PlaceholderResolver { _, _ -> "SUBSTITUTED" }
        val player = PlayerId("00000000-0000-0000-0000-000000000002")

        for (file in pageFiles()) {
            val page = loader.loadPage(file, components) ?: continue
            for (element in page.elements) {
                val text = element.text

                val stripped = Regex("""%[A-Za-z0-9_]+%""").replace(text, "")
                if (!stripped.contains('%')) continue
                val resolved = dev.shadr.core.page.PlaceholderScanner.apply(text, player, resolver)
                val resolvedStripped = Regex("""SUBSTITUTED""").replace(resolved, "")
                assertTrue(
                    resolvedStripped.count { it == '%' } == stripped.count { it == '%' },
                    "${file.name}: '$text' lost a literal percent sign to the scanner",
                )
            }
        }
    }

    @Test
    fun `the demo page still resolves to the page the harness expects`() {
        val loader = loader()
        val page = loader.loadPage(File(protocol, "pages/demo.yml"), loader.loadComponents())
        assertNotNull(page)
        assertEquals("demo", page.name)
        assertTrue(
            page.elements.any { it.id == "card_button" },
            "the demo page lost the button every manual test clicks",
        )
    }
}
