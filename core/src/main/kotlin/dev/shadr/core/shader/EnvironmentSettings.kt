/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.shader

import java.io.File

enum class EnvironmentEffect(
    val id: String,
    val title: String,
    val description: String,
    val programs: List<String>,
) {
    SKY(
        id = "sky",
        title = "Atmospheric sky",
        description = "My version of the sky.",
        programs = listOf("core/sky.vsh", "core/sky.fsh"),
    ),

    CLOUDS(
        id = "clouds",
        title = "Volumetric clouds",
        description = "This can look very botched.",
        programs = listOf("core/rendertype_clouds.vsh", "core/rendertype_clouds.fsh"),
    ),

    CELESTIALS(
        id = "celestials",
        title = "Procedural sun and moon",
        description = "Sketchy procedural generation instead of ew square.",
        programs = listOf("core/position_tex.fsh"),
    ),

    FROSTED_GLASS(
        id = "blur",
        title = "Frosted glass panels",
        description = "Apple-maxxing",
        programs = listOf(
            "post_effect/invert.json",
            "post/shadr_fullscreen.vsh",
            "post/shadr_blur_extract.fsh",
            "post/shadr_blur_box.fsh",
            "post/shadr_blur_composite.fsh",
            "post/shadr_blur_blit.fsh",
        ),
    ),
    ;

    companion object {
        fun parse(raw: String?): EnvironmentEffect? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

class EnvironmentSettings(private val file: File) {

    private val enabled = java.util.concurrent.ConcurrentHashMap<EnvironmentEffect, Boolean>()

    init {
        load()
    }

    operator fun get(effect: EnvironmentEffect): Boolean = enabled[effect] ?: false

    fun isEnabled(effect: EnvironmentEffect): Boolean = get(effect)

    fun set(effect: EnvironmentEffect, value: Boolean) {
        enabled[effect] = value
        save()
    }

    fun all(): Map<EnvironmentEffect, Boolean> =
        EnvironmentEffect.entries.associateWith { get(it) }

    private fun load() {
        enabled.clear()
        if (!file.isFile) return
        for (line in file.readLines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val effect = EnvironmentEffect.parse(trimmed.substringBefore('=')) ?: continue
            enabled[effect] = trimmed.substringAfter('=', "false").trim().toBooleanStrictOrNull() ?: false
        }
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(
            buildString {
                appendLine("# Which vanilla programs shadr overrides. Written by the editor.")
                for (effect in EnvironmentEffect.entries) {
                    appendLine("${effect.id}=${get(effect)}")
                }
            },
        )
    }
}
