/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

import kotlin.math.roundToInt

object VideoBudget {

    const val MAX_HEIGHT = 1080

    const val MIN_HEIGHT = 16

    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        frameCount: Int,
        maxHeight: Int = MAX_HEIGHT,
    ): VideoSheet? {
        if (sourceWidth < 1 || sourceHeight < 1 || frameCount < 1) return null

        val aspect = sourceWidth.toDouble() / sourceHeight
        var height = even(minOf(maxHeight, sourceHeight, VideoSheet.MAX_EDGE))

        while (height >= MIN_HEIGHT) {
            val width = even((height * aspect).roundToInt())
            if (width in 2..VideoSheet.MAX_EDGE) {
                VideoSheet.fit(width, height, frameCount)?.let { return it }
            }
            height -= 2
        }
        return null
    }

    /** Frames in [seconds] at [fps] */
    fun frameCount(seconds: Double, fps: Double): Int =
        maxOf(1, (seconds * fps).roundToInt())

    private fun even(value: Int): Int = value - (value % 2)
}
