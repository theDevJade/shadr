/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

data class VideoSheet(
    val frameWidth: Int,
    val frameHeight: Int,
    val columns: Int,
    val rows: Int,
) {
    val capacity: Int get() = columns * rows

    val width: Int get() = columns * frameWidth

    val height: Int get() = rows * frameHeight

    fun originOf(frame: Int): Pair<Int, Int> {
        require(frame in 0 until capacity) { "frame $frame outside a $columns x $rows sheet" }
        return Pair((frame % columns) * frameWidth, (frame / columns) * frameHeight)
    }

    companion object {
        const val MAX_EDGE = 4096

        fun fit(frameWidth: Int, frameHeight: Int, frameCount: Int): VideoSheet? {
            if (frameWidth !in 1..MAX_EDGE || frameHeight !in 1..MAX_EDGE) return null
            if (frameCount < 1) return null

            val columns = (MAX_EDGE / frameWidth).coerceAtMost(frameCount)
            val rows = (frameCount + columns - 1) / columns
            if (rows * frameHeight > MAX_EDGE) return null

            return VideoSheet(frameWidth, frameHeight, columns, rows)
        }

        fun capacityFor(frameWidth: Int, frameHeight: Int): Int {
            if (frameWidth !in 1..MAX_EDGE || frameHeight !in 1..MAX_EDGE) return 0
            return (MAX_EDGE / frameWidth) * (MAX_EDGE / frameHeight)
        }
    }
}
