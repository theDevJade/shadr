/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.anim

import dev.shadr.core.page.AnimationStep
import dev.shadr.core.page.Element
import dev.shadr.core.page.GuiAnimationDef

object AnimationMath {
    fun sampleAt(step: AnimationStep, tick: Int, animationDuration: Int): Double {
        val duration = (if (step.durationTicks > 0) step.durationTicks else animationDuration - step.delayTicks)
            .coerceAtLeast(1)
        val local = (tick - step.delayTicks).coerceIn(0, duration)
        val progress = step.easing.apply(local.toDouble() / duration)

        if (step.values.size >= 2) {
            val scaled = progress * (step.values.size - 1)
            val index = scaled.toInt().coerceIn(0, step.values.size - 2)
            val within = scaled - index
            return step.values[index] + (step.values[index + 1] - step.values[index]) * within
        }
        return step.from + (step.to - step.from) * progress
    }

    fun apply(element: Element, animation: GuiAnimationDef, tick: Int): Element {
        var result = element
        for (step in animation.steps) {
            if (step.target != element.id) continue
            val value = sampleAt(step, tick, animation.durationTicks)
            result = applyAxis(result, step.axis, value)
        }
        return result
    }

    private fun applyAxis(element: Element, axis: String, value: Double): Element = when (axis.lowercase()) {
        "x" -> element.copy(x = value)
        "y" -> element.copy(y = value)
        "dx" -> element.copy(x = element.x + value)
        "dy" -> element.copy(y = element.y + value)
        "width" -> element.copy(width = value.coerceAtLeast(1.0))
        "height" -> element.copy(height = value.coerceAtLeast(1.0))
        "layer" -> element.copy(layer = value)
        "opacity" -> element.copy(opacity = value.toInt().coerceIn(0, 255))
        "rotation", "rotationdeg" -> element.copy(rotationDeg = value)
        else -> element
    }

    fun toClientInterpolation(step: AnimationStep, animationDuration: Int): Pair<Int, Int>? {
        if (step.values.size >= 2) return null
        if (step.axis.equals("opacity", ignoreCase = true)) return null
        val duration = if (step.durationTicks > 0) step.durationTicks else animationDuration - step.delayTicks
        if (duration <= 0) return null
        return step.delayTicks to duration
    }
}
