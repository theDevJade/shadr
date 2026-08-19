/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackOverlayTest {

    @Test
    fun `only 26_2 renders with a reversed depth buffer`() {
        assertEquals(
            setOf(PackOverlay.MC_26_2),
            PackOverlay.entries.filter { it.reversedDepth }.toSet(),
            "reversed-Z arrived in 26.2. Verify a new version by disassembling " +
                "com.mojang.blaze3d.pipeline.DepthStencilState from its client jar: DEFAULT holds " +
                "CompareOp.GREATER_THAN_OR_EQUAL when reversed and LESS_THAN_OR_EQUAL when not. " +
                "Getting this wrong puts the whole HUD behind the world or in front of nothing",
        )
    }

    @Test
    fun `only 26_2 claims map streaming`() {
        assertEquals(
            setOf(PackOverlay.MC_26_2),
            PackOverlay.entries.filter { it.mapStream }.toSet(),
            "map streaming needs shadr_stream.glsl and the codec post programs, which only 26.2 ships",
        )
    }

    @Test
    fun `every overlay that claims blur ships the whole blur chain`() {
        val shared = java.io.File("../shaders/overlays/${PackOverlay.SHARED_DIRECTORY}")
        val required = listOf(
            "include/shadr_post.glsl",
            "post/shadr_fullscreen.vsh",
            "post_effect/creeper.json",
        ) + listOf("mask", "dilate", "erode", "extract", "box", "composite", "blit")
            .map { "post/shadr_blur_$it.fsh" }

        for (overlay in PackOverlay.entries.filter { it.blurPanels }) {
            val missing = required.filterNot { part ->
                java.io.File(shared, part).isFile ||
                    java.io.File("../shaders/overlays/${overlay.sourceDirectory}/$part").isFile
            }
            assertEquals(
                emptyList(), missing,
                "${overlay.label} claims blur panels but neither it nor the shared base ships " +
                    "$missing, so panels paint the key colour with nothing to consume it",
            )
        }
    }

    @Test
    fun `the profile declares every flag the shared includes branch on`() {
        val shared = java.io.File("../shaders/overlays/${PackOverlay.SHARED_DIRECTORY}/include")
        val branched = shared.listFiles { f -> f.extension == "glsl" }
            .orEmpty()
            .flatMap { Regex("""#if\s+(SHADR_[A-Z_]+)""").findAll(it.readText()).map { m -> m.groupValues[1] } }
            .toSet()
        assertTrue(branched.isNotEmpty(), "the shared includes branch on no profile flag at all")

        for (overlay in PackOverlay.entries) {
            val declared = Regex("""#define\s+(SHADR_[A-Z_]+)""")
                .findAll(overlay.profileGlsl())
                .map { it.groupValues[1] }
                .toSet()
            assertEquals(
                emptySet(), branched - declared,
                "${overlay.label} branches on flags its profile never defines, so they silently " +
                    "evaluate as 0 and the feature turns itself off",
            )
        }
    }

    @Test
    fun `overlay format ranges are contiguous and do not overlap`() {
        val sorted = PackOverlay.entries.sortedBy { it.minFormat }
        for (overlay in sorted) {
            assertTrue(
                overlay.minFormat <= overlay.maxFormat,
                "${overlay.label} has an inverted format range",
            )
        }
        for ((lower, upper) in sorted.zipWithNext()) {
            assertEquals(
                lower.maxFormat + 1, upper.minFormat,
                "${lower.label} and ${upper.label} leave a pack_format gap or overlap, so some " +
                    "clients resolve to the wrong overlay or to none",
            )
        }
        assertEquals(
            sorted.first().minFormat, PackOverlay.BASE_PACK_FORMAT,
            "pack.mcmeta declares a base pack_format that no overlay claims",
        )
    }
}
