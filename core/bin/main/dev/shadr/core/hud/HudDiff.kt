/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.hud

data class HudDiff(
    val removed: List<String>,
    val spawned: List<HudDraw>,
    val updated: List<HudDraw>,
) {
    val isEmpty: Boolean get() = removed.isEmpty() && spawned.isEmpty() && updated.isEmpty()

    companion object {
        fun between(
            previous: Map<String, HudDraw>,
            next: List<HudDraw>,
            exists: (String) -> Boolean = { true },
        ): HudDiff {
            val desired = next.associateBy { it.key }

            val removed = previous.keys.filter { it !in desired }
            val spawned = mutableListOf<HudDraw>()
            val updated = mutableListOf<HudDraw>()

            for ((key, draw) in desired) {
                val before = previous[key]
                when {
                    before == null || !exists(key) -> spawned += draw
                    before.kind != draw.kind -> spawned += draw
                    before == draw -> Unit
                    else -> updated += draw
                }
            }
            return HudDiff(removed, spawned, updated)
        }
    }
}
