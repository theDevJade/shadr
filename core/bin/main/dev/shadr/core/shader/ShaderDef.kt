/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.shader

import dev.shadr.core.Rgb

data class ShaderDef(
    val id: String,

    val source: String,

    val index: Int = 0,

    val description: String = "",

    val previewTint: Rgb = Rgb.WHITE,
) {
    val itemModel: String get() = "minecraft:shadr/shader_$id"

    companion object {
        const val NAMESPACE = "shadr"

        const val ENTRY_POINT = "shadr_main"

        const val MAX_SHADERS = 200

        const val MARKER_R = 0x5D
        const val MARKER_G = 0x52

        const val MARKER_A = 0xFC

        private val VALID_ID = Regex("[a-z0-9_]{1,48}")

        fun validateId(id: String): String? =
            if (VALID_ID.matches(id)) null else "id must match ${VALID_ID.pattern}"
    }
}

class ShaderRegistry(defs: List<ShaderDef>) {

    val shaders: List<ShaderDef> = defs
        .sortedBy { it.id }
        .take(ShaderDef.MAX_SHADERS)
        .mapIndexed { index, def -> def.copy(index = index) }

    val dropped: List<String> = defs.sortedBy { it.id }.drop(ShaderDef.MAX_SHADERS).map { it.id }

    val conflicts: Map<String, List<String>> = shaders
        .flatMap { shader -> GlslSymbols.globals(shader.source).map { it to shader.id } }
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size > 1 }
        .toSortedMap()

    val conflicted: Set<String> = conflicts.values.flatMap { it.drop(1) }.toSet()

    val isEmpty: Boolean get() = shaders.isEmpty()

    operator fun get(id: String): ShaderDef? = shaders.firstOrNull { it.id == id }

    companion object {
        val EMPTY = ShaderRegistry(emptyList())
    }
}
