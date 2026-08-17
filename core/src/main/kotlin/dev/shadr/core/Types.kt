/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import kotlinx.serialization.Serializable

@Serializable
data class PlayerId(val uuid: String)

@Serializable
data class ScreenPos(val x: Double, val y: Double)

@Serializable
data class Vec2(val x: Double, val y: Double)

@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
}

@Serializable
data class Rgb(val packed: Int) {
    val r: Int get() = (packed ushr 16) and 0xFF
    val g: Int get() = (packed ushr 8) and 0xFF
    val b: Int get() = packed and 0xFF

    fun hex(): String = String.format("%06x", packed and 0xFFFFFF)

    companion object {
        val WHITE = Rgb(0xFFFFFF)
        val HITBOX_DEBUG = Rgb(0xFF0000)

        fun parse(raw: String?): Rgb? {
            val s = raw?.trim()?.removePrefix("#")?.removePrefix("0x")?.removePrefix("0X") ?: return null
            if (s.length != 6 || s.any { it.digitToIntOrNull(16) == null }) return null
            return Rgb(s.toInt(16))
        }
    }
}

@Serializable
enum class HudAlignment { LEFT, CENTER, RIGHT }

@Serializable
enum class TextAlignment { CENTER, LEFT, RIGHT }

@Serializable
enum class RoundingSize(val id: String) {
    NONE("none"), SMALL("small"), MEDIUM("medium"), REGULAR("regular"), LARGE("large");

    companion object {
        fun parse(raw: String?): RoundingSize =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

@Serializable
enum class Interpolation(val id: String) {
    LINEAR("linear"),
    EASE_IN("ease-in"),
    EASE_OUT("ease-out"),
    EASE_IN_OUT("ease-in-out"),
    SMOOTH_IN("smooth-in"),
    SMOOTH_OUT("smooth-out"),
    SMOOTH("smooth");

    fun apply(t: Double): Double {
        val x = t.coerceIn(0.0, 1.0)
        return when (this) {
            LINEAR -> x
            EASE_IN, SMOOTH_IN -> x * x
            EASE_OUT, SMOOTH_OUT -> 1.0 - (1.0 - x) * (1.0 - x)
            EASE_IN_OUT, SMOOTH -> if (x < 0.5) 2.0 * x * x else 1.0 - ((-2.0 * x + 2.0) * (-2.0 * x + 2.0)) / 2.0
        }
    }

    companion object {
        fun parse(raw: String?): Interpolation =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: LINEAR
    }
}
