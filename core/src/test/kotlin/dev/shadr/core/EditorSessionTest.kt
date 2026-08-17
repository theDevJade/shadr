/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core

import dev.shadr.core.editor.EditorSession
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorSessionTest {
    private fun session(undoLimit: Int = 50) = EditorSession(
        Page(
            name = "t",
            elements = listOf(
                Element(id = "a", type = ElementType.BLOCK, x = 10.0, y = 20.0),
                Element(id = "b", type = ElementType.BLOCK, x = 50.0, y = 60.0),
            ),
        ),
        undoLimit = undoLimit,
    )

    private fun EditorSession.x(id: String) = page.elements.first { it.id == id }.x

    @Test
    fun `undo restores the state before an edit, redo reapplies it`() {
        val session = session()
        assertFalse(session.canUndo)

        session.patch("a", mapOf("position.x" to "250"))
        assertEquals(250.0, session.x("a"))
        assertTrue(session.canUndo)

        assertTrue(session.undo())
        assertEquals(10.0, session.x("a"))
        assertTrue(session.canRedo)

        assertTrue(session.redo())
        assertEquals(250.0, session.x("a"))
    }

    @Test
    fun `a gesture collapses to a single undo step`() {
        val session = session()
        for (x in listOf(20, 30, 40, 50, 60)) {
            session.patch("a", mapOf("position.x" to "$x"), gesture = "drag:a")
        }
        assertEquals(60.0, session.x("a"))

        assertTrue(session.undo())
        assertEquals(10.0, session.x("a"), "one undo should reverse the whole drag")
        assertFalse(session.canUndo)
    }

    @Test
    fun `separate gestures are separate undo steps`() {
        val session = session()
        session.patch("a", mapOf("position.x" to "100"), gesture = "drag:a")
        session.patch("a", mapOf("position.x" to "200"), gesture = "drag:a-again")

        session.undo()
        assertEquals(100.0, session.x("a"))
        session.undo()
        assertEquals(10.0, session.x("a"))
    }

    @Test
    fun `a batch edit is one undo step across every element it touched`() {
        val session = session()
        session.patchAll(
            mapOf(
                "a" to mapOf("position.x" to "111"),
                "b" to mapOf("position.x" to "222"),
            ),
        )
        assertEquals(111.0, session.x("a"))
        assertEquals(222.0, session.x("b"))

        assertTrue(session.undo())
        assertEquals(10.0, session.x("a"))
        assertEquals(50.0, session.x("b"))
    }

    @Test
    fun `a new edit clears the redo branch`() {
        val session = session()
        session.patch("a", mapOf("position.x" to "100"))
        session.undo()
        assertTrue(session.canRedo)

        session.patch("b", mapOf("position.x" to "999"))
        assertFalse(session.canRedo, "redo survived a divergent edit")
    }

    @Test
    fun `an edit that changes nothing is not recorded`() {
        val session = session()
        assertFalse(session.patch("a", mapOf("position.x" to "not a number")))
        assertFalse(session.canUndo, "a rejected value became an undo step")

        assertFalse(session.patch("nonexistent", mapOf("position.x" to "5")))
        assertFalse(session.canUndo)
    }

    @Test
    fun `history is bounded`() {
        val session = session(undoLimit = 3)
        for (x in 1..10) session.patch("a", mapOf("position.x" to "$x"))

        var undos = 0
        while (session.undo()) undos++
        assertEquals(3, undos, "history grew past its limit")
    }

    @Test
    fun `deleting several elements is one undo step`() {
        val session = session()
        assertTrue(session.delete(listOf("a", "b")))
        assertTrue(session.page.elements.isEmpty())

        assertTrue(session.undo())
        assertEquals(listOf("a", "b"), session.page.elements.map { it.id })
    }

    @Test
    fun `dirty tracks whether the page differs from the last save`() {
        val session = session()
        assertFalse(session.isDirty)

        session.patch("a", mapOf("position.x" to "250"))
        assertTrue(session.isDirty)

        session.markSaved()
        assertFalse(session.isDirty)

        session.undo()
        assertTrue(session.isDirty, "undoing past a save left the page looking clean")
    }

    @Test
    fun `a step animates one property of one element and leaves the rest alone`() {
        val session = session()
        session.setStep("open", target = "a", axis = "y", from = 0.0, to = 100.0, duration = 20)

        session.scrub(10)
        val halfway = session.snapshot().elements.first { it.id == "a" }
        assertEquals(50.0, halfway.y)
        assertEquals(60.0, session.snapshot().elements.first { it.id == "b" }.y, "b is not a target")
    }

    @Test
    fun `scrubbing changes what is rendered without changing what would be saved`() {
        val session = session()
        session.setStep("open", target = "a", axis = "y", from = 0.0, to = 100.0, duration = 20)
        session.markSaved()

        session.scrub(20)
        assertEquals(100.0, session.snapshot().elements.first { it.id == "a" }.y)
        assertEquals(20.0, session.page.elements.first { it.id == "a" }.y, "the scrub edited the page")
        assertFalse(session.isDirty, "a preview counted as an unsaved edit")

        session.scrub(null)
        assertEquals(20.0, session.snapshot().elements.first { it.id == "a" }.y)
    }

    @Test
    fun `edits are refused while the timeline is scrubbed`() {
        val session = session()
        session.setStep("open", target = "a", axis = "y", from = 0.0, to = 100.0, duration = 20)
        session.scrub(10)

        assertFalse(session.patch("a", mapOf("position.y" to "999")))
        assertFalse(session.delete(listOf("a")))
        assertEquals(20.0, session.page.elements.first { it.id == "a" }.y)

        session.scrub(null)
        assertTrue(session.patch("a", mapOf("position.y" to "999")))
    }

    @Test
    fun `setting a step twice on the same property replaces it rather than stacking`() {
        val session = session()
        session.setStep("open", target = "a", axis = "y", from = 0.0, to = 100.0, duration = 20)
        session.setStep("open", target = "a", axis = "y", from = 0.0, to = 300.0, duration = 20)

        val steps = session.page.animations.single().steps
        assertEquals(1, steps.size)
        assertEquals(300.0, steps.single().to)
    }

    @Test
    fun `removing a step reports whether there was one to remove`() {
        val session = session()
        session.setStep("open", target = "a", axis = "y", from = 0.0, to = 100.0, duration = 20)

        assertFalse(session.removeStep("open", "a", "x"), "removed an axis that was never set")
        assertTrue(session.removeStep("open", "a", "y"))
        assertTrue(session.page.animations.single().steps.isEmpty())
    }

    @Test
    fun `the easing curve survives an edit to an endpoint`() {
        val session = session()
        session.setStep("open", "a", "y", 0.0, 100.0, 20, Interpolation.EASE_OUT)
        session.setStep("open", "a", "y", 0.0, 300.0, 20, Interpolation.EASE_OUT)

        val step = session.page.animations.single().steps.single()
        assertEquals(Interpolation.EASE_OUT, step.easing)
        assertEquals(300.0, step.to)
    }

    @Test
    fun `easing shapes the sampled value, not just what is stored`() {
        val session = session()
        session.setStep("open", "a", "y", 0.0, 100.0, 20, Interpolation.EASE_IN)
        session.scrub(10)

        assertEquals(25.0, session.snapshot().elements.first { it.id == "a" }.y)
    }

    @Test
    fun `a timeline edit is undoable like any other`() {
        val session = session()
        session.setStep("open", target = "a", axis = "y", from = 0.0, to = 100.0, duration = 20)
        assertTrue(session.undo())
        assertTrue(session.page.animations.isEmpty())
    }

    @Test
    fun `a newly added element is editable, not locked for having no source path`() {
        val session = session()
        val added = session.add("block", 5.0, 5.0, 20.0, 20.0)

        assertEquals("", added.sourcePath)
        assertFalse(
            session.snapshot().locked.containsKey(added.id),
            "an element the writer can append was reported as unaddressable",
        )
    }

    @Test
    fun `an element that came from the file and cannot be addressed stays locked`() {
        val session = EditorSession(
            Page(
                name = "t",
                elements = listOf(Element(id = "a", type = ElementType.BLOCK, componentName = "card")),
            ),
        )
        assertTrue(session.snapshot().locked.containsKey("a"))
    }

    @Test
    fun `markSaved adopts the source paths the writer assigned`() {
        val session = session()
        val added = session.add("block", 5.0, 5.0, 20.0, 20.0)

        session.markSaved(mapOf(added.id to "2"))

        assertEquals("2", session.page.elements.first { it.id == added.id }.sourcePath)
        assertFalse(
            session.snapshot().locked.containsKey(added.id),
            "a saved element should stay editable rather than relock",
        )
    }

    @Test
    fun `a blur element is added on the reserved layer, behind everything else`() {
        val session = session()
        session.patch("a", mapOf("layer" to "14"))

        val blur = session.add("blur", 0.0, 0.0, 100.0, 40.0)

        assertEquals(dev.shadr.core.hud.HudPositionCalculator.BLUR_PANEL_LAYER, blur.layer)
        assertTrue(
            session.page.elements.filterNot { it.id == blur.id }.all { it.layer > blur.layer },
            "the blur panel did not land behind every other element",
        )
    }

    @Test
    fun `adding after a blur does not inherit the reserved layer`() {
        val session = session()
        session.add("blur", 0.0, 0.0, 100.0, 40.0)

        val block = session.add("block", 0.0, 0.0, 20.0, 20.0)

        assertTrue(block.layer > 0, "a new block was pushed behind the page by the blur layer")
    }
}
