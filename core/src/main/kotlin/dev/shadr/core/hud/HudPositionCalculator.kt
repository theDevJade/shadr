/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.hud

import dev.shadr.core.Vec3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

class HudPositionCalculator {
    /**
     * @param ownerWidth,ownerHeight the size of the element this quad belongs to.
     */
    fun calculateBoxPlacement(
        x: Double,
        y: Double,
        layer: Double,
        width: Double,
        height: Double,
        applyTopDrift: Boolean = true,
        ownerWidth: Double = width,
        ownerHeight: Double = height,
    ): Placement {
        val w = max(1.0, width)
        val h = max(1.0, height)
        val ownerW = max(1.0, ownerWidth)
        val ownerH = max(1.0, ownerHeight)
        val hudWidth = w * YAML_TO_HUD_SIZE_FACTOR
        val hudHeight = h * YAML_TO_HUD_SIZE_FACTOR

        val leftCompensation = ownerW * YAML_TO_HUD_SIZE_FACTOR / BOX_LEFT_COMP_DIVISOR
        val topCompensation = ownerH * YAML_TO_HUD_SIZE_FACTOR / BOX_TOP_COMP_DIVISOR

        val topDrift = if (applyTopDrift) {
            max(0.0, (ownerH - BOX_TOP_DRIFT_START_HEIGHT) * BOX_TOP_DRIFT_PER_HEIGHT)
        } else {
            0.0
        }

        val location = Vec3(
            x + w / 2.0 - leftCompensation + leftStepCorrection(ownerW),
            y + h + topCompensation - topDrift,
            layer,
        )
        val scale = Vec3(hudWidth / 2.0, hudHeight / 2.0, DISPLAY_THICKNESS)
        return Placement(location, scale)
    }

    fun placementDelta(ownerWidth: Double, ownerHeight: Double): dev.shadr.core.Vec2 {
        val ownerW = max(1.0, ownerWidth)
        val ownerH = max(1.0, ownerHeight)
        val leftCompensation = ownerW * YAML_TO_HUD_SIZE_FACTOR / BOX_LEFT_COMP_DIVISOR
        val topCompensation = ownerH * YAML_TO_HUD_SIZE_FACTOR / BOX_TOP_COMP_DIVISOR
        val topDrift = max(0.0, (ownerH - BOX_TOP_DRIFT_START_HEIGHT) * BOX_TOP_DRIFT_PER_HEIGHT)
        return dev.shadr.core.Vec2(
            leftStepCorrection(ownerW) - leftCompensation,
            topCompensation - topDrift,
        )
    }

    fun designBox(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        rotationDeg: Double = 0.0,
    ): RenderBox = RenderBox(x, y, max(1.0, width), max(1.0, height), rotationDeg)

    private fun leftStepCorrection(width: Double): Double {
        if (width <= BOX_LEFT_STEP_START_WIDTH) return 0.0
        val steps = floor((width - BOX_LEFT_STEP_START_WIDTH) / BOX_LEFT_STEP_PERIOD) + 1.0
        return max(0.0, steps)
    }

    fun toDisplayTranslation(
        location: Vec3,
        scale: Vec3,
        alignment: dev.shadr.core.HudAlignment = dev.shadr.core.HudAlignment.CENTER,
        itemThickness: ItemThickness = ItemThickness.NONE,
        layerZPixelMultiplier: Double = LEGACY_LAYER_Z_PIXEL_MULTIPLIER,
        hudProjected: Boolean = true,
        distanceField: Boolean = false,
    ): Vec3 {
        val depthOffset = when (itemThickness) {
            ItemThickness.NONE -> 0.0
            ItemThickness.BLOCK -> scale.z / 2.0
            ItemThickness.ITEM -> scale.z / 32.0
        }
        val x = SCREEN_X / 2.0 - location.x
        val y = if (hudProjected) {
            HUD_Y_BASE + (SCREEN_Y / 2.0 - location.y) - alignedOffset(alignment) -
                (if (distanceField) FIELD_BAND else 0.0)
        } else {
            SCREEN_Y / 2.0 - location.y
        }
        val z = DISPLAY_THICKNESS * depthOffset - location.z * layerZPixelMultiplier
        return Vec3(x, y, z)
    }

    fun toFixedShaderLayer(layer: Double, layerGap: Double = DEFAULT_FIX_SHADERS_LAYER_GAP): Double {
        val rebased = if (layer >= FIX_SHADERS_LAYER_REBASE_THRESHOLD) layer - FIX_SHADERS_LAYER_REBASE else layer
        return rebased * layerGap
    }

    enum class ItemThickness { NONE, BLOCK, ITEM }

    data class Placement(val location: Vec3, val scale: Vec3) {
        val designWidth: Double get() = scale.x * 2.0 / YAML_TO_HUD_SIZE_FACTOR
        val designHeight: Double get() = scale.y * 2.0 / YAML_TO_HUD_SIZE_FACTOR
    }

    companion object {
        const val YAML_TO_HUD_SIZE_FACTOR = 1.25
        const val SCREEN_X = 1920.0
        const val SCREEN_Y = 1080.0
        const val BOX_LEFT_COMP_DIVISOR = 160.0
        const val BOX_TOP_COMP_DIVISOR = 36.0
        const val BOX_TOP_DRIFT_START_HEIGHT = 200.0
        const val BOX_TOP_DRIFT_PER_HEIGHT = 0.0032
        const val BOX_LEFT_STEP_START_WIDTH = 2876.0
        const val BOX_LEFT_STEP_PERIOD = 1920.0
        const val DISPLAY_THICKNESS = 1.0

        const val HUD_Y_BASE = -15000.178

        const val FIELD_BAND = 100_000.0

        const val BLUR_PANEL_LAYER = -5_000.0

        const val HUD_DEPTH_BASE = 0.95
        const val LAYER_TO_DEPTH = 1.0e-6
        val BLUR_PANEL_DEPTH: Double get() = HUD_DEPTH_BASE + BLUR_PANEL_LAYER * LAYER_TO_DEPTH

        const val LEGACY_LAYER_Z_PIXEL_MULTIPLIER = 100.0
        const val DEFAULT_FIX_SHADERS_LAYER_GAP = 2.0e-5
        const val FIX_SHADERS_LAYER_REBASE_THRESHOLD = 6000.0
        const val FIX_SHADERS_LAYER_REBASE = 7400.0

        fun alignedOffset(alignment: dev.shadr.core.HudAlignment): Int = when (alignment) {
            dev.shadr.core.HudAlignment.LEFT -> 10_000
            dev.shadr.core.HudAlignment.CENTER -> 20_000
            dev.shadr.core.HudAlignment.RIGHT -> 30_000
        }

        fun textAlignmentOffsetX(x: Double, width: Double, alignment: dev.shadr.core.TextAlignment): Double =
            when (alignment) {
                dev.shadr.core.TextAlignment.LEFT -> x + width * TEXT_ALIGNMENT_WIDTH_FACTOR
                dev.shadr.core.TextAlignment.RIGHT -> x - width * TEXT_ALIGNMENT_WIDTH_FACTOR
                dev.shadr.core.TextAlignment.CENTER -> x
            }

        const val TEXT_ALIGNMENT_WIDTH_FACTOR = 1.055

        fun toInternalTextTopY(y: Double, height: Double): Double {
            val h = max(1.0, height)
            val capHeight = max(1.0, h * TEXT_CAP_HEIGHT_RATIO)
            return y - (h - capHeight)
        }

        const val TEXT_CAP_HEIGHT_RATIO = 7.0 / 60.0
    }
}
