/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.cursor

import dev.shadr.core.ScreenPos

class LookMapper(
    private val screenWidth: Double = 1920.0,
    private val screenHeight: Double = 1080.0,
    private val speed: Double = 1.0,
) {
    private var lastYaw = Float.NaN
    private var x = screenWidth / 2.0
    private var y = screenHeight / 2.0

    var deltaX: Double = 0.0
        private set
    var deltaY: Double = 0.0
        private set

    fun reset(atX: Double = screenWidth / 2.0, atY: Double = screenHeight / 2.0, yaw: Float = Float.NaN) {
        x = atX
        y = atY
        lastYaw = yaw
        deltaX = 0.0
        deltaY = 0.0
    }

    fun sample(yaw: Float, pitch: Float, sensitivity: Double = 1.0): ScreenPos {
        val effectiveSpeed = speed * sensitivity.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)

        if (lastYaw.isNaN()) lastYaw = yaw
        var yawDelta = yaw - lastYaw
        lastYaw = yaw
        if (yawDelta > 180f) yawDelta -= 360f else if (yawDelta < -180f) yawDelta += 360f

        val previousX = x
        val previousY = y
        if (kotlin.math.abs(yawDelta) > YAW_EPSILON) {
            x += yawDelta / 360.0 * screenWidth * effectiveSpeed
        }
        y = (pitch + 90.0) / 180.0 * screenHeight

        x = x.coerceIn(0.0, screenWidth)
        y = y.coerceIn(0.0, screenHeight)
        deltaX = x - previousX
        deltaY = y - previousY
        return ScreenPos(x, y)
    }

    private companion object {
        const val YAW_EPSILON = 0.01
        const val MIN_SENSITIVITY = 0.1
        const val MAX_SENSITIVITY = 4.0
    }
}
