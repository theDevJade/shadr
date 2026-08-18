/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class LicenseHeaderTest {
    private val repo = File("..").canonicalFile

    private val notice = "Copyright © 2026 theDevJade"

    @Test
    fun `only shadr's own sources carry the banner`() {
        val stamped = repo.walkTopDown()
            .onEnter { it.name !in setOf("build", "bin", ".git", ".gradle", "out", "node_modules") }
            .filter { it.isFile && it.extension in setOf("kt", "java", "glsl", "fsh", "vsh") }
            .filter { it.readText().contains(notice) }
            .toList()
        assertTrue(stamped.isNotEmpty(), "nothing carries the banner at all")

        val stray = stamped.filterNot { file ->
            val path = file.relativeTo(repo).invariantSeparatorsPath
            "/src/" in path || (path.startsWith("shaders/") && file.extension == "glsl")
        }
        assertTrue(
            stray.isEmpty(),
            "these carry a shadr copyright but are not shadr source; check the Spotless " +
                "targets: ${stray.take(5).map { it.relativeTo(repo).path }}",
        )
    }

    @Test
    fun `no shader program carries the banner`() {
        val shaders = File(repo, "shaders")
        if (!shaders.isDirectory) return
        val stamped = shaders.walkTopDown()
            .filter { it.isFile && it.extension in setOf("fsh", "vsh") }
            .filter { it.readText().contains(notice) }
            .toList()
        assertTrue(
            stamped.isEmpty(),
            "a shadr banner belongs on neither a Mojang-derived program nor above #version, " +
                "where it makes the client discard every pipeline define: " +
                "${stamped.map { it.relativeTo(repo).path }}",
        )
    }

    @Test
    fun `the LICENSE the banner points at exists and names the same holder`() {
        val license = File(repo, "LICENSE")
        assertTrue(license.isFile, "the banner says 'See LICENSE' and there is no LICENSE file")

        val text = license.readText()
        assertTrue(
            text.contains("theDevJade"),
            "LICENSE names a different copyright holder from the banner applied to every source file",
        )
        assertTrue(
            text.contains("Apache License") && text.contains("Version 2.0"),
            "the banner claims Apache-2.0 and LICENSE is something else",
        )
    }

    @Test
    fun `the NOTICE attributes the shaders shadr did not write`() {
        val notice = File(repo, "NOTICE")
        assertTrue(notice.isFile, "Apache-2.0 section 4(d) needs a NOTICE and there is none")

        val text = notice.readText()
        assertTrue(text.contains("Mojang"), "NOTICE does not name the owner of the copied core shaders")
        assertTrue(
            text.contains("shaders/overlays/"),
            "NOTICE does not say which files the third-party terms cover",
        )
    }

    @Test
    fun `the shared header file is the one being applied`() {
        val header = File(repo, "gradle/license-header.txt")
        assertTrue(header.isFile, "no shared header at gradle/license-header.txt")
        assertTrue(header.readText().contains(notice))

        val builds = listOf(
            "platform-minestom/build.gradle.kts",
            "testserver/build.gradle.kts",
            "platform-paper-nms/build.gradle.kts",
        )
        for (build in builds) {
            val script = File(repo, build).takeIf { it.isFile } ?: continue
            assertTrue(
                script.readText().contains("gradle/license-header.txt"),
                "$build does not reference the shared header",
            )
        }
    }
}
