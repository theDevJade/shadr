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

class SourceEscapeTest {
    private val escapedPlaceholder = Regex("""\$\{'\$'}\{([^}\n]*)}""")

    private val expressionSyntax = Regex("""[.(\[+\-*/!?=]""")

    private val brokenBareInterpolation = Regex("""\$\{'\$'}\w""")

    private val allowed = setOf(

        "SourceEscapeTest.kt",
    )

    @Test
    fun `no Kotlin source escapes a dollar it meant to interpolate`() {
        val offenders = mutableListOf<String>()
        for (file in kotlinSources()) {
            if (file.name in allowed) continue
            file.readLines().forEachIndexed { index, line ->
                val expression = escapedPlaceholder.findAll(line)
                    .map { it.groupValues[1] }
                    .firstOrNull { expressionSyntax.containsMatchIn(it) }
                val why = when {
                    expression != null -> "escaped a dollar in front of the expression '$expression'"
                    brokenBareInterpolation.containsMatchIn(line) ->
                        "escaped a dollar in front of a name, with no braces to be template syntax"
                    else -> return@forEachIndexed
                }
                offenders += "${file.path}:${index + 1}: $why\n    ${line.trim()}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "these ship their own template syntax:\n" +
                offenders.joinToString("\n"),
        )
    }

    private fun kotlinSources(): List<File> {
        val repo = File("..").canonicalFile
        return repo.listFiles()
            .orEmpty()
            .filter { it.isDirectory && File(it, "src").isDirectory }
            .flatMap { module ->
                File(module, "src").walkTopDown().filter { it.isFile && it.extension == "kt" }
            }
            .also { assertTrue(it.size > 50, "found only ${it.size} Kotlin files; the scan is not reaching the tree") }
    }
}
