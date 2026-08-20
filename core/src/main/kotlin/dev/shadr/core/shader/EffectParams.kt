/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.shader

import kotlinx.serialization.Serializable

@Serializable
enum class EffectParamType { FLOAT, COLOR, BOOL, ENUM }

@Serializable
data class EffectParam(
    val key: String,
    val label: String,
    val type: EffectParamType = EffectParamType.FLOAT,
    val default: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 0.01,
    val options: List<String> = emptyList(),
    val group: String = "",
) {
    fun clamp(raw: Double): Double = when (type) {
        EffectParamType.BOOL -> if (raw >= 0.5) 1.0 else 0.0
        EffectParamType.ENUM -> raw.toInt().coerceIn(0, (options.size - 1).coerceAtLeast(0)).toDouble()
        EffectParamType.COLOR -> raw.toLong().coerceIn(0L, 0xFFFFFFL).toDouble()
        EffectParamType.FLOAT -> raw.coerceIn(minOf(min, max), maxOf(min, max))
    }

    companion object {
        fun float(
            key: String,
            label: String,
            default: Double,
            min: Double,
            max: Double,
            step: Double = 0.01,
            group: String = "",
        ) = EffectParam(key, label, EffectParamType.FLOAT, default, min, max, step, group = group)

        fun color(key: String, label: String, default: Int, group: String = "") =
            EffectParam(key, label, EffectParamType.COLOR, default.toDouble(), group = group)

        fun bool(key: String, label: String, default: Boolean, group: String = "") =
            EffectParam(key, label, EffectParamType.BOOL, if (default) 1.0 else 0.0, group = group)

        fun enum(key: String, label: String, options: List<String>, default: Int = 0, group: String = "") =
            EffectParam(
                key, label, EffectParamType.ENUM, default.toDouble(),
                min = 0.0, max = (options.size - 1).toDouble(), step = 1.0,
                options = options, group = group,
            )
    }
}
