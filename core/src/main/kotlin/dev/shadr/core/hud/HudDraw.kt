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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class HudDraw(
    val key: String,
    val kind: Kind,
    val translation: Vec3,
    val scale: Vec3,
    val content: String = "",
    val item: String? = null,
    val itemModel: String? = null,
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

@kotlinx.serialization.Serializable
data class RenderBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val rotationDeg: Double = 0.0,
) {
    val centerX: Double get() = x + width / 2.0
    val centerY: Double get() = y + height / 2.0

    fun union(other: RenderBox): RenderBox {
        if (rotationDeg != 0.0 || other.rotationDeg != 0.0) return this
        val left = minOf(x, other.x)
        val top = minOf(y, other.y)
        val right = maxOf(x + width, other.x + other.width)
        val bottom = maxOf(y + height, other.y + other.height)
        return RenderBox(left, top, right - left, bottom - top)
    }

    fun translate(dx: Double, dy: Double): RenderBox = copy(x = x + dx, y = y + dy)
}

data class HitRegion(
    val elementId: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val layer: Double,
    val interactive: Boolean,
    val rotationDeg: Double = 0.0,
) {
    fun contains(px: Double, py: Double): Boolean {
        if (rotationDeg == 0.0) return px >= x && px <= x + width && py >= y && py <= y + height
        val cx = x + width / 2.0
        val cy = y + height / 2.0
        val radians = Math.toRadians(-rotationDeg)
        val dx = px - cx
        val dy = py - cy
        val lx = dx * cos(radians) - dy * sin(radians)
        val ly = dx * sin(radians) + dy * cos(radians)
        return abs(lx) <= width / 2.0 && abs(ly) <= height / 2.0
    }

    fun toBox(): RenderBox = RenderBox(x, y, width, height, rotationDeg)
}

data class RenderedPage(
    val draws: List<HudDraw>,
    val hitRegions: List<HitRegion>,
    val renderBoxes: Map<String, RenderBox> = emptyMap(),
) {
    /** Highest layer wins; equal layers are z-fighting in game, so the first declared is picked. */
    fun hitTest(x: Double, y: Double): HitRegion? =
        hitRegions.filter { it.interactive && it.contains(x, y) }.maxByOrNull { it.layer }
}
