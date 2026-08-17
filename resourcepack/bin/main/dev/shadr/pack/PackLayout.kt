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
) {
    MC_26_2("shadr_26_2", 88, 999, "26.2+"),
    MC_26_1("shadr_26_1", 80, 87, "26.1"),
    MC_1_21_X("shadr_1_21_x", 56, 79, "1.21.6 - 1.21.11"),
    MC_1_21_5("shadr_1_21_5", 55, 55, "1.21.5"),
    MC_1_21_4("shadr_1_21_4", 42, 46, "1.21.2 - 1.21.4"),
    MC_1_21_1("shadr_1_21_1", 32, 34, "1.21 - 1.21.1");

    val sourceDirectory: String get() = directory.replace("shadr_", "mc_")

    companion object {
        const val BASE_PACK_FORMAT = 55

        fun forVersion(mc: String): PackOverlay {
            val v = mc.trim()
            return when {
                v.startsWith("26.2") || v.startsWith("26.3") -> MC_26_2
                v.startsWith("26.1") || v.startsWith("26.0") -> MC_26_1
                v.startsWith("1.21.10") || v.startsWith("1.21.11") -> MC_1_21_X
                v.startsWith("1.21.6") || v.startsWith("1.21.7") ||
                    v.startsWith("1.21.8") || v.startsWith("1.21.9") -> MC_1_21_X
                v.startsWith("1.21.5") -> MC_1_21_5
                v.startsWith("1.21.2") || v.startsWith("1.21.3") || v.startsWith("1.21.4") -> MC_1_21_4
                v.startsWith("1.21.1") || v.startsWith("1.21") -> MC_1_21_1
                v.startsWith("1.") -> MC_1_21_1
                else -> MC_26_2
            }
        }

        fun forPackFormat(format: Int): PackOverlay =
            entries.firstOrNull { format in it.minFormat..it.maxFormat } ?: MC_26_2
    }
}
