/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

import dev.shadr.core.page.Page
import dev.shadr.core.page.ElementType

object VideoAudio {

    data class Track(val sound: String, val everyTicks: Int)

    fun tracksOf(page: Page, clips: (String) -> VideoClip?): List<Track> =
        page.elements
            .asSequence()
            .filter { it.enabled && it.type == ElementType.VIDEO }
            .mapNotNull { it.item }
            .distinct()
            .mapNotNull(clips)
            .map { Track(it.soundKey, it.durationTicks) }
            .toList()

    fun step(tracks: List<Track>, tick: Long, due: Map<String, Long>): Pair<List<Track>, Map<String, Long>> {
        if (tracks.isEmpty()) return emptyList<Track>() to emptyMap()

        val start = mutableListOf<Track>()
        val next = HashMap<String, Long>(tracks.size)
        for (track in tracks) {
            val at = due[track.sound]
            if (at == null || tick >= at) {
                start += track
                next[track.sound] = tick + track.everyTicks
            } else {
                next[track.sound] = at
            }
        }
        return start to next
    }
}
