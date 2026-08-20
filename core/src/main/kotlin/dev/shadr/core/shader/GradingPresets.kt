/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.shader

import java.io.File
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

object GradingPresets {

    const val DIRECTORY = "grading"

    val BUILT_IN: Map<String, Map<String, Double>> = linkedMapOf(
        "neutral" to EnvironmentEffect.GRADING.defaults(),
        "cinematic" to mapOf(
            "exposure" to 0.1,
            "contrast" to 1.12,
            "saturation" to 0.9,
            "temperature" to -6.0,
            "tonemap" to 2.0,
            "shadows" to 0x7C8290.toDouble(),
            "midtones" to 0x808080.toDouble(),
            "highlights" to 0x8A8478.toDouble(),
            "vignette" to 0.28,
            "grain" to 0.06,
        ),
        "warm" to mapOf(
            "exposure" to 0.05,
            "contrast" to 1.04,
            "saturation" to 1.08,
            "temperature" to 22.0,
            "tint" to 4.0,
            "tonemap" to 2.0,
            "highlights" to 0x8A8478.toDouble(),
        ),
        "cold" to mapOf(
            "exposure" to -0.05,
            "contrast" to 1.06,
            "saturation" to 0.94,
            "temperature" to -24.0,
            "tonemap" to 2.0,
            "shadows" to 0x78808E.toDouble(),
        ),
        "noir" to mapOf(
            "contrast" to 1.35,
            "saturation" to 0.0,
            "tonemap" to 4.0,
            "vignette" to 0.45,
            "grain" to 0.18,
            "sharpen" to 0.2,
        ),
        "vibrant" to mapOf(
            "exposure" to 0.08,
            "contrast" to 1.1,
            "saturation" to 1.2,
            "vibrance" to 0.25,
            "tonemap" to 2.0,
        ),
    )

    fun load(shaderRoot: File?): Map<String, Map<String, Double>> {
        val dir = shaderRoot?.let { File(it, DIRECTORY) }?.takeIf { it.isDirectory }
            ?: return BUILT_IN
        val out = linkedMapOf<String, Map<String, Double>>()
        out += BUILT_IN
        dir.listFiles { f -> f.isFile && (f.extension == "yml" || f.extension == "yaml") }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                readPreset(file)?.let { out[file.nameWithoutExtension] = it }
            }
        return out
    }

    private fun readPreset(file: File): Map<String, Double>? {
        val raw = runCatching {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(file.readText())
        }.getOrNull() as? Map<*, *> ?: return null

        val params = EnvironmentEffect.GRADING.params.associateBy { it.key }
        val out = linkedMapOf<String, Double>()
        for ((key, value) in raw) {
            val name = key?.toString()?.trim() ?: continue
            val param = params[name] ?: params[camel(name)] ?: continue
            valueOf(param, value)?.let { out[param.key] = it }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun valueOf(param: EffectParam, raw: Any?): Double? = when (param.type) {
        EffectParamType.COLOR -> dev.shadr.core.Rgb.parse(raw?.toString())?.packed?.toDouble()
        EffectParamType.BOOL -> when (raw) {
            is Boolean -> if (raw) 1.0 else 0.0
            else -> raw?.toString()?.toBooleanStrictOrNull()?.let { if (it) 1.0 else 0.0 }
        }
        EffectParamType.ENUM -> when (raw) {
            is Number -> raw.toDouble()
            else -> param.options.indexOfFirst { it.equals(raw?.toString()?.trim(), true) }
                .takeIf { it >= 0 }?.toDouble()
        }
        EffectParamType.FLOAT -> when (raw) {
            is Number -> raw.toDouble()
            else -> dev.shadr.core.page.Expr.eval(raw?.toString().orEmpty(), emptyMap())
        }
    }?.let { param.clamp(it) }

    private fun camel(kebab: String): String =
        kebab.split('-').mapIndexed { index, part ->
            if (index == 0) part else part.replaceFirstChar { it.uppercase() }
        }.joinToString("")
}
