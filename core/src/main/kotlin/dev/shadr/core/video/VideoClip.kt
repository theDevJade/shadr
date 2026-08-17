/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

data class VideoClip(
    val id: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val fps: Double,
    val index: Int = 0,
) {
    val duration: Double get() = frameCount / fps

    val itemModel: String get() = itemModelOf(id)

    val sheetTexture: String get() = "minecraft:$SHEET_PREFIX$id"

    val soundKey: String get() = soundKeyOf(id)

    val durationTicks: Int get() = Math.round(duration * TICKS_PER_SECOND).toInt().coerceAtLeast(1)

    companion object {
        const val MODEL_PREFIX = "shadr/video_"

        const val SHEET_PREFIX = "shadr/video/"

        const val MAX_CLIPS = 64

        const val SOUND_PREFIX = "shadr.video."

        const val TICKS_PER_SECOND = 20.0

        fun itemModelOf(id: String): String = "minecraft:$MODEL_PREFIX$id"

        fun soundKeyOf(id: String): String = "$SOUND_PREFIX$id"

        private val VALID_ID = Regex("[a-z0-9_]{1,48}")

        fun validateId(id: String): String? =
            if (VALID_ID.matches(id)) null else "id must match ${VALID_ID.pattern}"
    }
}

class VideoRegistry(clips: List<VideoClip>) {

    val clips: List<VideoClip> = clips
        .sortedBy { it.id }
        .take(VideoClip.MAX_CLIPS)
        .mapIndexed { index, clip -> clip.copy(index = index) }

    val dropped: List<String> =
        clips.sortedBy { it.id }.drop(VideoClip.MAX_CLIPS).map { it.id }

    val isEmpty: Boolean get() = this.clips.isEmpty()

    operator fun get(id: String): VideoClip? = clips.firstOrNull { it.id == id }

    companion object {
        val EMPTY = VideoRegistry(emptyList())
    }
}
