/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.editor

import dev.shadr.core.HudAlignment
import dev.shadr.core.Interpolation
import dev.shadr.core.Rgb
import dev.shadr.core.RoundingSize
import dev.shadr.core.TextAlignment
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Outline
import dev.shadr.core.anim.AnimationMath
import dev.shadr.core.page.AnimationStep
import dev.shadr.core.page.GuiAnimationDef
import dev.shadr.core.page.Page
import dev.shadr.core.page.Rounding

class EditorSession(
    initial: Page,
    /** Matches `editor.history.undo-limit` in the config. */
    private val undoLimit: Int = 50,
) {

    var page: Page = initial
        private set

    var original: Page = initial
        private set

    private var counter = 0

    var onChanged: ((Page) -> Unit)? = null

    private val undoStack = ArrayDeque<Page>()
    private val redoStack = ArrayDeque<Page>()

    private var lastGesture: String? = null

    var previewTick: Int? = null
        private set

    val rendered: Page
        get() {
            val tick = previewTick ?: return page
            val animation = page.animations.firstOrNull() ?: return page
            return page.copy(
                elements = page.elements.map { AnimationMath.apply(it, animation, tick) },
            )
        }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val isDirty: Boolean get() = page != original

    fun snapshot(
        issues: List<String> = emptyList(),
        kind: DocumentKind = DocumentKind.PAGE,
    ): PageSnapshot = rendered.let { frame ->
        PageSnapshot(
        name = frame.name,
        screen = frame.screen,
        elements = frame.elements,
        issues = issues,
        locked = original.elements.map { it.id }.toSet().let { known ->
            frame.elements.mapNotNull { element ->
                if (element.id !in known) return@mapNotNull null
                PageWriter.unaddressableReason(element)?.let { element.id to it }
            }.toMap()
        },
        canUndo = canUndo,
        canRedo = canRedo,
        dirty = isDirty,
        animations = page.animations,
        previewTick = previewTick,
        kind = kind,
        )
    }

    fun reset(next: Page) {
        page = next
        original = next
        undoStack.clear()
        redoStack.clear()
        lastGesture = null
        notifyChanged()
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(page)
        page = previous
        lastGesture = null
        notifyChanged()
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(page)
        page = next
        lastGesture = null
        notifyChanged()
        return true
    }

    private fun checkpoint(gesture: String?) {
        val continues = gesture != null && gesture == lastGesture && undoStack.isNotEmpty()
        if (!continues) {
            undoStack.addLast(page)
            if (undoStack.size > undoLimit) undoStack.removeFirst()
        }
        redoStack.clear()
        lastGesture = gesture
    }

    fun markSaved(assignedPaths: Map<String, String> = emptyMap()) {
        if (assignedPaths.isNotEmpty()) {
            page = page.copy(
                elements = page.elements.map { element ->
                    assignedPaths[element.id]?.let { element.copy(sourcePath = it) } ?: element
                },
            )
        }
        original = page
    }

    fun patch(elementId: String, changes: Map<String, String>, gesture: String? = null): Boolean =
        patchAll(mapOf(elementId to changes), gesture)

    fun patchAll(edits: Map<String, Map<String, String>>, gesture: String? = null): Boolean {
        if (previewTick != null) return false
        val elements = page.elements.toMutableList()
        var changed = false

        for ((elementId, changes) in edits) {
            val index = elements.indexOfFirst { it.id == elementId }
            if (index < 0) continue
            var element = elements[index]
            for ((path, raw) in changes) element = applyChange(element, path, raw)
            if (element != elements[index]) {
                elements[index] = element
                changed = true
            }
        }
        if (!changed) return false

        checkpoint(gesture)
        page = page.copy(elements = elements)
        notifyChanged()
        return true
    }

    fun add(type: String, x: Double, y: Double, width: Double, height: Double): Element {
        val elementType = ElementType.parse(type)
        val element = Element(
            id = "el_${elementType.id}_${++counter}",
            type = elementType,
            x = x,
            y = y,
            width = width,
            height = height,
            layer = if (elementType == ElementType.BLUR) {
                dev.shadr.core.hud.HudPositionCalculator.BLUR_PANEL_LAYER
            } else {
                (page.elements.filterNot { it.type == ElementType.BLUR }.maxOfOrNull { it.layer } ?: 0.0) + 1.0
            },
            rounding = if (elementType.roundedByDefault) Rounding(size = RoundingSize.REGULAR) else null,
        )
        checkpoint(null)
        page = page.copy(elements = page.elements + element)
        notifyChanged()
        return element
    }

    fun delete(elementIds: Collection<String>): Boolean {
        if (previewTick != null) return false
        val remaining = page.elements.filterNot { it.id in elementIds }
        if (remaining.size == page.elements.size) return false
        checkpoint(null)
        page = page.copy(elements = remaining)
        notifyChanged()
        return true
    }

    fun scrub(tick: Int?) {
        previewTick = tick
        notifyChanged()
    }

    fun setStep(
        animation: String,
        target: String,
        axis: String,
        from: Double,
        to: Double,
        duration: Int,
        easing: Interpolation = Interpolation.LINEAR,
    ): Boolean {
        checkpoint(null)
        val existing = page.animations.firstOrNull { it.name == animation }
            ?: GuiAnimationDef(name = animation, durationTicks = duration)

        val steps = existing.steps.filterNot { it.target == target && it.axis == axis } +
            AnimationStep(
                target = target,
                axis = axis,
                easing = easing,
                from = from,
                to = to,
                durationTicks = duration,
            )

        val updated = existing.copy(
            steps = steps,
            durationTicks = maxOf(existing.durationTicks, duration),
        )
        page = page.copy(
            animations = page.animations.filterNot { it.name == animation } + updated,
        )
        notifyChanged()
        return true
    }

    fun removeStep(animation: String, target: String, axis: String): Boolean {
        val existing = page.animations.firstOrNull { it.name == animation } ?: return false
        val steps = existing.steps.filterNot { it.target == target && it.axis == axis }
        if (steps.size == existing.steps.size) return false

        checkpoint(null)
        page = page.copy(
            animations = page.animations.filterNot { it.name == animation } +
                existing.copy(steps = steps),
        )
        notifyChanged()
        return true
    }

    private fun notifyChanged() = onChanged?.invoke(rendered)

    private fun applyChange(element: Element, path: String, raw: String): Element {
        val number = raw.trim().toDoubleOrNull()
        return when (path) {
            "position.x", "x" -> number?.let { element.copy(x = it) } ?: element
            "position.y", "y" -> number?.let { element.copy(y = it) } ?: element
            "size.width", "width" -> number?.let { element.copy(width = it.coerceAtLeast(1.0)) } ?: element
            "size.height", "height" -> number?.let { element.copy(height = it.coerceAtLeast(1.0)) } ?: element
            "layer" -> number?.let { element.copy(layer = it) } ?: element
            "opacity" -> number?.let { element.copy(opacity = it.toInt().coerceIn(0, 255)) } ?: element
            "rotationDeg" -> number?.let { element.copy(rotationDeg = it) } ?: element
            "color" -> Rgb.parse(raw)?.let { element.copy(color = it) } ?: element
            "text" -> element.copy(text = raw)
            "unicode" -> element.copy(unicode = raw)
            "font" -> element.copy(font = raw)
            "id" -> if (raw.isBlank() || page.elements.any { it.id == raw }) element else element.copy(id = raw)
            "type" -> element.copy(type = ElementType.parse(raw))
            "enabled" -> element.copy(enabled = raw.toBooleanStrictOrNull() ?: element.enabled)
            "align" -> parseHudAlignment(raw)?.let { element.copy(hudAlignment = it) } ?: element
            "textAlign" -> parseTextAlignment(raw)?.let { element.copy(textAlignment = it) } ?: element
            "rounding.size" -> element.copy(
                rounding = (element.rounding ?: Rounding()).copy(size = RoundingSize.parse(raw)),
            )
            "rounding.radius" -> element.copy(
                rounding = (element.rounding ?: Rounding()).copy(radius = number),
            )
            "outline.size" -> element.copy(
                outline = number?.let { size ->
                    if (size <= 0.0) null else (element.outline ?: Outline(size, Rgb.WHITE)).copy(size = size)
                } ?: element.outline,
            )
            "outline.color" -> element.copy(
                outline = Rgb.parse(raw)?.let { (element.outline ?: Outline(1.0, it)).copy(color = it) }
                    ?: element.outline,
            )
            else -> element
        }
    }

    private fun parseHudAlignment(raw: String) = when (raw.trim().lowercase()) {
        "left" -> HudAlignment.LEFT
        "right" -> HudAlignment.RIGHT
        "center", "centre", "middle" -> HudAlignment.CENTER
        else -> null
    }

    private fun parseTextAlignment(raw: String) = when (raw.trim().lowercase()) {
        "left" -> TextAlignment.LEFT
        "right" -> TextAlignment.RIGHT
        "center", "centre", "middle" -> TextAlignment.CENTER
        else -> null
    }
}
