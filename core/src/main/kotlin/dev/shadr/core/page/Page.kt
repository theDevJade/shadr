/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.page

import dev.shadr.core.HudAlignment
import dev.shadr.core.Interpolation
import dev.shadr.core.Rgb
import dev.shadr.core.RoundingSize
import dev.shadr.core.TextAlignment
import dev.shadr.core.text.Glyphs
import kotlinx.serialization.Serializable

@Serializable
data class Page(
    val name: String,
    val screen: ScreenDef = ScreenDef(),
    val elements: List<Element> = emptyList(),
    val animations: List<GuiAnimationDef> = emptyList(),
)

@Serializable
data class ScreenDef(
    val width: Double = 1920.0,
    val height: Double = 1080.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val previewDefaultZoom: Double = 0.8,
    val hitboxOffsetX: Double = 0.0,
    val hitboxOffsetY: Double = 0.0,
    val cursorSize: Double = 10.0,
    val cursorSpeed: Double = 1.0,
    val cursorUnicode: String = Glyphs.CURSOR.toString(),
    val cursorLayer: Double = 9700.0,
)

@Serializable
enum class ElementType(val id: String, val defaultGlyph: Char = Glyphs.BACKGROUND) {
    BLOCK("block"),

    BLOCK_ROUNDED("block_rounded"),

    CIRCLE("circle", Glyphs.CIRCLE),

    GRADIENT("gradient", Glyphs.GRADIENT),

    BLUR("blur"),

    PROGRESS("progress", Glyphs.SLIDER),

    BLOCK_SDF("block_sdf"),

    TEXT("text"),

    ITEM("item"),

    SHADER("shader"),

    IMAGE("image"),

    HITBOX("hitbox"),

    COMPONENT("component"),

    GRID("grid_block");

    val supportsRounding: Boolean
        get() = this == BLOCK || this == BLOCK_ROUNDED || this == BLOCK_SDF

    val roundedByDefault: Boolean get() = this == BLOCK_ROUNDED || this == BLOCK_SDF

    val isTextual: Boolean get() = this == TEXT

    companion object {
        fun parse(raw: String?): ElementType =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: BLOCK
    }
}

@Serializable
data class Element(
    val id: String,
    val type: ElementType,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 20.0,
    val height: Double = 20.0,
    val layer: Double = 0.0,
    val color: Rgb = Rgb.WHITE,
    val opacity: Int = 255,
    val unicode: String = Glyphs.BACKGROUND.toString(),
    val text: String = "",
    val font: String = Glyphs.FONT_UI,
    val hudAlignment: HudAlignment = HudAlignment.CENTER,
    val textAlignment: TextAlignment = TextAlignment.CENTER,
    val lineWidth: Int = 200,
    val enabled: Boolean = true,
    val outline: Outline? = null,
    val rounding: Rounding? = null,
    val rotationDeg: Double = 0.0,
    val pivotOffsetX: Double = 0.0,
    val pivotOffsetY: Double = 0.0,
    val mirrorX: Boolean = false,
    val mirrorY: Boolean = false,
    val item: String? = null,
    val itemCustomModelData: Int? = null,
    val playerHeadText: Boolean = false,
    val interaction: Interaction = Interaction(),
    val dynamic: DynamicFields = emptyMap(),
    val sourcePath: String = "",
    val originX: Double = 0.0,
    val originY: Double = 0.0,
    val componentName: String? = null,
) {
    val isBlockish: Boolean get() = !type.isTextual && type != ElementType.ITEM

    val centerX: Double get() = x + width / 2.0
    val centerY: Double get() = y + height / 2.0
}

@Serializable
data class Outline(val size: Double, val color: Rgb, val layer: Double? = null)

@Serializable
data class Rounding(
    val size: RoundingSize = RoundingSize.REGULAR,
    val radius: Double? = null,
    val unicode: String? = null,
    val topLeft: CornerSpec = CornerSpec(),
    val topRight: CornerSpec = CornerSpec(),
    val bottomRight: CornerSpec = CornerSpec(),
    val bottomLeft: CornerSpec = CornerSpec(),
)

@Serializable
data class CornerSpec(val unicode: String? = null, val offsetX: Double = 0.0, val offsetY: Double = 0.0)

@Serializable
data class Interaction(
    val interactive: Boolean = true,
    val disableHitbox: Boolean = false,
    val hitboxOffsetX: Double = 0.0,
    val hitboxOffsetY: Double = 0.0,
    val hoverText: String? = null,
    val hoverEffect: String? = null,
    val clickEffect: String? = null,
    val onClick: List<ActionSpec> = emptyList(),
    val onLeftClick: List<ActionSpec> = emptyList(),
    val onRightClick: List<ActionSpec> = emptyList(),
    val permission: String? = null,
)

typealias DynamicFields = Map<String, String>

@Serializable
data class ActionSpec(val verb: String, val argument: String)

@Serializable
data class EffectDef(
    val id: String,
    val name: String = id,
    val moveX: Double = 0.0,
    val moveY: Double = 0.0,
    val scaleXPercent: Double = 0.0,
    val scaleYPercent: Double = 0.0,
    val opacityDelta: Int = 0,
    val rotationDeg: Double = 0.0,
    val durationMs: Long = 250,
    val interpolation: Interpolation = Interpolation.EASE_IN_OUT,
) {
    fun applyTo(element: Element): Element {
        val dw = element.width * scaleXPercent / 100.0
        val dh = element.height * scaleYPercent / 100.0
        return element.copy(
            x = element.x + moveX - dw / 2.0,
            y = element.y + moveY - dh / 2.0,
            width = element.width + dw,
            height = element.height + dh,
            opacity = (element.opacity + opacityDelta).coerceIn(0, 255),
            rotationDeg = element.rotationDeg + rotationDeg,
        )
    }

    val durationTicks: Int get() = ((durationMs * 20.0) / 1000.0).toInt().coerceAtLeast(1)
}

@Serializable
data class GuiAnimationDef(
    val name: String,
    val durationTicks: Int,
    val steps: List<AnimationStep> = emptyList(),
)

@Serializable
data class AnimationStep(
    val target: String,
    val axis: String,
    val easing: Interpolation = Interpolation.LINEAR,
    val from: Double = 0.0,
    val to: Double = 0.0,
    val values: List<Double> = emptyList(),
    val delayTicks: Int = 0,
    val durationTicks: Int = 0,
)
