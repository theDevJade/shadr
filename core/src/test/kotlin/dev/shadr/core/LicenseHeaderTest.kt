/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class LicenseHeaderTest {
    private val repo = File("..").canonicalFile

    private val notice = "Copyright © 2026 theDevJade"

    private fun sourcesUnder(dir: File): List<File> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "glsl", "fsh", "vsh") }
            .toList()

    @Test
    fun `only shadr's own sources carry the banner`() {
        val stamped = repo.walkTopDown()
            .onEnter { it.name !in setOf("build", ".git", ".gradle", "out", "node_modules") }
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
            "a banner on a shader program pushes #version off line 1, which makes the client " +
                "discard every pipeline define: ${stamped.map { it.relativeTo(repo).path }}",
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
        assertTrue(text.isNotBlank(), "LICENSE is empty")
    }

    @Test
    fun `the shared header file is the one being applied`() {
        val header = File(repo, "gradle/license-header.txt")
        assertTrue(header.isFile, "no shared header at gradle/license-header.txt")
        assertTrue(header.readText().contains(notice))

        for (build in listOf("platform-minestom/build.gradle.kts", "testserver/build.gradle.kts")) {
            val script = File(repo, build).takeIf { it.isFile } ?: continue
            assertTrue(
                script.readText().contains("gradle/license-header.txt"),
                "$build does not reference the shared header",
            )
        }
    }
}
