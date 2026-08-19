/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.editor.PageWriter
import dev.shadr.core.RoundingSize
import dev.shadr.core.page.AnimationStep
import dev.shadr.core.page.GuiAnimationDef
import dev.shadr.core.page.PageLoader
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PageWriterTest {
    private val source = """
        |name: demo
        |screen:
        |  width: 1920
        |  height: 1080
        |
        |blocks:
        |  # The backdrop covers the whole design space.
        |  - type: block
        |    id: backdrop
        |    layer: 0.0
        |    color: '080808'
        |    position:
        |      x: 0
        |      y: 0
        |    size:
        |      width: 1920      # full width on purpose
        |      height: 1080
        |
        |  - type: block_rounded
        |    id: card
        |    layer: 10.0
        |    color: 15151c
        |    position:
        |      x: 1920/2 - 260
        |      y: 1080/2 - 170
        |    size:
        |      width: 520
        |      height: 340
        |    children:
        |      - type: text
        |        id: card_title
        |        layer: 11.0
        |        color: f2f2f7
        |        position:
        |          x: 32
        |          y: 38
        |        size:
        |          width: 96
        |          height: 96
        |        text: shadr
        |
    """.trimMargin()

    private fun workspace(): Triple<File, File, PageLoader> {
        val dir = createTempDirectory("shadr-writer").toFile()
        val pages = File(dir, "pages").apply { mkdirs() }
        File(dir, "components").mkdirs()
        File(dir, "effects").mkdirs()
        val file = File(pages, "demo.yml").apply { writeText(source) }
        return Triple(dir, file, PageLoader(pages, File(dir, "components"), File(dir, "effects")))
    }

    @Test
    fun `a moved element is written back and everything else survives`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!

        val moved = original.elements.map {
            if (it.id == "backdrop") it.copy(x = 40.0, y = 25.0) else it
        }
        val result = PageWriter().save(file, original, original.copy(elements = moved))

        assertEquals(1, result.saved)
        assertTrue(result.ok, "unexpected skips: ${result.skipped}")

        val text = file.readText()
        assertTrue(text.contains("x: 40"), "new position not written:\n$text")
        assertTrue(text.contains("y: 25"))

        assertTrue(text.contains("# The backdrop covers the whole design space."), "lost a comment")
        assertTrue(text.contains("full width on purpose"), "lost a trailing comment")
        assertTrue(text.contains("1920/2 - 260"), "clobbered an untouched expression")
        assertTrue(text.contains("id: card_title"), "lost a nested element")

        val reloaded = loader.loadPage(file)
        assertNotNull(reloaded)
        assertEquals(40.0, reloaded.elements.first { it.id == "backdrop" }.x)
        assertEquals(1920.0, reloaded.elements.first { it.id == "backdrop" }.width)
    }

    @Test
    fun `a nested element is written back relative to its parent`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!
        val title = original.elements.first { it.id == "card_title" }

        assertEquals(732.0, title.x)

        val nudged = original.elements.map {
            if (it.id == "card_title") it.copy(x = it.x + 10) else it
        }
        PageWriter().save(file, original, original.copy(elements = nudged))

        assertTrue(file.readText().contains("x: 42"), "wrote an absolute position:\n${file.readText()}")

        val reloaded = loader.loadPage(file)!!
        assertEquals(742.0, reloaded.elements.first { it.id == "card_title" }.x)
    }

    @Test
    fun `saving is idempotent`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!
        val moved = original.elements.map { if (it.id == "card_title") it.copy(x = it.x + 10) else it }

        PageWriter().save(file, original, original.copy(elements = moved))
        val afterFirst = loader.loadPage(file)!!
        val firstText = file.readText()

        PageWriter().save(file, afterFirst, afterFirst)
        assertEquals(firstText, file.readText(), "an unchanged save rewrote the file")

        assertEquals(
            afterFirst.elements.first { it.id == "card_title" }.x,
            loader.loadPage(file)!!.elements.first { it.id == "card_title" }.x,
        )
    }

    @Test
    fun `changing a field that held an expression is reported`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!
        val moved = original.elements.map { if (it.id == "card") it.copy(x = 250.0) else it }

        val result = PageWriter().save(file, original, original.copy(elements = moved))

        assertTrue(
            result.expressionsReplaced.any { it == "card.position.x" },
            "replacing '1920/2 - 260' with a literal went unreported: ${result.expressionsReplaced}",
        )
        assertTrue(file.readText().contains("x: 250"))
    }

    @Test
    fun `moving a component-derived element writes the move onto the instance`() {
        val dir = createTempDirectory("shadr-writer-component").toFile()
        val pages = File(dir, "pages").apply { mkdirs() }
        val components = File(dir, "components").apply { mkdirs() }
        File(dir, "effects").mkdirs()

        File(components, "chip.yml").writeText(
            """
            |params:
            |  id: "chip"
            |blocks:
            |  - type: block
            |    id: "${'$'}{id}_bg"
            |    size: {width: 100, height: 40}
            """.trimMargin(),
        )
        val file = File(pages, "p.yml").apply {
            writeText(
                """
                |name: p
                |blocks:
                |  - type: component
                |    component: chip
                |    params: {id: one}
                """.trimMargin(),
            )
        }

        val loader = PageLoader(pages, components, File(dir, "effects"))
        val original = loader.loadPage(file)!!
        val before = file.readText()

        val moved = original.elements.map { it.copy(x = 999.0) }
        val result = PageWriter().save(file, original, original.copy(elements = moved))

        assertTrue(result.ok, "moving a component was refused: ${result.skipped}")
        assertEquals(1, result.saved, "the instance should be written once")
        assertTrue(before != file.readText(), "the move was not written at all")
        assertEquals(
            999.0, loader.loadPage(file)!!.elements.single().x,
            "the component instance did not end up where it was dragged",
        )
    }

    @Test
    fun `a new element is appended and reloads with the same geometry`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!

        val added = original.elements + dev.shadr.core.page.Element(
            id = "brand_new",
            type = dev.shadr.core.page.ElementType.BLOCK_ROUNDED,
            x = 300.0,
            y = 400.0,
            width = 160.0,
            height = 60.0,
            layer = 42.0,
            color = Rgb(0x4CC9F0),
            rounding = dev.shadr.core.page.Rounding(size = RoundingSize.SMALL),
        )
        val result = PageWriter().save(file, original, original.copy(elements = added))
        assertEquals(1, result.saved)
        assertTrue(result.ok, "unexpected skips: ${result.skipped}")

        val reloaded = loader.loadPage(file)!!
        val written = reloaded.elements.first { it.id == "brand_new" }
        assertEquals(300.0, written.x)
        assertEquals(160.0, written.width)
        assertEquals(42.0, written.layer)
        assertEquals(0x4CC9F0, written.color.packed)
        assertEquals(RoundingSize.SMALL, written.rounding?.size)

        assertTrue(file.readText().contains("# The backdrop covers the whole design space."))
        assertTrue(file.readText().contains("1920/2 - 260"))
    }

    @Test
    fun `a deleted element is removed and its siblings survive`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!

        val removed = original.elements.filterNot { it.id == "backdrop" }
        val result = PageWriter().save(file, original, original.copy(elements = removed))
        assertEquals(1, result.saved)

        val reloaded = loader.loadPage(file)!!
        assertTrue(reloaded.elements.none { it.id == "backdrop" }, "backdrop was not removed")
        assertTrue(reloaded.elements.any { it.id == "card" }, "removing one element took out a sibling")
        assertTrue(reloaded.elements.any { it.id == "card_title" }, "lost a nested child")
    }

    @Test
    fun `deleting several elements at once removes exactly those elements`() {
        val dir = createTempDirectory("shadr-writer-multi").toFile()
        val pages = File(dir, "pages").apply { mkdirs() }
        File(dir, "components").mkdirs()
        File(dir, "effects").mkdirs()
        val file = File(pages, "p.yml").apply {
            writeText(
                buildString {
                    appendLine("name: p")
                    appendLine("blocks:")
                    for (index in 0 until 5) {
                        appendLine("  - type: block")
                        appendLine("    id: e$index")
                        appendLine("    size: {width: 10, height: 10}")
                    }
                },
            )
        }
        val loader = PageLoader(pages, File(dir, "components"), File(dir, "effects"))
        val original = loader.loadPage(file)!!
        assertEquals(5, original.elements.size)

        val kept = original.elements.filterNot { it.id in setOf("e0", "e2", "e4") }
        val result = PageWriter().save(file, original, original.copy(elements = kept))
        assertEquals(3, result.saved)

        assertEquals(listOf("e1", "e3"), loader.loadPage(file)!!.elements.map { it.id })
    }

    @Test
    fun `a colour with leading zeroes survives the round trip`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!
        val recoloured = original.elements.map {
            if (it.id == "backdrop") it.copy(color = Rgb(0x080810)) else it
        }
        PageWriter().save(file, original, original.copy(elements = recoloured))

        val reloaded = loader.loadPage(file)!!
        assertEquals(0x080810, reloaded.elements.first { it.id == "backdrop" }.color.packed)
    }

    @Test
    fun `a timeline edit round-trips through the file`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!
        val animated = original.copy(
            animations = listOf(
                GuiAnimationDef(
                    name = "open",
                    durationTicks = 20,
                    steps = listOf(
                        AnimationStep(
                            target = "card",
                            axis = "y",
                            easing = Interpolation.EASE_OUT,
                            from = 0.0,
                            to = 370.0,
                            durationTicks = 12,
                        ),
                    ),
                ),
            ),
        )
        PageWriter().save(file, original, animated)

        val reloaded = loader.loadPage(file)!!
        val step = reloaded.animations.single().steps.single()
        assertEquals("card", step.target)
        assertEquals("y", step.axis)
        assertEquals(370.0, step.to)
        assertEquals(12, step.durationTicks)
        assertEquals(Interpolation.EASE_OUT, step.easing, "the easing curve was not written")
    }

    @Test
    fun `writing animations leaves the hand-authored blocks alone`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!
        PageWriter().save(
            file,
            original,
            original.copy(
                animations = listOf(
                    GuiAnimationDef("open", 20, listOf(AnimationStep(target = "card", axis = "y", to = 10.0))),
                ),
            ),
        )

        val text = file.readText()
        assertTrue(text.contains("# The backdrop covers the whole design space."))
        assertTrue(text.contains("# full width on purpose"))
        assertTrue(text.contains("1920/2 - 260"), "an untouched expression was flattened")
    }

    private val animatedSource = source + """
        |
        |animations:
        |  # Runs when the page opens. 20 ticks is one second.
        |  - name: open
        |    durationTicks: 20
        |    steps:
        |      # The card rises into place.
        |      - target: card
        |        axis: y
        |        easing: ease-out
        |        from: 1080/2 - 120   # half a card below its resting place
        |        to: 1080/2 - 170
        |        duration: 12
        |      - target: card
        |        axis: opacity
        |        easing: linear
        |        from: 0
        |        to: 255
        |
        |  # Left alone by every test below; it is the control.
        |  - name: pulse
        |    durationTicks: 40
        |    steps:
        |      - target: backdrop
        |        axis: opacity
        |        easing: linear
        |        from: 200
        |        to: 255
        |
    """.trimMargin()

    private fun animatedWorkspace(): Triple<File, File, PageLoader> {
        val (dir, file, loader) = workspace()
        file.writeText(animatedSource)
        return Triple(dir, file, loader)
    }

    @Test
    fun `editing one step leaves the rest of the animations block hand-authored`() {
        val (_, file, loader) = animatedWorkspace()
        val original = loader.loadPage(file)!!
        assertEquals(2, original.animations.size, "fixture did not load")

        val edited = original.copy(
            animations = original.animations.map { animation ->
                if (animation.name != "open") animation
                else animation.copy(
                    steps = animation.steps.map {
                        if (it.axis == "y") it.copy(durationTicks = 16) else it
                    },
                )
            },
        )
        PageWriter().save(file, original, edited)

        val text = file.readText()
        assertTrue(text.contains("duration: 16"), "the edit was not written:\n$text")
        assertTrue(
            text.contains("# Runs when the page opens. 20 ticks is one second."),
            "lost the animation's comment",
        )
        assertTrue(text.contains("# The card rises into place."), "lost a step's comment")
        assertTrue(text.contains("# Left alone by every test below"), "lost an untouched animation's comment")
        assertTrue(
            text.contains("1080/2 - 120"),
            "flattened an expression in a field the edit never touched:\n$text",
        )
        assertTrue(text.contains("half a card below its resting place"), "lost a trailing comment")

        val reloaded = loader.loadPage(file)!!
        assertEquals(2, reloaded.animations.size)
        val step = reloaded.animations.first { it.name == "open" }.steps.first { it.axis == "y" }
        assertEquals(16, step.durationTicks)
        assertEquals(420.0, step.from, "the expression no longer evaluates to what it did")
    }

    @Test
    fun `overwriting an authored expression in a step is reported`() {
        val (_, file, loader) = animatedWorkspace()
        val original = loader.loadPage(file)!!
        val edited = original.copy(
            animations = original.animations.map { animation ->
                if (animation.name != "open") animation
                else animation.copy(
                    steps = animation.steps.map { if (it.axis == "y") it.copy(from = 900.0) else it },
                )
            },
        )
        val result = PageWriter().save(file, original, edited)

        assertTrue(
            result.expressionsReplaced.contains("open.card.y.from"),
            "an overwritten expression went unreported: ${result.expressionsReplaced}",
        )
        assertTrue(file.readText().contains("from: 900"))
    }

    @Test
    fun `adding and removing steps keeps the untouched ones intact`() {
        val (_, file, loader) = animatedWorkspace()
        val original = loader.loadPage(file)!!
        val open = original.animations.first { it.name == "open" }
        val edited = original.copy(
            animations = original.animations.map { animation ->
                if (animation.name != "open") animation
                else animation.copy(
                    steps = animation.steps.filter { it.axis != "opacity" } +
                        AnimationStep(target = "card_title", axis = "opacity", from = 0.0, to = 255.0),
                )
            },
        )
        assertEquals(2, open.steps.size)
        PageWriter().save(file, original, edited)

        val text = file.readText()
        assertTrue(text.contains("# The card rises into place."), "lost the surviving step's comment")
        assertTrue(text.contains("1080/2 - 120"), "flattened the surviving step's expression")
        assertTrue(text.contains("target: card_title"), "the new step was not written")

        val reloaded = loader.loadPage(file)!!.animations.first { it.name == "open" }
        assertEquals(2, reloaded.steps.size)
        assertEquals(setOf("card", "card_title"), reloaded.steps.map { it.target }.toSet())
    }

    @Test
    fun `duplicate step keys fall back to a rewrite of only that animation`() {
        val (_, file, loader) = workspace()
        file.writeText(
            source + """
            |
            |animations:
            |  - name: broken
            |    durationTicks: 20
            |    steps:
            |      - target: card
            |        axis: y
            |        from: 0
            |        to: 10
            |      - target: card
            |        axis: y
            |        from: 10
            |        to: 20
            |
            |  # This one is well-formed and must keep its comment.
            |  - name: fine
            |    durationTicks: 40
            |    steps:
            |      - target: backdrop
            |        axis: opacity
            |        from: 200   # deliberately commented
            |        to: 255
            |
            """.trimMargin(),
        )
        val original = loader.loadPage(file)!!
        val edited = original.copy(
            animations = original.animations.map {
                if (it.name == "broken") it.copy(durationTicks = 30) else it
            },
        )
        PageWriter().save(file, original, edited)

        val text = file.readText()
        assertTrue(text.contains("durationTicks: 30"), "the edit was not written")
        assertTrue(text.contains("# This one is well-formed"), "the fallback took a comment it should not have")
        assertTrue(text.contains("deliberately commented"), "the fallback reached into the other animation")
        assertEquals(2, loader.loadPage(file)!!.animations.first { it.name == "broken" }.steps.size)
    }

    @Test
    fun `removing the last step removes the block`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!
        val animated = original.copy(
            animations = listOf(
                GuiAnimationDef("open", 20, listOf(AnimationStep(target = "card", axis = "y", to = 10.0))),
            ),
        )
        PageWriter().save(file, original, animated)
        PageWriter().save(file, animated, animated.copy(animations = emptyList()))

        assertTrue(loader.loadPage(file)!!.animations.isEmpty())
        assertTrue(!file.readText().contains("animations:"))
    }

    private val expansions = """
        |name: expansions
        |blocks:
        |  - type: grid_block
        |    id: row
        |    gap: 12
        |    position:
        |      x: 100
        |      y: 100
        |    children:
        |      - type: block
        |        id: chip_a
        |        color: '111111'
        |        size:
        |          width: 80
        |          height: 40
        |      - type: block
        |        id: chip_b
        |        color: '222222'
        |        size:
        |          width: 80
        |          height: 40
        |
        |  - loop: 3
        |    block:
        |      - type: block
        |        id: tick_${'$'}{loopIndex}
        |        color: '333333'
        |        size:
        |          width: 10
        |          height: 10
        |
    """.trimMargin()

    private fun expansionWorkspace(): Pair<File, PageLoader> {
        val dir = createTempDirectory("shadr-writer-expand").toFile()
        val pages = File(dir, "pages").apply { mkdirs() }
        File(dir, "components").mkdirs()
        File(dir, "effects").mkdirs()
        val file = File(pages, "expansions.yml").apply { writeText(expansions) }
        return file to PageLoader(pages, File(dir, "components"), File(dir, "effects"))
    }

    @Test
    fun `a grid child is written back to its own node`() {
        val (file, loader) = expansionWorkspace()
        val original = loader.loadPage(file)!!
        val edited = original.copy(
            elements = original.elements.map {
                if (it.id == "chip_b") it.copy(width = 200.0, color = Rgb(0xAABBCC)) else it
            },
        )
        val result = PageWriter().save(file, original, edited)
        assertTrue(result.skipped.none { it.key == "chip_b" }, "grid child refused: ${result.skipped}")

        val reloaded = loader.loadPage(file)!!
        assertEquals(200.0, reloaded.elements.first { it.id == "chip_b" }.width)
        assertEquals(0xAABBCC, reloaded.elements.first { it.id == "chip_b" }.color.packed)

        assertEquals(80.0, reloaded.elements.first { it.id == "chip_a" }.width)
        assertEquals(0x111111, reloaded.elements.first { it.id == "chip_a" }.color.packed)
    }

    @Test
    fun `a loop iteration is refused with a reason naming the construct`() {
        val (file, loader) = expansionWorkspace()
        val original = loader.loadPage(file)!!
        val edited = original.copy(
            elements = original.elements.map { if (it.id == "tick_1") it.copy(width = 99.0) else it },
        )
        val result = PageWriter().save(file, original, edited)

        val reason = result.skipped["tick_1"]
        assertNotNull(reason, "a loop iteration was written back: ${result.skipped}")
        assertTrue(reason.contains("loop"), "reason did not name the construct: $reason")
    }

    @Test
    fun `deleting a grid child removes only that child`() {
        val (file, loader) = expansionWorkspace()
        val original = loader.loadPage(file)!!
        val edited = original.copy(elements = original.elements.filterNot { it.id == "chip_a" })
        PageWriter().save(file, original, edited)

        val ids = loader.loadPage(file)!!.elements.map { it.id }
        assertTrue("chip_a" !in ids, "the deleted child survived")
        assertTrue("chip_b" in ids, "deleting one child took its sibling with it")
    }

    @Test
    fun `an explicit keyframe list is written as values`() {
        val (_, file, loader) = workspace()
        val original = loader.loadPage(file)!!
        PageWriter().save(
            file,
            original,
            original.copy(
                animations = listOf(
                    GuiAnimationDef(
                        "pulse",
                        20,
                        listOf(AnimationStep(target = "card", axis = "opacity", values = listOf(0.0, 255.0, 128.0))),
                    ),
                ),
            ),
        )

        val step = loader.loadPage(file)!!.animations.single().steps.single()
        assertEquals(listOf(0.0, 255.0, 128.0), step.values)
    }
}
