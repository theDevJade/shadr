/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.page

object Expr {

    fun screenVars(width: Double, height: Double): Map<String, Double> = mapOf(
        "screenWidth" to width, "screenHeight" to height,
        "width" to width, "height" to height,
        "halfWidth" to width / 2.0, "halfHeight" to height / 2.0,
    )

    fun eval(raw: Any?, vars: Map<String, Double> = emptyMap()): Double? {
        when (raw) {
            null -> return null
            is Number -> return raw.toDouble()
            is Boolean -> return if (raw) 1.0 else 0.0
        }
        val text = raw.toString().trim()
        if (text.isEmpty()) return null
        text.toDoubleOrNull()?.let { return it }
        return try {
            Parser(text, vars).parse()
        } catch (_: ExprError) {
            null
        }
    }

    fun evalOr(raw: Any?, fallback: Double, vars: Map<String, Double> = emptyMap()): Double =
        eval(raw, vars) ?: fallback

    private class ExprError(message: String) : RuntimeException(message)

    private class Parser(private val src: String, private val vars: Map<String, Double>) {
        private var pos = 0

        fun parse(): Double {
            val v = additive()
            skipSpace()
            if (pos != src.length) throw ExprError("trailing input at $pos in '$src'")
            if (!v.isFinite()) throw ExprError("non-finite result for '$src'")
            return v
        }

        private fun additive(): Double {
            var left = multiplicative()
            while (true) {
                skipSpace()
                when (peek()) {
                    '+' -> { pos++; left += multiplicative() }
                    '-' -> { pos++; left -= multiplicative() }
                    else -> return left
                }
            }
        }

        private fun multiplicative(): Double {
            var left = unary()
            while (true) {
                skipSpace()
                when (peek()) {
                    '*' -> { pos++; left *= unary() }
                    '/' -> {
                        pos++
                        val d = unary()
                        if (d == 0.0) throw ExprError("division by zero in '$src'")
                        left /= d
                    }
                    '%' -> {
                        pos++
                        val d = unary()
                        if (d == 0.0) throw ExprError("modulo by zero in '$src'")
                        left %= d
                    }
                    else -> return left
                }
            }
        }

        private fun unary(): Double {
            skipSpace()
            return when (peek()) {
                '-' -> { pos++; -unary() }
                '+' -> { pos++; unary() }
                else -> primary()
            }
        }

        private fun primary(): Double {
            skipSpace()
            val c = peek() ?: throw ExprError("unexpected end of '$src'")
            if (c == '(') {
                pos++
                val v = additive()
                skipSpace()
                if (peek() != ')') throw ExprError("unbalanced parenthesis in '$src'")
                pos++
                return v
            }
            if (c.isDigit() || c == '.') return number()
            if (c.isLetter() || c == '_') return variable()
            throw ExprError("unexpected '$c' at $pos in '$src'")
        }

        private fun number(): Double {
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            return src.substring(start, pos).toDoubleOrNull()
                ?: throw ExprError("bad number '${src.substring(start, pos)}'")
        }

        private fun variable(): Double {
            val start = pos
            while (peek()?.let { it.isLetterOrDigit() || it == '_' || it == '.' } == true) pos++
            val name = src.substring(start, pos)
            return vars[name] ?: throw ExprError("unknown variable '$name'")
        }

        private fun peek(): Char? = src.getOrNull(pos)
        private fun skipSpace() { while (peek()?.isWhitespace() == true) pos++ }
    }
}
