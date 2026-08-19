/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.paper.nms.EntitySlots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntitySlotsTest {

    private val slots = EntitySlots.DEFAULT

    @Test
    fun `every slot a text display writes is distinct`() {
        val used = mapOf(
            "interpolationDelay" to slots.interpolationDelay(),
            "interpolationDuration" to slots.interpolationDuration(),
            "translation" to slots.translation(),
            "scale" to slots.scale(),
            "leftRotation" to slots.leftRotation(),
            "rightRotation" to slots.rightRotation(),
            "billboard" to slots.billboard(),
            "brightness" to slots.brightness(),
            "viewRange" to slots.viewRange(),
            "text" to slots.text(),
            "lineWidth" to slots.lineWidth(),
            "backgroundColor" to slots.backgroundColor(),
            "textOpacity" to slots.textOpacity(),
            "textFlags" to slots.textFlags(),
        )
        assertNoCollision(used)
    }

    @Test
    fun `every slot an item display writes is distinct`() {
        val used = mapOf(
            "interpolationDelay" to slots.interpolationDelay(),
            "interpolationDuration" to slots.interpolationDuration(),
            "translation" to slots.translation(),
            "scale" to slots.scale(),
            "leftRotation" to slots.leftRotation(),
            "rightRotation" to slots.rightRotation(),
            "billboard" to slots.billboard(),
            "brightness" to slots.brightness(),
            "viewRange" to slots.viewRange(),
            "item" to slots.item(),
        )
        assertNoCollision(used)
    }

    @Test
    fun `every slot an item frame writes is distinct`() {
        assertNoCollision(
            mapOf(
                "sharedFlags" to slots.sharedFlags(),
                "frameItem" to slots.frameItem(),
                "frameRotation" to slots.frameRotation(),
            ),
        )
    }

    @Test
    fun `text and item occupy the same slot on their own display types`() {
        assertEquals(
            slots.text(), slots.item(),
            "text displays and item displays reuse one slot for their payload; if a version " +
                "splits them, item() is the one that must move",
        )
    }

    @Test
    fun `display slots sit above the shared entity slots`() {
        val shared = slots.sharedFlags()
        val displaySlots = listOf(
            slots.interpolationDelay(), slots.translation(), slots.scale(),
            slots.billboard(), slots.brightness(), slots.viewRange(),
            slots.text(), slots.textFlags(),
        )
        assertTrue(
            displaySlots.all { it > shared },
            "Display fields are appended after Entity's, so every display slot must be above " +
                "sharedFlags ($shared); one at or below it means the table is off by a block",
        )
    }

    private fun assertNoCollision(used: Map<String, Int>) {
        val byIndex = used.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertTrue(
            byIndex.isEmpty(),
            "two fields share a metadata slot, so one silently overwrites the other: $byIndex",
        )
    }
}
