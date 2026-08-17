/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.cursor

import dev.shadr.core.ScreenPos
import kotlin.math.abs

/**
 * Ping compensation lookahead.
 */
class CursorPredictor {
    private val xFilter = CursorKalmanFilter()
    private val yFilter = CursorKalmanFilter()
    private var smoothedPingMillis = Double.NaN
    private var lastSampleNanos = 0L
    private var lastPredictionX = Double.NaN
    private var lastPredictionY = Double.NaN

    /**
     * @param rawX / [rawY] the cursor position implied by the player's current look angles.
     * @param deltaX / [deltaY] this tick's movement, used only to detect reversals.
     * @param pingMillis the platform's current round-trip estimate for this player.
     */
    fun predict(
        rawX: Double,
        rawY: Double,
        deltaX: Double,
        deltaY: Double,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
        screenWidth: Double,
        screenHeight: Double,
        pingMillis: Int,
        nowNanos: Long = System.nanoTime(),
    ): ScreenPos {
        val dt = elapsedTicks(nowNanos)
        xFilter.update(rawX, dt)
        yFilter.update(rawY, dt)

        val ping = pingMillis.toDouble().coerceIn(0.0, MAX_TRACKED_PING_MILLIS)
        smoothedPingMillis = if (smoothedPingMillis.isFinite()) {
            smoothedPingMillis + (ping - smoothedPingMillis) * PING_SMOOTHING
        } else {
            ping
        }

        val lookaheadTicks = (smoothedPingMillis / TICK_MILLIS).coerceIn(0.0, MAX_LOOKAHEAD_TICKS)
        val maxOffsetX = (screenWidth * MAX_OFFSET_RATIO).coerceAtLeast(0.0)
        val maxOffsetY = (screenHeight * MAX_OFFSET_RATIO).coerceAtLeast(0.0)
        val stable = xFilter.isStable && yFilter.isStable

        val offsetX = if (stable && abs(deltaX) > MOVEMENT_EPSILON) {
            (xFilter.estimatedVelocity * lookaheadTicks).coerceIn(-maxOffsetX, maxOffsetX)
        } else 0.0
        val offsetY = if (stable && abs(deltaY) > MOVEMENT_EPSILON) {
            (yFilter.estimatedVelocity * lookaheadTicks).coerceIn(-maxOffsetY, maxOffsetY)
        } else 0.0

        var x = (rawX + offsetX).coerceIn(minX, maxX)
        var y = (rawY + offsetY).coerceIn(minY, maxY)
        x = applyDirectionCutoff(x, lastPredictionX, deltaX).coerceIn(minX, maxX)
        y = applyDirectionCutoff(y, lastPredictionY, deltaY).coerceIn(minY, maxY)

        lastPredictionX = x
        lastPredictionY = y
        return ScreenPos(x, y)
    }

    fun reset() {
        xFilter.reset()
        yFilter.reset()
        smoothedPingMillis = Double.NaN
        lastSampleNanos = 0L
        lastPredictionX = Double.NaN
        lastPredictionY = Double.NaN
    }

    private fun elapsedTicks(nowNanos: Long): Double {
        if (lastSampleNanos == 0L) {
            lastSampleNanos = nowNanos
            return 1.0
        }
        val ticks = (nowNanos - lastSampleNanos) / NANOS_PER_TICK
        lastSampleNanos = nowNanos
        // This indicates a dirty result.
        if (!ticks.isFinite() || ticks <= 0.0 || ticks > MAX_SAMPLE_GAP_TICKS) {
            xFilter.reset()
            yFilter.reset()
            return 1.0
        }
        return ticks.coerceIn(0.5, 2.0)
    }

    /** Prevents the predicted cursor from moving against the player's input direction. */
    private fun applyDirectionCutoff(predicted: Double, previous: Double, delta: Double): Double {
        if (!previous.isFinite() || !delta.isFinite() || abs(delta) <= MOVEMENT_EPSILON) return predicted
        val movement = predicted - previous
        return if (movement * delta < 0.0) previous else predicted
    }

    private companion object {
        const val TICK_MILLIS = 50.0
        const val NANOS_PER_TICK = 5.0e7
        const val MAX_LOOKAHEAD_TICKS = 4.0
        const val MAX_OFFSET_RATIO = 0.1
        const val PING_SMOOTHING = 0.2
        const val MAX_SAMPLE_GAP_TICKS = 3.0
        const val MAX_TRACKED_PING_MILLIS = 200.0
        const val MOVEMENT_EPSILON = 0.005
    }
}
