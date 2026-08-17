/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class BundledAssetsTest {
    private val resources = File("build/resources/main").canonicalFile

    private fun index(): List<String>? =
        File(resources, BundledAssets.INDEX).takeIf { it.isFile }
            ?.readLines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }

    @Test
    fun `every indexed group has somewhere to be written`() {
        val entries = index() ?: return
        val groups = entries.map { it.substringBefore('/') }.toSet()
        val unmapped = groups - BundledAssets.TARGETS.keys
        assertTrue(
            unmapped.isEmpty(),
            "the build bundles these groups but ShadrPlugin has nowhere to put them, so they " +
                "are silently never extracted: $unmapped",
        )
    }

    @Test
    fun `every indexed file is actually in the jar`() {
        val entries = index() ?: return
        val missing = entries.filterNot { File(resources, "bundled/$it").isFile }
        assertTrue(
            missing.isEmpty(),
            "the index names files the build did not copy: $missing",
        )
    }

    @Test
    fun `the seed carries a usable starter library`() {
        val entries = index() ?: return
        for ((group, minimum) in listOf(
            "pages" to 3,
            "components" to 8,
            "effects" to 4,
            "shaders" to 3,
            "font" to 2,
            "sounds" to 3,
        )) {
            val count = entries.count { it.startsWith("$group/") }
            assertTrue(
                count >= minimum,
                "only $count bundled $group; a dropped-in jar would start almost empty",
            )
        }
    }

    @Test
    fun `every shader overlay the pack declares is bundled`() {
        val entries = index() ?: return
        val overlays = entries
            .filter { it.startsWith("shaders/overlays/") }
            .map { it.removePrefix("shaders/overlays/").substringBefore('/') }
            .toSet()

        for (version in listOf("mc_26_2", "mc_26_1", "mc_1_21_x", "mc_1_21_5", "mc_1_21_4", "mc_1_21_1")) {
            assertTrue(
                version in overlays,
                "shaders/overlays/$version is not bundled, so a dropped-in jar cannot build a " +
                    "pack for those clients. Bundled: $overlays",
            )
        }
        assertTrue(
            entries.any { it == "shaders/environment.properties" },
            "environment.properties is not bundled, so world overrides default differently in " +
                "a shipped jar than in the repo",
        )
    }

    @Test
    fun `every target directory is one the plugin loads from`() {
        val source = File("src/main/kotlin/dev/shadr/paper/ShadrPlugin.kt").readText()
        for (target in BundledAssets.TARGETS.values) {
            val root = target.substringBefore('/')
            assertTrue(
                source.contains("\"$root\""),
                "seeded files land in '$target', but ShadrPlugin never reads '$root'",
            )
        }
    }
}
