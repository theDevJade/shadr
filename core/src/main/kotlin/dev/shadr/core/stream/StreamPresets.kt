/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.stream

object StreamPresets {

    val QUALITY: String = System.getProperty("shadr.stream.quality", "ultra")

    val CODEC_1080: StreamCodec.Geometry = when (QUALITY) {
        "balanced" -> StreamCodec.Geometry(1920, 1088, 2000, 8, 4)
        "high" -> StreamCodec.Geometry(1920, 1088, 2600, 4, 2)
        else -> StreamCodec.Geometry(1920, 1088, 3200, 2, 1)
    }

    fun skipPerPx(): Int = when (QUALITY) {
        "balanced" -> 4
        "high" -> 2
        else -> 1
    }

    fun mcPerPx(): Int = when (QUALITY) {
        "balanced" -> 7
        "high" -> 4
        else -> 3
    }

    fun defaultBudget(): Int = when (QUALITY) {
        "balanced" -> 140_000
        "high" -> 240_000
        else -> 480_000
    }

    fun carrier(): StreamGeometry = StreamGeometry(
        slots = CODEC_1080.slots,
        regionX = 0,
        regionY = 0,
        mapIdBase = 32_000,
        fps = 60.0,
        probe = false,
        probeMode = 0,
        gridColumns = 12,
        codec = true,
    )
}
