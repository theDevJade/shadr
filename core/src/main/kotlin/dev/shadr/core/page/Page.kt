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
    val hud: Boolean = false,
) {
    val locksCamera: Boolean get() = !hud
}

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

    VIDEO("video"),

    HITBOX("hitbox"),

    TEXT_INPUT("text_input"),

    TOGGLE("toggle"),

    SLIDER("slider"),

    COMPONENT("component"),

    GRID("grid_block");

    val sourceKey: String
        get() = when (this) {
            SHADER -> "shader"
            VIDEO -> "video"
            else -> "item"
        }

    val supportsRounding: Boolean
        get() = this == BLOCK || this == BLOCK_ROUNDED || this == BLOCK_SDF ||
            this == TEXT_INPUT || this == TOGGLE || this == SLIDER

    val roundedByDefault: Boolean
        get() = this == BLOCK_ROUNDED || this == BLOCK_SDF || this == TEXT_INPUT ||
            this == TOGGLE || this == SLIDER

    val isControl: Boolean get() = this == TEXT_INPUT || this == TOGGLE || this == SLIDER

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
    val stream: Boolean = false,
    val itemCustomModelData: Int? = null,
    val playerHeadText: Boolean = false,
    val interaction: Interaction = Interaction(),
    val dynamic: DynamicFields = emptyMap(),
    val sourcePath: String = "",
    val originX: Double = 0.0,
    val originY: Double = 0.0,
    val componentName: String? = null,
    val input: TextInput? = null,
    val toggle: Toggle? = null,
    val slider: Slider? = null,
) {
    val isBlockish: Boolean get() = !type.isTextual && type != ElementType.ITEM

    val centerX: Double get() = x + width / 2.0
    val centerY: Double get() = y + height / 2.0
}

@Serializable
data class TextInput(
    val placeholder: String = "",
    val value: String = "",
    val maxLength: Int = DEFAULT_MAX_LENGTH,
    val lines: Int = 1,
    val secret: Boolean = false,
    val textColor: Rgb? = null,
    val placeholderColor: Rgb? = null,
    val focusEffect: String? = null,
    val hoverOutline: Rgb? = null,
    val focusOutline: Rgb? = null,
    val fontSize: Double = 32.0,
    val padding: Double = 10.0,
    val onSubmit: List<ActionSpec> = emptyList(),
) {
    val clampedLines: Int get() = lines.coerceIn(1, SIGN_LINES)

    fun clamp(raw: String): String = raw.take(maxLength.coerceIn(1, HARD_MAX_LENGTH))

    fun outlineFor(base: Rgb, hovered: Boolean, focused: Boolean): Rgb = when {
        focused -> focusOutline ?: lift(base, FOCUS_LIFT)
        hovered -> hoverOutline ?: lift(base, HOVER_LIFT)
        else -> base
    }

    fun display(current: String): String = when {
        current.isEmpty() -> placeholder
        secret -> "\u2022".repeat(current.length)
        else -> current
    }

    companion object {
        const val HOVER_LIFT = 0.35

        const val FOCUS_LIFT = 0.6

        private fun lift(colour: Rgb, amount: Double): Rgb {
            fun channel(value: Int) = (value + (255 - value) * amount).toInt().coerceIn(0, 255)
            return Rgb((channel(colour.r) shl 16) or (channel(colour.g) shl 8) or channel(colour.b))
        }

        const val SIGN_LINES = 4

        const val DEFAULT_MAX_LENGTH = 60

        const val HARD_MAX_LENGTH = 384
    }
}

@Serializable
data class Toggle(
    val value: Boolean = false,
    val onColor: Rgb = Rgb(0x4C8DFF),
    val offColor: Rgb = Rgb(0x3A3A47),
    val knobColor: Rgb = Rgb(0xF2F2F7),
    val onChange: List<ActionSpec> = emptyList(),
) {
    fun knobFraction(on: Boolean): Double = if (on) 1.0 else 0.0

    fun trackColor(on: Boolean): Rgb = if (on) onColor else offColor
}

@Serializable
data class Slider(
    val value: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 100.0,
    val step: Double = 0.0,
    val trackColor: Rgb = Rgb(0x3A3A47),
    val fillColor: Rgb = Rgb(0x4C8DFF),
    val knobColor: Rgb = Rgb(0xF2F2F7),
    val onChange: List<ActionSpec> = emptyList(),
) {
    private val span: Double get() = (max - min).takeIf { it > 0.0 } ?: 1.0

    fun clamp(raw: Double): Double {
        val bounded = raw.coerceIn(minOf(min, max), maxOf(min, max))
        if (step <= 0.0) return bounded
        return (min + Math.round((bounded - min) / step) * step).coerceIn(minOf(min, max), maxOf(min, max))
    }

    fun fractionOf(value: Double): Double = ((clamp(value) - min) / span).coerceIn(0.0, 1.0)

    fun valueAt(fraction: Double): Double = clamp(min + fraction.coerceIn(0.0, 1.0) * span)

    fun format(value: Double): String {
        val clamped = clamp(value)
        return if (step > 0.0 && step % 1.0 == 0.0 || clamped % 1.0 == 0.0) {
            clamped.toLong().toString()
        } else {
            "%.2f".format(clamped)
        }
    }
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
) {
    val actionable: Boolean
        get() = onClick.isNotEmpty() || onLeftClick.isNotEmpty() || onRightClick.isNotEmpty() ||
            hoverText != null || hoverEffect != null || clickEffect != null
}

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
