/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack

/**
 * The pack ships as **one** pack with `overlays`, not one pack per client version.
 *
 * A server has a mixed-version player base and can only send one URL and one SHA-1. So
 * every supported client gets the same file, and the client itself selects the overlay
 * directory whose `formats` range covers its own `pack_format`. Version-specific content
 * is exactly the core shader overrides; fonts, textures and sounds are shared at the root.
 *
 * Ranges are contiguous and open-ended at the top, so a client newer than anything we
 * know about still lands on the newest overlay rather than silently losing its shaders.
 */
enum class PackOverlay(
    /** Directory inside the pack, and the source directory under `shaders/overlays/`. */
    val directory: String,
    val minFormat: Int,
    val maxFormat: Int,
    val label: String,
) {
    // Newest first, so a future client falls into MC_26_2's open top end.
    MC_26_2("shadr_26_2", 88, 999, "26.2+"),
    MC_26_1("shadr_26_1", 80, 87, "26.1"),
    MC_1_21_X("shadr_1_21_x", 56, 79, "1.21.6 - 1.21.11"),
    MC_1_21_5("shadr_1_21_5", 55, 55, "1.21.5"),
    MC_1_21_4("shadr_1_21_4", 42, 46, "1.21.2 - 1.21.4"),
    MC_1_21_1("shadr_1_21_1", 32, 34, "1.21 - 1.21.1");

    /** Source directory name under the repo's `shaders/overlays/`. */
    val sourceDirectory: String get() = directory.replace("shadr_", "mc_")

    companion object {
        /** Base `pack_format` written to `pack.mcmeta`. */
        const val BASE_PACK_FORMAT = 55

        /**
         * Which overlay a client on [mc] will use. Informational only, since the client does
         * the real selection from `pack_format`, but the server needs it for logging and
         * for the Bedrock/preview paths.
         *
         * Ordering matters: the longest, most specific prefix has to be tested first, or
         * `1.21.1` swallows `1.21.10` and hands it the wrong shaders.
         */
        fun forVersion(mc: String): PackOverlay {
            val v = mc.trim()
            return when {
                v.startsWith("26.2") || v.startsWith("26.3") -> MC_26_2
                v.startsWith("26.1") || v.startsWith("26.0") -> MC_26_1
                // 1.21.10 / 1.21.11 must be matched before the bare "1.21.1" prefix.
                v.startsWith("1.21.10") || v.startsWith("1.21.11") -> MC_1_21_X
                v.startsWith("1.21.6") || v.startsWith("1.21.7") ||
                    v.startsWith("1.21.8") || v.startsWith("1.21.9") -> MC_1_21_X
                v.startsWith("1.21.5") -> MC_1_21_5
                v.startsWith("1.21.2") || v.startsWith("1.21.3") || v.startsWith("1.21.4") -> MC_1_21_4
                v.startsWith("1.21.1") || v.startsWith("1.21") -> MC_1_21_1
                v.startsWith("1.") -> MC_1_21_1 // older than the floor; best effort
                else -> MC_26_2                 // date-versioned / unknown: newest
            }
        }

        /** `pack_format` -> overlay, mirroring what the client itself will pick. */
        fun forPackFormat(format: Int): PackOverlay =
            entries.firstOrNull { format in it.minFormat..it.maxFormat } ?: MC_26_2
    }
}
