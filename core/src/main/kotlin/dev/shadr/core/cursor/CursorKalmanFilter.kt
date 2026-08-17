/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.cursor

/**
 * Essentially a predictive filter for cursor movement.
 */
class CursorKalmanFilter {
    private var position = 0.0
    private var velocity = 0.0
    private var covariance00 = 1.0
    private var covariance01 = 0.0
    private var covariance10 = 0.0
    private var covariance11 = 1.0
    private var iterations = 0

    /** @param dt elapsed time in ticks since the previous sample. */
    fun update(measurement: Double, dt: Double) {
        if (!measurement.isFinite()) return
        if (iterations++ == 0) {
            position = measurement
            velocity = 0.0
            return
        }

        val step = dt.coerceIn(MIN_DT, MAX_DT)
        position += velocity * step

        val half = 0.5 * step * step
        val p00 = covariance00 + step * covariance01 + step * covariance10 +
            step * step * covariance11 + PROCESS_NOISE * half * half
        val p01 = covariance01 + step * covariance11 + PROCESS_NOISE * half * step
        val p10 = covariance10 + step * covariance11 + PROCESS_NOISE * step * half
        val p11 = covariance11 + PROCESS_NOISE * step * step

        val innovation = measurement - position
        val innovationCovariance = p00 + MEASUREMENT_NOISE
        if (!innovationCovariance.isFinite() || innovationCovariance <= 0.0) {
            reset()
            position = measurement
            iterations = 1
            return
        }

        val gainPosition = p00 / innovationCovariance
        val gainVelocity = p10 / innovationCovariance
        position += gainPosition * innovation
        velocity += gainVelocity * innovation

        // Joseph form
        val complement = 1.0 - gainPosition
        covariance00 = complement * complement * p00 + gainPosition * gainPosition * MEASUREMENT_NOISE
        covariance01 = complement * (-gainVelocity * p00 + p01) + gainPosition * gainVelocity * MEASUREMENT_NOISE
        covariance10 = complement * (-gainVelocity * p00 + p10) + gainVelocity * gainPosition * MEASUREMENT_NOISE
        covariance11 = gainVelocity * gainVelocity * p00 - gainVelocity * (p01 + p10) + p11 +
            gainVelocity * gainVelocity * MEASUREMENT_NOISE

        val symmetric = (covariance01 + covariance10) * 0.5
        covariance01 = symmetric
        covariance10 = symmetric
    }

    /** Sample first, use later */
    val isStable: Boolean get() = iterations >= STABLE_ITERATIONS

    val estimatedVelocity: Double get() = if (velocity.isFinite()) velocity else 0.0

    val estimatedPosition: Double get() = if (position.isFinite()) position else 0.0

    fun reset() {
        position = 0.0
        velocity = 0.0
        covariance00 = 1.0
        covariance01 = 0.0
        covariance10 = 0.0
        covariance11 = 1.0
        iterations = 0
    }

    private companion object {
        const val PROCESS_NOISE = 4.0
        const val MEASUREMENT_NOISE = 1.0
        const val STABLE_ITERATIONS = 4
        const val MIN_DT = 0.5
        const val MAX_DT = 2.0
    }
}
