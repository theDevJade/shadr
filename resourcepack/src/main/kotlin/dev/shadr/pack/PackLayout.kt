/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

enum class PackOverlay(
    val directory: String,
    val minFormat: Int,
    val maxFormat: Int,
    val label: String,
    val reversedDepth: Boolean,
    val blurPanels: Boolean,
    val mapStream: Boolean,
    val itemProgram: String,
) {
    MC_26_2(
        "shadr_26_2", 88, 999, "26.2+",
        reversedDepth = true, blurPanels = true, mapStream = true, itemProgram = "core/item.fsh",
    ),
    MC_26_1(
        "shadr_26_1", 80, 87, "26.1",
        reversedDepth = false, blurPanels = true, mapStream = false, itemProgram = "core/item.fsh",
    ),
    MC_1_21_X(
        "shadr_1_21_x", 56, 79, "1.21.6 - 1.21.11",
        reversedDepth = false, blurPanels = true, mapStream = false,
        itemProgram = "core/rendertype_item_entity_translucent_cull.fsh",
    );

    val sourceDirectory: String get() = directory.replace("shadr_", "mc_")

    fun profileGlsl(): String = buildString {
        appendLine("#define SHADR_REVERSED_DEPTH ${if (reversedDepth) 1 else 0}")
        appendLine("#define SHADR_BLUR_PANELS ${if (blurPanels) 1 else 0}")
        appendLine("#define SHADR_MAP_STREAM ${if (mapStream) 1 else 0}")
    }

    companion object {
        const val BASE_PACK_FORMAT = 56

        const val SHARED_DIRECTORY = "_shared"

        const val PROFILE_INCLUDE = "shadr_profile.glsl"
    }
}
