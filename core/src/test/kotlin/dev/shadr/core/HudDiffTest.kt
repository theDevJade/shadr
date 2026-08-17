/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.Vec3
import dev.shadr.core.hud.HudDiff
import dev.shadr.core.hud.HudDraw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HudDiffTest {
    private fun draw(key: String, kind: HudDraw.Kind = HudDraw.Kind.TEXT, y: Double = 0.0) =
        HudDraw(key = key, kind = kind, translation = Vec3(0.0, y, 0.0), scale = Vec3(1.0, 1.0, 1.0))

    private fun frame(vararg draws: HudDraw) = draws.associateBy { it.key }

    @Test
    fun `an unchanged draw is neither spawned nor updated`() {
        val a = draw("a")
        val diff = HudDiff.between(frame(a), listOf(a))
        assertTrue(diff.isEmpty, "re-sending an identical draw would only cost a packet")
    }

    @Test
    fun `a moved draw updates in place`() {
        val diff = HudDiff.between(frame(draw("a")), listOf(draw("a", y = 5.0)))
        assertEquals(listOf("a"), diff.updated.map { it.key })
        assertTrue(diff.spawned.isEmpty())
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `keys that leave the frame are removed`() {
        val diff = HudDiff.between(frame(draw("a"), draw("b")), listOf(draw("a")))
        assertEquals(listOf("b"), diff.removed)
        assertTrue(diff.updated.isEmpty())
    }

    @Test
    fun `changing kind forces a respawn`() {
        val diff = HudDiff.between(frame(draw("a")), listOf(draw("a", kind = HudDraw.Kind.ITEM)))
        assertEquals(listOf("a"), diff.spawned.map { it.key })
        assertTrue(diff.updated.isEmpty())
    }

    @Test
    fun `an entity that disappeared underneath us is respawned`() {
        val a = draw("a")
        val diff = HudDiff.between(frame(a), listOf(a), exists = { false })
        assertEquals(listOf("a"), diff.spawned.map { it.key })
    }

    @Test
    fun `a first frame spawns everything`() {
        val diff = HudDiff.between(emptyMap(), listOf(draw("a"), draw("b")))
        assertEquals(setOf("a", "b"), diff.spawned.map { it.key }.toSet())
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.updated.isEmpty())
    }
}
