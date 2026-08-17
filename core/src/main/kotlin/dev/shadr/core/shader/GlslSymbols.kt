/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.shader

object GlslSymbols {

    fun macros(source: String): List<String> =
        Regex("""^\s*#\s*define\s+([A-Za-z_]\w*)""", RegexOption.MULTILINE)
            .findAll(source)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    fun globals(source: String): List<String> {
        val out = mutableListOf<String>()
        val declaration = Regex(
            """(?:^|[;{}])\s*(?:const\s+|uniform\s+|flat\s+)*[A-Za-z_]\w*\s+([A-Za-z_]\w*)\s*[({=]""",
            RegexOption.MULTILINE,
        )

        var depth = 0
        val topLevel = StringBuilder()
        var index = 0
        val stripped = strip(source)

        while (index < stripped.length) {
            val char = stripped[index]
            when {
                char == '{' -> {
                    depth++
                    if (depth == 1) topLevel.append('{')
                }
                char == '}' -> {
                    depth--
                    if (depth == 0) topLevel.append('}')
                }
                depth == 0 -> topLevel.append(char)
            }
            index++
        }

        for (match in declaration.findAll(topLevel.toString())) {
            val name = match.groupValues[1]
            if (name in KEYWORDS) continue
            if (name == ShaderDef.ENTRY_POINT) continue
            out += name
        }
        return out.distinct()
    }

    private fun strip(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            val two = if (index + 1 < source.length) source.substring(index, index + 2) else ""
            when {
                two == "//" -> {
                    while (index < source.length && source[index] != '\n') index++
                }
                two == "/*" -> {
                    index += 2
                    while (index + 1 < source.length && source.substring(index, index + 2) != "*/") index++
                    index += 2
                }
                else -> {
                    out.append(source[index])
                    index++
                }
            }
        }
        return out.toString()
    }

    private val KEYWORDS = setOf(
        "if", "for", "while", "switch", "return", "else", "do",
        "void", "float", "int", "bool", "vec2", "vec3", "vec4",
        "mat2", "mat3", "mat4", "ivec2", "ivec3", "ivec4", "const", "uniform",
    )
}
