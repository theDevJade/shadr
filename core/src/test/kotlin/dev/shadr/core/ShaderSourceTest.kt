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

class ShaderSourceTest {
    private val shaders = File("../shaders").canonicalFile

    private fun programs(): List<File> =
        shaders.walkTopDown()
            .filter { it.isFile && it.extension in setOf("fsh", "vsh") }
            .toList()

    @Test
    fun `every shader program starts with the version directive on line one`() {
        val files = programs()
        assertTrue(files.size > 20, "found only ${files.size} shader programs; the scan is wrong")

        val offenders = files.filterNot { it.readText().startsWith("#version") }
        assertTrue(
            offenders.isEmpty(),
            "these do not begin with #version, so Minecraft's preprocessor will inject every " +
                "pipeline #define into the wrong place and the client will reject the pack:\n" +
                offenders.joinToString("\n") { file ->
                    "  ${file.relativeTo(shaders).path}: starts with ${file.readText().take(24).lines().first()}"
                },
        )
    }

    @Test
    fun `the first line of every shader program is the version directive itself`() {
        for (file in programs()) {
            val firstLine = file.readText().substringBefore('\n').trim()
            assertTrue(
                firstLine.startsWith("#version"),
                "${file.relativeTo(shaders).path} has '$firstLine' where #version must be",
            )
        }
    }

    @Test
    fun `an injected define lands in live code rather than inside a comment`() {
        for (file in programs()) {
            val source = file.readText()
            val injectIndex = source.indexOf('\n') + 1
            val injected = source.substring(0, injectIndex) +
                "#define SHADR_INJECTION_PROBE\n#line 1 0\n" +
                source.substring(injectIndex)

            val withoutBlockComments = injected.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            assertTrue(
                withoutBlockComments.contains("#define SHADR_INJECTION_PROBE"),
                "${file.relativeTo(shaders).path}: an injected #define is swallowed by a " +
                    "comment, so every pipeline define for this program is silently discarded",
            )
        }
    }
}
