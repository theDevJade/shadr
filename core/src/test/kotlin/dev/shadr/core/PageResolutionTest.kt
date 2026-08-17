/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.page.PageLoader
import dev.shadr.core.page.ScreenDef
import dev.shadr.core.page.TemplateResolver
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PageResolutionTest {
    private fun blocks(yaml: String): List<Any?> =
        (Yaml().load<Any?>("blocks:\n$yaml") as Map<*, *>)["blocks"] as List<Any?>

    @Test
    fun `children are flattened into absolute coordinates`() {
        val elements = TemplateResolver().resolve(
            blocks(
                """
                  - type: block
                    id: parent
                    position: {x: 100, y: 50}
                    size: {width: 200, height: 100}
                    children:
                      - type: block
                        id: child
                        position: {x: 20, y: 10}
                        size: {width: 40, height: 20}
                """.trimIndent(),
            ),
            ScreenDef(),
        )

        val child = elements.first { it.id == "child" }
        assertEquals(120.0, child.x)
        assertEquals(60.0, child.y)

        assertEquals(2, elements.size)
    }

    @Test
    fun `component params substitute and keep their type`() {
        val component = Yaml().load<Map<String, Any?>>(
            """
            params:
              id: "chip"
              value: "0"
              w: 100
            blocks:
              - type: block
                id: "${'$'}{id}_bg"
                size: {width: "${'$'}{w}", height: 40}
              - type: text
                id: "${'$'}{id}_label"
                text: "value: ${'$'}{value}"
            """.trimIndent(),
        )

        val elements = TemplateResolver(mapOf("chip" to component)).resolve(
            blocks(
                """
                  - type: component
                    component: chip
                    params:
                      id: cpu
                      value: "97%"
                """.trimIndent(),
            ),
            ScreenDef(),
        )

        assertEquals(listOf("cpu_bg", "cpu_label"), elements.map { it.id })

        assertEquals(100.0, elements[0].width)
        assertEquals("value: 97%", elements[1].text)
    }

    @Test
    fun `grid_block advances children by size plus gap`() {
        val elements = TemplateResolver().resolve(
            blocks(
                """
                  - type: grid_block
                    direction: row
                    gap: 10
                    position: {x: 0, y: 0}
                    children:
                      - {type: block, id: a, size: {width: 50, height: 20}}
                      - {type: block, id: b, size: {width: 30, height: 20}}
                      - {type: block, id: c, size: {width: 40, height: 20}}
                """.trimIndent(),
            ),
            ScreenDef(),
        )
        assertEquals(listOf(0.0, 60.0, 100.0), elements.map { it.x })
    }

    @Test
    fun `grid_block advances component children by their produced extent`() {
        val component = Yaml().load<Map<String, Any?>>(
            """
            params:
              id: "chip"
              w: 160
            blocks:
              - type: block
                id: "${'$'}{id}"
                size: {width: "${'$'}{w}", height: 72}
            """.trimIndent(),
        )

        val elements = TemplateResolver(mapOf("chip" to component)).resolve(
            blocks(
                """
                  - type: grid_block
                    direction: row
                    gap: 16
                    position: {x: 100, y: 0}
                    children:
                      - {type: component, component: chip, params: {id: a}}
                      - {type: component, component: chip, params: {id: b}}
                      - {type: component, component: chip, params: {id: c}}
                """.trimIndent(),
            ),
            ScreenDef(),
        )

        assertEquals(listOf("a", "b", "c"), elements.map { it.id })
        assertEquals(listOf(100.0, 276.0, 452.0), elements.map { it.x })
    }

    @Test
    fun `loops bind the index and value`() {
        val elements = TemplateResolver().resolve(
            blocks(
                """
                  - loop: 3
                    blocks:
                      - type: text
                        id: "row_${'$'}{loopIndex}"
                        position: {x: 0, y: "loopIndex * 30"}
                        text: "row ${'$'}{loopNumber}"
                """.trimIndent(),
            ),
            ScreenDef(),
        )
        assertEquals(listOf("row_0", "row_1", "row_2"), elements.map { it.id })
        assertEquals(listOf(0.0, 30.0, 60.0), elements.map { it.y })
        assertEquals("row 3", elements[2].text)
    }

    @Test
    fun `an unknown component is reported`() {
        val resolver = TemplateResolver()
        val elements = resolver.resolve(
            blocks("  - {type: component, component: nope}"),
            ScreenDef(),
        )
        assertTrue(elements.isEmpty())
        assertTrue(resolver.issues.any { it.contains("nope") })
    }

    @Test
    fun `recursive components stop at the depth limit`() {
        val recursive = Yaml().load<Map<String, Any?>>(
            """
            params: {}
            blocks:
              - {type: component, component: loop_me}
            """.trimIndent(),
        )
        val resolver = TemplateResolver(mapOf("loop_me" to recursive))
        val elements = resolver.resolve(blocks("  - {type: component, component: loop_me}"), ScreenDef())
        assertTrue(elements.isEmpty())
        assertTrue(resolver.issues.any { it.contains("depth") })
    }

    @Test
    fun `the shipped demo page loads, resolves, and renders`() {
        val root = File("..").canonicalFile
        val loader = PageLoader(
            pagesDir = File(root, "protocol/pages"),
            componentsDir = File(root, "protocol/components"),
            effectsDir = File(root, "protocol/effects"),
        )
        val page = loader.loadPage(File(root, "protocol/pages/demo.yml"))
        assertNotNull(page)
        assertTrue(loader.issues.isEmpty(), "issues: ${loader.issues}")

        assertTrue(page.elements.any { it.id == "chip_players_value" })
        assertTrue(page.elements.any { it.id == "chip_tps_value" })
        assertTrue(page.elements.any { it.id == "chip_ping_value" })

        assertEquals(700.0, page.elements.first { it.id == "card" }.x)

        val rendered = PageRenderer().render(page)
        assertTrue(rendered.draws.isNotEmpty())

        val cardParts = rendered.draws.filter { it.elementId == "card" }
        assertEquals(2 + 4 + 1, cardParts.size, "expected 2 fills, 4 corners, 1 outline")

        val button = page.elements.first { it.id == "card_button" }
        val hit = rendered.hitTest(button.centerX, button.centerY)
        assertNotNull(hit)
    }

    @Test
    fun `effects scale relative to the element`() {
        val root = File("..").canonicalFile
        val effects = PageLoader(
            pagesDir = File(root, "protocol/pages"),
            componentsDir = File(root, "protocol/components"),
            effectsDir = File(root, "protocol/effects"),
        ).loadEffects()

        val lift = effects.getValue("lift")
        assertEquals(-3.0, lift.moveY)
        assertEquals(4.0, lift.scaleXPercent)

        val small = dev.shadr.core.page.Element(id = "s", type = dev.shadr.core.page.ElementType.BLOCK, width = 100.0, height = 50.0)
        val lifted = lift.applyTo(small)
        assertEquals(104.0, lifted.width)
        assertEquals(52.0, lifted.height)

        assertEquals(-2.0, lifted.x)
    }
}
