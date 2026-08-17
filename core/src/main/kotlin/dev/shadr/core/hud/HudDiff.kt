/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.hud

data class HudDiff(
    val removed: List<String>,
    val spawned: List<HudDraw>,
    val updated: List<HudDraw>,
) {
    val isEmpty: Boolean get() = removed.isEmpty() && spawned.isEmpty() && updated.isEmpty()

    companion object {
        /**
         * @param previous the draws applied last frame, keyed as they were.
         * @param next the desired frame.
         * @param exists whether the adapter still holds a live entity for a key.
         */
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
                    before == draw -> Unit // Identical
                    else -> updated += draw
                }
            }
            return HudDiff(removed, spawned, updated)
        }
    }
}
