/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.shader

object GlslValidator {

    data class Problem(val message: String, val line: Int = 0, val fatal: Boolean = true)

    private val BANNED = mapOf(
        "#version" to "the generated program declares its own version",
        "#moj_import" to "it was a nightmare to implement",
        "#extension" to "extensions must be declared by the generated program",
    )

    private val RESERVED = listOf(
        "main", "fragColor", "texCoord0", "Sampler0", "vertexColor", "shadr_dispatch",
    )

    fun validate(def: ShaderDef): List<Problem> {
        val problems = mutableListOf<Problem>()
        val lines = def.source.lines()

        for ((index, raw) in lines.withIndex()) {
            val line = raw.substringBefore("//").trim()
            if (line.isEmpty()) continue

            for ((directive, why) in BANNED) {
                if (line.startsWith(directive)) {
                    problems += Problem("remove `$directive`: $why", index + 1)
                }
            }
            for (name in RESERVED) {
                if (Regex("""\b\w+\s+$name\s*\(""").containsMatchIn(line) ||
                    Regex("""^\s*(uniform|out|in)\s+\w+\s+$name\b""").containsMatchIn(line)
                ) {
                    problems += Problem("`$name` is defined by the generated program", index + 1)
                }
            }
        }

        if (!Regex("""\bvec4\s+${ShaderDef.ENTRY_POINT}\s*\(""").containsMatchIn(def.source)) {
            problems += Problem(
                "no `vec4 ${ShaderDef.ENTRY_POINT}(vec2 uv, float time, vec4 tint)`; " +
                    "that is the function the renderer calls",
            )
        }

        balance(def.source)?.let { problems += it }
        return problems
    }

    private fun balance(source: String): Problem? {
        var depth = 0
        var line = 1
        var openedAt = 1
        var inLineComment = false
        var inBlockComment = false
        var previous = ' '

        for (char in source) {
            when {
                char == '\n' -> { line++; inLineComment = false }
                inLineComment || (inBlockComment && !(previous == '*' && char == '/')) -> Unit
                inBlockComment -> inBlockComment = false
                previous == '/' && char == '/' -> inLineComment = true
                previous == '/' && char == '*' -> inBlockComment = true
                char == '{' -> { if (depth == 0) openedAt = line; depth++ }
                char == '}' -> {
                    depth--
                    if (depth < 0) return Problem("unmatched `}`", line)
                }
            }
            previous = char
        }
        return if (depth > 0) Problem("unclosed `{` opened here", openedAt) else null
    }
}
