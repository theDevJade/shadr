/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.paper.nms.PacketBackends
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PacketBackendsTest {
    @Test
    fun `every shipped minecraft version maps to a backend`() {
        val expected = mapOf(
            "1.21.6" to "v1_21_8",
            "1.21.7" to "v1_21_8",
            "1.21.8" to "v1_21_8",
            "1.21.9" to "v1_21_11",
            "1.21.10" to "v1_21_11",
            "1.21.11" to "v1_21_11",
            "26.0" to "v26_1",
            "26.1" to "v26_1",
            "26.1.1" to "v26_1",
            "26.2" to "v26_2",
        )
        for ((version, module) in expected) {
            assertEquals(module, PacketBackends.moduleFor(version), "wrong backend for $version")
        }
    }

    @Test
    fun `an unreleased major fails loudly instead of loading the newest backend`() {
        assertNull(
            PacketBackends.moduleFor("26.3"),
            "26.3 silently reusing the 26.2 backend would send packets built against the wrong " +
                "protocol rather than falling back to display entities",
        )
        assertNull(PacketBackends.moduleFor("27.0"))
        assertNull(PacketBackends.moduleFor("1.20.6"))
    }

    @Test
    fun `versions below the 1_21_6 floor resolve to nothing`() {
        for (version in listOf("1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5")) {
            assertNull(
                PacketBackends.moduleFor(version),
                "$version is below the supported floor, so it must degrade to display entities " +
                    "rather than be handed a backend built for a different protocol",
            )
        }
    }

    @Test
    fun `a malformed version does not resolve`() {
        for (version in listOf("1.21.", "1.21.x", "", "1.21", "1.")) {
            assertNull(PacketBackends.moduleFor(version), "unexpected backend for '$version'")
        }
    }

    @Test
    fun `a patch release stays on its own family`() {
        assertEquals("v26_2", PacketBackends.moduleFor("26.2.1"))
        assertEquals("v26_1", PacketBackends.moduleFor("26.1.2"))
        assertEquals("v1_21_11", PacketBackends.moduleFor("1.21.12"))
        assertEquals("v1_21_8", PacketBackends.moduleFor("1.21.6.1"))
    }

    @Test
    fun `the plugin jar carries a class for every backend the mapping names`() {
        if (System.getProperty("shadr.allowMissingNms") == "true") return

        val jar = java.io.File("build/libs").listFiles()
            ?.firstOrNull { it.name.startsWith("shadr-paper") && it.extension == "jar" }
            ?: return

        val modules = JarFile(jar).use { archive ->
            archive.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith("/NmsPacketBackend.class") }
                .map { it.substringAfter("dev/shadr/paper/nms/").substringBefore('/') }
                .toSet()
        }
        assertTrue(
            modules.isNotEmpty(),
            "the plugin jar embeds no packet backends at all, so every server would fall back to " +
                "Bukkit display entities; build platform-paper-nms before packaging",
        )

        val named = setOf(
            "1.21.6", "1.21.8", "1.21.11", "26.1", "26.2",
        ).mapNotNull { PacketBackends.moduleFor(it) }.toSet()

        assertTrue(
            named.all { it in modules },
            "the plugin jar embeds $modules but the version mapping also names ${named - modules}, " +
                "so those servers would load nothing and fall back to display entities",
        )
    }
}
