/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.session

import dev.shadr.core.PlayerId
import dev.shadr.core.ScreenPos
import dev.shadr.core.action.Action
import dev.shadr.core.action.ActionRunner
import dev.shadr.core.cursor.CursorPredictor
import dev.shadr.core.hud.HudDraw
import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.hud.RenderedPage
import dev.shadr.core.page.EffectDef
import dev.shadr.core.page.Element
import dev.shadr.core.page.Interaction
import dev.shadr.core.page.Page
import dev.shadr.core.text.Glyphs
import kotlin.math.max

class UiSession @JvmOverloads constructor(
    val player: PlayerId,
    private var page: Page,
    private val renderer: PageRenderer,
    private val effects: Map<String, EffectDef>,
    private val actionRunner: ActionRunner,
    private val predictor: CursorPredictor? = null,
    private val placeholders: dev.shadr.core.page.PlaceholderResolver =
        dev.shadr.core.page.PlaceholderResolver.NONE,
) {
    var cursor: ScreenPos = ScreenPos(page.screen.width / 2.0, page.screen.height / 2.0)
        private set

    private var hoveredId: String? = null
    private var pressedId: String? = null
    private var previousCursor: ScreenPos = cursor
    private var rendered: RenderedPage = renderer.render(page)

    private var dirty = true

    val currentPage: Page get() = page

    fun openPage(next: Page) {
        page = next
        hoveredId = null
        pressedId = null
        predictor?.reset()
        cursor = ScreenPos(next.screen.width / 2.0, next.screen.height / 2.0)
        rerender()
    }

    fun refreshPage(next: Page) {
        page = next
        rerender()
    }

    fun update(rawCursor: ScreenPos, deltaX: Double, deltaY: Double, pingMillis: Int): Boolean {
        cursor = predictor?.predict(
            rawX = rawCursor.x,
            rawY = rawCursor.y,
            deltaX = deltaX,
            deltaY = deltaY,
            minX = 0.0,
            maxX = page.screen.width,
            minY = 0.0,
            maxY = page.screen.height,
            screenWidth = page.screen.width,
            screenHeight = page.screen.height,
            pingMillis = pingMillis,
        ) ?: rawCursor

        if (cursor != previousCursor) {
            previousCursor = cursor
            dirty = true
        }

        val hit = rendered.hitTest(cursor.x, cursor.y)
        if (hit?.elementId != hoveredId) {
            hoveredId = hit?.elementId
            rerender()
        }
        return consumeDirty()
    }

    fun click(rightClick: Boolean = false): String? {
        val hit = rendered.hitTest(cursor.x, cursor.y) ?: return null
        val element = page.elements.firstOrNull { it.id == hit.elementId } ?: return null

        pressedId = element.id
        rerender()

        val actions = when {
            rightClick && element.interaction.onRightClick.isNotEmpty() -> element.interaction.onRightClick
            !rightClick && element.interaction.onLeftClick.isNotEmpty() -> element.interaction.onLeftClick
            else -> element.interaction.onClick
        }
        actionRunner.run(player, Action.from(actions), element.interaction.permission)
        return element.id
    }

    fun releaseClick() {
        if (pressedId == null) return
        pressedId = null
        rerender()
    }

    fun draws(): List<HudDraw> {
        val hoverTicks = effectTicks(hoveredId) { it.hoverEffect }
        val clickTicks = effectTicks(pressedId) { it.clickEffect }
        val draws = rendered.draws.map { draw ->
            val ticks = when (draw.elementId) {
                pressedId -> clickTicks
                hoveredId -> hoverTicks
                else -> null
            }
            if (ticks == null) draw else draw.copy(interpolationDuration = ticks)
        }
        return draws + hoverTextDraw() + cursorDraw()
    }

    private fun hoverTextDraw(): List<HudDraw> {
        val id = hoveredId ?: return emptyList()
        val element = page.elements.firstOrNull { it.id == id } ?: return emptyList()
        val raw = element.interaction.hoverText?.takeIf { it.isNotBlank() } ?: return emptyList()

        val text = dev.shadr.core.page.PlaceholderScanner.apply(raw, player, placeholders)
        val screen = page.screen
        val margin = HOVER_TEXT_SIZE
        val tooltip = Element(
            id = HOVER_TEXT_ELEMENT_ID,
            type = dev.shadr.core.page.ElementType.TEXT,
            x = cursor.x.coerceIn(margin, max(margin, screen.width - margin)),
            y = (cursor.y + screen.cursorSize).coerceAtMost(screen.height - HOVER_TEXT_SIZE),
            width = HOVER_TEXT_SIZE,
            height = HOVER_TEXT_SIZE,
            layer = screen.cursorLayer - 1.0,
            text = text,
        )
        return renderer.render(page.copy(elements = listOf(tooltip))).draws
    }

    private fun effectTicks(elementId: String?, pick: (Interaction) -> String?): Int? {
        val element = elementId?.let { id -> page.elements.firstOrNull { it.id == id } } ?: return null
        val effect = pick(element.interaction)?.trim()?.let { effects[it] } ?: return null
        return effect.durationTicks
    }

    fun consumeDirty(): Boolean {
        val was = dirty
        dirty = false
        return was
    }

    private fun rerender() {
        val transformed = page.elements.map { element ->
            var result = element
            if (element.id == hoveredId) result = applyEffect(result, element.interaction.hoverEffect)
            if (element.id == pressedId) result = applyEffect(result, element.interaction.clickEffect)
            resolvePlaceholders(result)
        }
        rendered = renderer.render(page.copy(elements = transformed))
        dirty = true
    }

    private fun resolvePlaceholders(element: Element): Element {
        var result = element
        if (dev.shadr.core.page.PlaceholderScanner.hasPlaceholder(element.text)) {
            val resolved = dev.shadr.core.page.PlaceholderScanner.apply(element.text, player, placeholders)
            lastResolved[element.id] = resolved
            result = result.copy(text = resolved)
        }
        for ((path, expression) in element.dynamic) {
            val value = evaluateDynamic(expression) ?: continue
            lastResolved["${element.id}#$path"] = value.toString()
            result = applyDynamic(result, path, value)
        }
        return result
    }

    private fun evaluateDynamic(expression: String): Double? {
        val substituted =
            dev.shadr.core.page.PlaceholderScanner.apply(expression, player, placeholders)
        if (dev.shadr.core.page.PlaceholderScanner.hasPlaceholder(substituted)) return null
        return dev.shadr.core.page.Expr.eval(substituted)
    }

    private fun applyDynamic(element: Element, path: String, value: Double): Element = when (path) {
        "size.width" -> element.copy(width = value)
        "size.height" -> element.copy(height = value)
        "opacity" -> element.copy(opacity = value.toInt().coerceIn(0, 255))
        else -> element
    }

    fun refreshPlaceholders(): Boolean {
        var changed = false
        for (element in page.elements) {
            if (dev.shadr.core.page.PlaceholderScanner.hasPlaceholder(element.text)) {
                val resolved =
                    dev.shadr.core.page.PlaceholderScanner.apply(element.text, player, placeholders)
                if (lastResolved[element.id] != resolved) changed = true
            }
            for ((path, expression) in element.dynamic) {
                val value = evaluateDynamic(expression) ?: continue
                if (lastResolved["${element.id}#$path"] != value.toString()) changed = true
            }
        }
        if (changed) rerender()
        return changed
    }

    val hasPlaceholders: Boolean
        get() = page.elements.any {
            dev.shadr.core.page.PlaceholderScanner.hasPlaceholder(it.text) || it.dynamic.isNotEmpty()
        }

    private val lastResolved = mutableMapOf<String, String>()

    init {
        rerender()
    }

    private fun applyEffect(element: Element, effectId: String?): Element {
        val effect = effectId?.let { effects[it.trim()] } ?: return element
        return effect.applyTo(element)
    }

    private fun cursorDraw(): HudDraw {
        val screen = page.screen
        val size = screen.cursorSize
        val cursorElement = Element(
            id = CURSOR_ELEMENT_ID,
            type = dev.shadr.core.page.ElementType.BLOCK,
            x = cursor.x,
            y = cursor.y,
            width = size,
            height = size,
            layer = screen.cursorLayer,
            unicode = screen.cursorUnicode.ifBlank { Glyphs.CURSOR.toString() },
        )
        return renderer.render(page.copy(elements = listOf(cursorElement))).draws.first()
    }

    companion object {
        const val CURSOR_ELEMENT_ID = "__shadr_cursor"
        const val HOVER_TEXT_ELEMENT_ID = "__shadr_hover_text"
        const val HOVER_TEXT_SIZE = 48.0
    }
}
