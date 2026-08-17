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
import dev.shadr.core.text.Glyphs
import kotlin.math.max
import kotlin.math.min

class PageRenderer(
    private val calculator: HudPositionCalculator = HudPositionCalculator(),
    private val fixShaders: Boolean = false,
    private val fixShadersLayerGap: Double = HudPositionCalculator.DEFAULT_FIX_SHADERS_LAYER_GAP,
    private val debugHitboxes: Boolean = false,
    private val interpolationTicks: Int = 1,
) {
    fun render(page: Page): RenderedPage {
        val draws = mutableListOf<HudDraw>()
        val regions = mutableListOf<HitRegion>()
        for (element in page.elements) {
            if (!element.enabled) continue
            renderElement(element, page, draws)
            regions += hitRegion(element, page, hitLayer(element))
        }
        return RenderedPage(draws, regions)
    }

    private fun renderElement(element: Element, page: Page, out: MutableList<HudDraw>) {
        val visible = element.opacity > 0 && (element.type != ElementType.HITBOX || debugHitboxes)
        if (!visible) return

        val x = element.x + page.screen.offsetX
        val yTop = element.y + page.screen.offsetY
        val layer = if (element.type == ElementType.BLUR) {
            HudPositionCalculator.BLUR_PANEL_LAYER
        } else {
            runtimeLayer(element.layer)
        }

        when (element.type) {
            ElementType.ITEM, ElementType.SHADER -> out += itemDraw(element, x, yTop, layer)
            ElementType.TEXT -> out += textDraw(element, x, yTop, layer)
            ElementType.BLOCK_SDF -> {
                element.outline?.let { out += sdfOutlineDraw(element, x, yTop, layer, it) }
                out += sdfBoxDraw(element, x, yTop, layer)
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
                out += if (rounded) sdfBoxDraw(element, x, yTop, layer) else blockDraw(element, x, yTop, layer)
            }
        }
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

    private fun isDistanceField(element: Element): Boolean = element.font in DISTANCE_FIELD_FONTS

    private fun itemDraw(element: Element, x: Double, y: Double, layer: Double): HudDraw {
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
            scale = placement.scale,
            item = element.item,
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

    private fun cornerBucketFor(element: Element): Int {
        val rounding = element.rounding
        val radius = rounding?.radius
        if (radius != null) {
            return ShapeBuckets.bucketForRadius(radius, element.width, element.height)
        }
        return ShapeBuckets.bucketFor(presetFractionOf(rounding?.size ?: RoundingSize.REGULAR))
    }

    private fun presetFractionOf(size: RoundingSize): Double = when (size) {
        RoundingSize.NONE -> 0.0
        RoundingSize.SMALL -> 0.06
        RoundingSize.MEDIUM -> 0.12
        RoundingSize.REGULAR -> 0.20
        RoundingSize.LARGE -> 0.35
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
        else -> element.interaction.actionable
    }

    private fun hitRegion(element: Element, page: Page, layer: Double): HitRegion = HitRegion(
        elementId = element.id,
        x = element.x + page.screen.offsetX + page.screen.hitboxOffsetX + element.interaction.hitboxOffsetX,
        y = element.y + page.screen.offsetY + page.screen.hitboxOffsetY + element.interaction.hitboxOffsetY,
        width = element.width,
        height = element.height,
        layer = layer,
        interactive = takesInput(element),
    )

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
        const val SHAPE_ITEM = "minecraft:leather_horse_armor"

        val DISTANCE_FIELD_FONTS = setOf(Glyphs.FONT_UI_SHARP, Glyphs.FONT_UI_SHARP_SEMIBOLD)

        const val ITEM_VERTICAL_OFFSET_RATIO = 0.56

        const val SDF_QUAD_SCALE = 64.0 / 40.0
        const val OUTLINE_LAYER_EPSILON = 0.01
    }
}
