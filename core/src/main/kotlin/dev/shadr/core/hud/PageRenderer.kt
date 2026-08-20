/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.hud

import dev.shadr.core.RoundingSize
import dev.shadr.core.Vec3
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Page
import dev.shadr.core.page.Slider
import dev.shadr.core.page.TextInput
import dev.shadr.core.page.Toggle
import dev.shadr.core.text.Glyphs
import dev.shadr.core.text.MetricsTable
import kotlin.math.max
import kotlin.math.min

class PageRenderer(
    private val calculator: HudPositionCalculator = HudPositionCalculator(),
    private val fixShaders: Boolean = false,
    private val fixShadersLayerGap: Double = HudPositionCalculator.DEFAULT_FIX_SHADERS_LAYER_GAP,
    private val debugHitboxes: Boolean = false,
    private val interpolationTicks: Int = 1,
    private val metrics: MetricsTable = MetricsTable.EMPTY,
) {
    fun render(page: Page): RenderedPage {
        val draws = mutableListOf<HudDraw>()
        val regions = mutableListOf<HitRegion>()
        val boxes = linkedMapOf<String, RenderBox>()
        for (element in page.elements) {
            if (!element.enabled) continue
            renderElement(element, page, draws, boxes)
            regions += hitRegion(element, page, hitLayer(element), boxes[element.id])
        }
        return RenderedPage(draws, regions, boxes)
    }

    private fun record(boxes: MutableMap<String, RenderBox>, id: String, box: RenderBox) {
        val existing = boxes[id]
        boxes[id] = if (existing == null) box else existing.union(box)
    }

    private fun quadBox(
        element: Element,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ): RenderBox = calculator.designBox(x, y, width, height, element.rotationDeg)

    private fun renderElement(
        element: Element,
        page: Page,
        out: MutableList<HudDraw>,
        boxes: MutableMap<String, RenderBox>,
    ) {
        val visible = element.opacity > 0 && (element.type != ElementType.HITBOX || debugHitboxes)
        if (!visible) {
            record(boxes, element.id, hiddenBox(element, page))
            return
        }

        val x = element.x + page.screen.offsetX
        val yTop = element.y + page.screen.offsetY
        val layer = if (element.type == ElementType.BLUR) {
            HudPositionCalculator.BLUR_PANEL_LAYER
        } else {
            runtimeLayer(element.layer)
        }

        when (element.type) {
            ElementType.ITEM, ElementType.SHADER, ElementType.VIDEO -> {
                out += itemDraw(element, x, yTop, layer)
                record(boxes, element.id, itemBox(element, x, yTop))
            }
            ElementType.TEXT -> {
                out += textDraw(element, x, yTop, layer)
                record(boxes, element.id, textBox(element, x, yTop))
            }
            ElementType.BLOCK_SDF -> {
                element.outline?.let { out += sdfOutlineDraw(element, x, yTop, layer, it) }
                out += sdfBoxDraw(element, x, yTop, layer)
                record(boxes, element.id, sdfBox(element, x, yTop, element.width, element.height))
            }
            ElementType.TEXT_INPUT -> {
                element.outline?.let { out += sdfOutlineDraw(element, x, yTop, layer, it) }
                out += sdfBoxDraw(element, x, yTop, layer)
                out += inputLabelDraw(element, x, yTop, layer)
                record(boxes, element.id, sdfBox(element, x, yTop, element.width, element.height))
            }
            ElementType.TOGGLE -> {
                out += toggleDraws(element, x, yTop, layer)
                record(boxes, element.id, sdfBox(element, x, yTop, element.width, element.height))
            }
            ElementType.SLIDER -> {
                out += sliderDraws(element, x, yTop, layer)
                record(boxes, element.id, sdfBox(element, x, yTop, element.width, element.height))
            }
            else -> {
                val rounded = roundedRadius(element) > 0.0
                element.outline?.let {
                    out += if (rounded) {
                        sdfOutlineDraw(element, x, yTop, layer, it)
                    } else {
                        outlineDraw(element, x, yTop, layer, it)
                    }
                }
                if (rounded) {
                    out += sdfBoxDraw(element, x, yTop, layer)
                    record(boxes, element.id, sdfBox(element, x, yTop, element.width, element.height))
                } else {
                    out += blockDraw(element, x, yTop, layer)
                    record(boxes, element.id, quadBox(element, x, yTop, element.width, element.height))
                }
            }
        }
    }

    private fun hiddenBox(element: Element, page: Page): RenderBox = calculator.designBox(
        element.x + page.screen.offsetX, element.y + page.screen.offsetY,
        element.width, element.height, element.rotationDeg,
    )

    private fun sdfBox(element: Element, x: Double, y: Double, width: Double, height: Double): RenderBox =
        quadBox(element, x, y, width, height)

    private fun itemBox(element: Element, x: Double, y: Double): RenderBox =
        quadBox(element, x, y, element.width, element.height)

    private fun textBox(element: Element, x: Double, y: Double): RenderBox {
        val lines = metrics.wrap(element.font, element.text, element.lineWidth)
        val perFontPixel = MetricsTable.designPerFontPixel(element.height)
        val widest = lines.maxOfOrNull { metrics.measure(element.font, it) } ?: 0.0
        val width = max(1.0, widest * perFontPixel)
        val height = max(1.0, lines.size * metrics.font(element.font).lineHeight * perFontPixel)
        val left = when (element.textAlignment) {
            dev.shadr.core.TextAlignment.LEFT -> x
            dev.shadr.core.TextAlignment.RIGHT -> x - width
            dev.shadr.core.TextAlignment.CENTER -> x - width / 2.0
        }
        return calculator.designBox(left, y, width, height, element.rotationDeg)
    }

    private fun blockDraw(element: Element, x: Double, y: Double, layer: Double): HudDraw {
        val placement = calculator.calculateBoxPlacement(x, y, layer, element.width, element.height)
        return draw(
            key = element.id,
            element = element,
            placement = placement,
            content = colored(element, glyphOf(element)),
        )
    }

    private fun textDraw(element: Element, x: Double, y: Double, layer: Double): HudDraw {
        val internalY = HudPositionCalculator.toInternalTextTopY(y, element.height)
        val alignedX = HudPositionCalculator.textAlignmentOffsetX(x, element.width, element.textAlignment)
        val placement = calculator.calculateBoxPlacement(alignedX, internalY, layer, element.width, element.height)
        return draw(
            key = element.id,
            element = element,
            placement = placement,
            content = colored(element, element.text),
        )
    }

    private fun inputLabelDraw(element: Element, x: Double, y: Double, layer: Double): HudDraw {
        val input = element.input ?: TextInput()
        val current = input.value
        val shown = input.display(current)
        val colour = when {
            current.isEmpty() -> input.placeholderColor ?: PLACEHOLDER_COLOR
            else -> input.textColor ?: element.color
        }

        val label = element.copy(
            type = ElementType.TEXT,
            text = shown,
            color = colour,
            width = input.fontSize,
            height = input.fontSize,
            textAlignment = dev.shadr.core.TextAlignment.LEFT,
            outline = null,
            rounding = null,
        )

        val labelX = x + input.padding
        val labelY = y + element.height / 2.0 - TEXT_CENTRE_FACTOR * input.fontSize
        val internalY = HudPositionCalculator.toInternalTextTopY(labelY, input.fontSize)
        val alignedX = HudPositionCalculator.textAlignmentOffsetX(
            labelX, input.fontSize, dev.shadr.core.TextAlignment.LEFT,
        )
        val placement = calculator.calculateBoxPlacement(
            alignedX, internalY, layer + LABEL_LAYER_STEP, input.fontSize, input.fontSize,
        )
        return draw(
            key = "${element.id}__value",
            element = label,
            placement = placement,
            content = colored(label, shown),
        )
    }

    private fun pill(
        key: String,
        element: Element,
        x: Double,
        y: Double,
        layer: Double,
        width: Double,
        height: Double,
        colour: dev.shadr.core.Rgb,
    ): HudDraw {
        return sdfQuad(
            key = key,
            element = element,
            x = x,
            y = y,
            width = width,
            height = height,
            layer = layer,
            radius = min(width, height) / 2.0,
            tint = colour,
        )
    }

    private fun toggleDraws(element: Element, x: Double, y: Double, layer: Double): List<HudDraw> {
        val toggle = element.toggle ?: Toggle()
        val on = toggle.value
        val trackHeight = element.height
        val knob = (trackHeight - KNOB_INSET * 2.0).coerceAtLeast(2.0)
        val travel = (element.width - knob - KNOB_INSET * 2.0).coerceAtLeast(0.0)
        val knobX = x + KNOB_INSET + travel * toggle.knobFraction(on)

        return listOf(
            pill("${element.id}", element, x, y, layer, element.width, trackHeight, toggle.trackColor(on)),
            pill(
                "${element.id}__knob", element, knobX, y + KNOB_INSET,
                layer + LABEL_LAYER_STEP, knob, knob, toggle.knobColor,
            ),
        )
    }

    private fun sliderDraws(element: Element, x: Double, y: Double, layer: Double): List<HudDraw> {
        val slider = element.slider ?: Slider()
        val fraction = slider.fractionOf(slider.value)
        val track = (element.height * TRACK_HEIGHT_RATIO).coerceAtLeast(2.0)
        val trackY = y + (element.height - track) / 2.0
        val knob = element.height.coerceAtLeast(track)
        val travel = (element.width - knob).coerceAtLeast(0.0)

        val out = mutableListOf(
            pill("${element.id}", element, x, trackY, layer, element.width, track, slider.trackColor),
        )
        val filled = knob / 2.0 + travel * fraction
        if (filled > 0.0) {
            out += pill(
                "${element.id}__fill", element, x, trackY,
                layer + LABEL_LAYER_STEP, filled, track, slider.fillColor,
            )
        }
        out += pill(
            "${element.id}__knob", element, x + travel * fraction, y,
            layer + LABEL_LAYER_STEP * 2.0, knob, knob, slider.knobColor,
        )
        return out
    }

    private fun isDistanceField(element: Element): Boolean = element.font in DISTANCE_FIELD_FONTS

    private fun itemDraw(element: Element, x: Double, y: Double, layer: Double): HudDraw {
        val video = element.type == ElementType.VIDEO
        val placement = calculator.calculateBoxPlacement(
            x, y - element.height * ITEM_VERTICAL_OFFSET_RATIO, layer, element.width, element.height,
        )
        val thickness = if (element.item?.let(::looksLikeBlock) == true) {
            HudPositionCalculator.ItemThickness.BLOCK
        } else {
            HudPositionCalculator.ItemThickness.ITEM
        }
        return HudDraw(
            key = element.id,
            kind = HudDraw.Kind.ITEM,
            translation = calculator.toDisplayTranslation(
                placement.location, placement.scale, element.hudAlignment, thickness,
            ),
            scale = if (video) {
                Vec3(
                    placement.scale.x * SDF_QUAD_SCALE,
                    placement.scale.y * SDF_QUAD_SCALE,
                    placement.scale.z,
                )
            } else {
                placement.scale
            },
            item = if (video) SHAPE_ITEM else element.item,
            itemModel = if (video) {
                element.item?.let(dev.shadr.core.video.VideoClip::itemModelOf)
            } else {
                null
            },
            itemCustomModelData = element.itemCustomModelData,
            opacity = element.opacity,
            alignment = element.hudAlignment,
            rotationDeg = element.rotationDeg,
            interpolationDuration = interpolationTicks,
            elementId = element.id,
        )
    }

    private fun sdfBoxDraw(element: Element, x: Double, y: Double, layer: Double): HudDraw =
        sdfQuad(
            key = element.id,
            element = element,
            x = x,
            y = y,
            width = element.width,
            height = element.height,
            layer = layer,
            radius = roundedRadius(element),
            tint = element.color,
        )

    private fun sdfOutlineDraw(
        element: Element,
        x: Double,
        y: Double,
        layer: Double,
        outline: dev.shadr.core.page.Outline,
    ): HudDraw {
        val grow = outline.size
        return sdfQuad(
            key = "${element.id}__outline",
            element = element,
            x = x - grow,
            y = y - grow,
            width = element.width + grow * 2.0,
            height = element.height + grow * 2.0,
            layer = outline.layer?.let(::runtimeLayer) ?: (layer - OUTLINE_LAYER_EPSILON),
            radius = roundedRadius(element) + grow,
            tint = outline.color,
        )
    }

    private fun sdfQuad(
        key: String,
        element: Element,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        layer: Double,
        radius: Double,
        tint: dev.shadr.core.Rgb,
    ): HudDraw {
        val placement = calculator.calculateBoxPlacement(
            x, y - height * ITEM_VERTICAL_OFFSET_RATIO, layer, width, height,
        )
        val bucket = ShapeBuckets.bucketForRadius(radius, width, height)
        return HudDraw(
            key = key,
            kind = HudDraw.Kind.ITEM,
            translation = calculator.toDisplayTranslation(
                placement.location, placement.scale, element.hudAlignment,
                distanceField = true,
            ),
            scale = Vec3(
                placement.scale.x * SDF_QUAD_SCALE,
                placement.scale.y * SDF_QUAD_SCALE,
                placement.scale.z,
            ),
            item = SHAPE_ITEM,
            itemCustomModelData = bucket,
            tint = tint,
            cornerFraction = ShapeBuckets.fractionFor(bucket),
            opacity = element.opacity,
            alignment = element.hudAlignment,
            rotationDeg = element.rotationDeg,
            interpolationDuration = interpolationTicks,
            distanceField = true,
            elementId = element.id,
        )
    }

    private fun outlineDraw(element: Element, x: Double, y: Double, layer: Double, outline: dev.shadr.core.page.Outline): HudDraw {
        val grow = outline.size
        val placement = calculator.calculateBoxPlacement(
            x - grow, y - grow, outline.layer?.let(::runtimeLayer) ?: (layer - OUTLINE_LAYER_EPSILON),
            element.width + grow * 2.0, element.height + grow * 2.0,
        )
        return draw(
            key = "${element.id}__outline",
            element = element,
            placement = placement,
            content = "<#${outline.color.hex()}><font:${element.font}>${glyphOf(element)}",
        )
    }

    /**
     * Rounding is a distance field, to prevent extra entity load.
     */
    private fun roundedRadius(element: Element): Double {
        val rounding = element.rounding ?: return 0.0
        return (rounding.radius ?: defaultRadius(rounding.size, element))
            .coerceIn(0.0, min(element.width, element.height) / 2.0)
    }


    private fun draw(
        key: String,
        element: Element,
        placement: HudPositionCalculator.Placement,
        content: String,
    ) = HudDraw(
        key = key,
        kind = HudDraw.Kind.TEXT,
        translation = calculator.toDisplayTranslation(
            placement.location, placement.scale, element.hudAlignment,
            distanceField = isDistanceField(element),
        ),
        scale = placement.scale,
        content = content,
        opacity = element.opacity,
        alignment = element.hudAlignment,
        textAlignment = element.textAlignment,
        lineWidth = element.lineWidth,
        rotationDeg = element.rotationDeg,
        interpolationDuration = interpolationTicks,
        distanceField = isDistanceField(element),
        elementId = element.id,
    )

    private fun hitLayer(element: Element): Double =
        if (element.type == ElementType.BLUR) {
            HudPositionCalculator.BLUR_PANEL_LAYER
        } else {
            element.layer
        }

    /**
     * A hitbox is an explicit hit area, so it takes input whether or not anything is bound yet.
     * A blur panel is a backdrop and never does.
     */
    private fun takesInput(element: Element): Boolean = when {
        !element.interaction.interactive || element.interaction.disableHitbox -> false
        element.type == ElementType.BLUR -> false
        element.type == ElementType.HITBOX -> true
        element.type.isControl -> true
        else -> element.interaction.actionable
    }

    private fun hitRegion(element: Element, page: Page, layer: Double, box: RenderBox?): HitRegion {
        val drawn = box ?: hiddenBox(element, page)
        val dx = page.screen.hitboxOffsetX + element.interaction.hitboxOffsetX
        val dy = page.screen.hitboxOffsetY + element.interaction.hitboxOffsetY
        return HitRegion(
            elementId = element.id,
            x = drawn.x + dx,
            y = drawn.y + dy,
            width = drawn.width,
            height = drawn.height,
            layer = layer,
            interactive = takesInput(element),
            rotationDeg = element.rotationDeg,
        )
    }

    private fun runtimeLayer(layer: Double): Double =
        if (fixShaders) calculator.toFixedShaderLayer(layer, fixShadersLayerGap) else layer

    private fun colored(element: Element, body: String) =
        "<#${element.color.hex()}><font:${element.font}>$body"

    private fun glyphOf(element: Element): String = when {
        element.type == ElementType.HITBOX -> Glyphs.BACKGROUND.toString()
        element.unicode.isNotBlank() -> element.unicode
        else -> element.type.defaultGlyph.toString()
    }

    private fun defaultRadius(size: RoundingSize, element: Element): Double {
        val shortest = min(element.width, element.height)
        return when (size) {
            RoundingSize.NONE -> 0.0
            RoundingSize.SMALL -> min(4.0, shortest / 2.0)
            RoundingSize.MEDIUM -> min(8.0, shortest / 2.0)
            RoundingSize.REGULAR -> min(14.0, shortest / 2.0)
            RoundingSize.LARGE -> min(24.0, shortest / 2.0)
        }
    }


    private fun looksLikeBlock(item: String): Boolean =
        !item.substringAfter(':').let { id ->
            id.endsWith("_sword") || id.endsWith("_pickaxe") || id.endsWith("_axe") ||
                id.endsWith("_shovel") || id.endsWith("_hoe") || id.endsWith("_ingot") ||
                id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings") ||
                id.endsWith("_boots") || id == "stick" || id == "string" || id == "paper"
        }

    companion object {
        const val TEXT_CENTRE_FACTOR = 0.1514

        const val LABEL_LAYER_STEP = 0.01

        const val KNOB_INSET = 3.0

        const val TRACK_HEIGHT_RATIO = 0.35

        val PLACEHOLDER_COLOR = dev.shadr.core.Rgb(0x6A6A7A)

        const val SHAPE_ITEM = "minecraft:leather_horse_armor"

        val DISTANCE_FIELD_FONTS = setOf(Glyphs.FONT_UI_SHARP, Glyphs.FONT_UI_SHARP_SEMIBOLD)

        const val ITEM_VERTICAL_OFFSET_RATIO = 0.56

        const val SDF_QUAD_SCALE = 64.0 / 40.0
        const val OUTLINE_LAYER_EPSILON = 0.01
    }
}
