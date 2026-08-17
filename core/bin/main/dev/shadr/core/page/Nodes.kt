/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.page

class Node(val raw: Map<String, Any?>, val vars: Map<String, Double> = emptyMap()) {

    operator fun get(path: String): Any? {
        if (!path.contains('.')) return raw[path]
        var current: Any? = raw
        for (segment in path.split('.')) {
            val map = current as? Map<*, *> ?: return null
            current = map[segment]
        }
        return current
    }

    fun has(path: String): Boolean = get(path) != null

    fun string(vararg paths: String): String? {
        for (p in paths) {
            val v = get(p) ?: continue
            val s = v.toString()
            if (s.isNotBlank()) return s
        }
        return null
    }

    fun number(vararg paths: String, fallback: Double = 0.0): Double {
        for (p in paths) {
            val v = get(p) ?: continue
            Expr.eval(v, vars)?.let { return it }
        }
        return fallback
    }

    fun int(vararg paths: String, fallback: Int = 0): Int = number(*paths, fallback = fallback.toDouble()).toInt()

    fun bool(vararg paths: String, fallback: Boolean = false): Boolean {
        for (p in paths) {
            val v = get(p) ?: continue
            when (v) {
                is Boolean -> return v
                is Number -> return v.toDouble() != 0.0
                else -> when (v.toString().trim().lowercase()) {
                    "true", "yes", "on", "1" -> return true
                    "false", "no", "off", "0" -> return false
                }
            }
        }
        return fallback
    }

    fun list(path: String): List<Any?> = (get(path) as? List<*>)?.toList() ?: emptyList()

    fun child(path: String, vars: Map<String, Double> = this.vars): Node? =
        (get(path) as? Map<*, *>)?.let { Node(it.stringKeyed(), vars) }

    fun withVars(extra: Map<String, Double>) = Node(raw, vars + extra)
}

@Suppress("UNCHECKED_CAST")
fun Map<*, *>.stringKeyed(): Map<String, Any?> =
    entries.associate { (k, v) -> k.toString() to v } as Map<String, Any?>

fun Any?.asNode(vars: Map<String, Double> = emptyMap()): Node? =
    (this as? Map<*, *>)?.let { Node(it.stringKeyed(), vars) }
