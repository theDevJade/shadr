/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.hud

import dev.shadr.core.HudAlignment
import dev.shadr.core.TextAlignment
import dev.shadr.core.Vec3

data class HudDraw(
    val key: String,
    val kind: Kind,
    val translation: Vec3,
    val scale: Vec3,
    val content: String = "",
    val item: String? = null,
    val itemCustomModelData: Int? = null,
    val tint: dev.shadr.core.Rgb? = null,
    val cornerFraction: Double? = null,
    val opacity: Int = 255,
    val alignment: HudAlignment = HudAlignment.CENTER,
    val textAlignment: TextAlignment = TextAlignment.CENTER,
    val lineWidth: Int = DisplayMeta.DEFAULT_TEXT_WRAP_LINE_WIDTH,
    val rotationDeg: Double = 0.0,
    val interpolationDelay: Int = 0,
    val interpolationDuration: Int = 0,
    val distanceField: Boolean = false,
    val elementId: String = key,
) {
    enum class Kind { TEXT, ITEM }

    val textFlags: Byte get() = DisplayMeta.textFlags(textAlignment)

    val displayText: String get() = if (kind == Kind.TEXT) DisplayMeta.TEXT_LAYOUT_PREFIX + content else ""
}

data class HitRegion(
    val elementId: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val layer: Double,
    val interactive: Boolean,
) {
    fun contains(px: Double, py: Double): Boolean =
        px >= x && px <= x + width && py >= y && py <= y + height
}

data class RenderedPage(
    val draws: List<HudDraw>,
    val hitRegions: List<HitRegion>,
) {
    fun hitTest(x: Double, y: Double): HitRegion? =
        hitRegions.filter { it.interactive && it.contains(x, y) }.maxByOrNull { it.layer }
}
