/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertTrue

class PluginJarTest {
    private fun jar(): File? =
        File("build/libs").listFiles { f -> f.extension == "jar" && !f.name.contains("sources") }
            ?.maxByOrNull { it.lastModified() }

    private fun entries(): Set<String>? {
        val file = jar() ?: return null
        return JarFile(file).use { archive ->
            archive.entries().asSequence().map { it.name }.toSet()
        }
    }

    @Test
    fun `the built jar name is the one the updater looks for in a release`() {
        val file = jar() ?: return
        assertTrue(
            dev.shadr.core.update.UpdateChecker.DEFAULT_ASSET_PATTERN.matches(file.name),
            "the plugin jar is built as '${file.name}', which the updater's asset pattern " +
                "(${dev.shadr.core.update.UpdateChecker.DEFAULT_ASSET_PATTERN}) does not match, so a " +
                "published release would look to every server like a release with no plugin in it",
        )
    }

    private val required = mapOf(
        "Kotlin stdlib" to "kotlin/NoWhenBranchMatchedException.class",
        "shadr core" to "dev/shadr/core/hud/PageRenderer.class",
        "shadr resourcepack" to "dev/shadr/pack/PackGenerator.class",
        "shadr paper adapter" to "dev/shadr/paper/ShadrPlugin.class",
        "snakeyaml" to "org/yaml/snakeyaml/Yaml.class",
        "kotlinx-serialization" to "kotlinx/serialization/json/Json.class",
    )

    @Test
    fun `the plugin jar carries everything its classloader will need`() {
        val entries = entries() ?: return
        val missing = required.filterValues { it !in entries }
        assertTrue(
            missing.isEmpty(),
            "the plugin jar is not self-contained: a Bukkit classloader sees the server and " +
                "this jar and nothing else, so each of these is a NoClassDefFoundError at " +
                "load: ${missing.keys}",
        )
    }

    @Test
    fun `the plugin jar does not bundle the server API`() {
        val entries = entries() ?: return
        val bundledServer = entries.filter {
            it.startsWith("org/bukkit/") || it.startsWith("io/papermc/paper/") ||
                it.startsWith("net/minecraft/")
        }
        assertTrue(
            bundledServer.isEmpty(),
            "the jar bundles ${bundledServer.size} server-API classes, shadowing the running " +
                "server's own: ${bundledServer.take(3)}",
        )
    }

    @Test
    fun `the plugin jar does not bundle PlaceholderAPI`() {
        val entries = entries() ?: return
        val bundled = entries.filter { it.startsWith("me/clip/") }
        assertTrue(
            bundled.isEmpty(),
            "PlaceholderAPI is bundled (${bundled.size} classes); it must be compileOnly, or " +
                "it shadows the server's own copy: ${bundled.take(3)}",
        )
    }

    @Test
    fun `PlaceholderAPI is declared as a soft dependency`() {
        val plugin = File("build/resources/main/plugin.yml").takeIf { it.isFile }?.readText() ?: return
        assertTrue(
            Regex("""softdepend:.*PlaceholderAPI""").containsMatchIn(plugin),
            "plugin.yml does not softdepend on PlaceholderAPI, so load order is not guaranteed",
        )
        assertTrue(
            !Regex("""^depend:.*PlaceholderAPI""", RegexOption.MULTILINE).containsMatchIn(plugin),
            "PlaceholderAPI must not be a hard dependency; shadr runs without it",
        )
    }

    @Test
    fun `no dependency signature files survived into the jar`() {
        val entries = entries() ?: return
        val signatures = entries.filter {
            it.startsWith("META-INF/") &&
                (it.endsWith(".SF") || it.endsWith(".DSA") || it.endsWith(".RSA") || it.endsWith(".EC"))
        }
        assertTrue(signatures.isEmpty(), "signature files would fail jar verification: $signatures")
    }

    @Test
    fun `the bundled starter library is in the jar, not just in build resources`() {
        val entries = entries() ?: return
        assertTrue(
            "bundled/${BundledAssets.INDEX.removePrefix("bundled/")}" in entries ||
                BundledAssets.INDEX in entries,
            "the seeding index is not in the jar, so a dropped-in plugin seeds nothing",
        )
        for (group in BundledAssets.TARGETS.keys) {
            assertTrue(
                entries.any { it.startsWith("bundled/$group/") },
                "nothing bundled under '$group', so first-run seeding writes none of it",
            )
        }
    }
}
