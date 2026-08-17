/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.shader

import dev.shadr.core.Rgb
import java.io.File

class ShaderLoader(private val dir: File) {

    val issues = mutableListOf<String>()

    fun load(): ShaderRegistry {
        issues.clear()
        val files = dir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.extension.equals("glsl", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: return ShaderRegistry.EMPTY

        val defs = files.mapNotNull { file ->
            val id = file.nameWithoutExtension.lowercase()
            val invalid = ShaderDef.validateId(id)
            if (invalid != null) {
                issues += "${file.name}: $invalid"
                return@mapNotNull null
            }
            val source = file.readText()
            val def = ShaderDef(
                id = id,
                source = source,
                description = directive(source, "description") ?: "",
                previewTint = directive(source, "preview")?.let { Rgb.parse(it) } ?: Rgb.WHITE,
            )
            val problems = GlslValidator.validate(def)
            problems.forEach { issues += "${file.name}: ${it.message}" }
            def
        }

        val registry = ShaderRegistry(defs)
        for ((name, claimants) in registry.conflicts) {
            val loser = claimants.drop(1).joinToString(", ")
            issues += "$loser: '$name' is already defined by '${claimants.first()}'; " +
                "every shader compiles into one program, so the name has to be unique"
        }
        registry.dropped.forEach {
            issues += "$it: more than ${ShaderDef.MAX_SHADERS} shaders"
        }
        return registry
    }

    fun write(id: String, source: String): File =
        File(dir.apply { mkdirs() }, "$id.glsl").apply { writeText(source) }

    fun delete(id: String): Boolean = File(dir, "$id.glsl").delete()

    fun rename(from: String, to: String): String? {
        ShaderDef.validateId(to)?.let { return it }
        val source = File(dir, "$from.glsl")
        if (!source.isFile) return "no shader called '$from'"
        val target = File(dir, "$to.glsl")
        if (target.exists()) return "a shader called '$to' already exists"
        return if (source.renameTo(target)) null else "could not rename '$from'"
    }

    private fun directive(source: String, name: String): String? =
        Regex("""^\s*//\s*@$name\s+(.+)$""", RegexOption.MULTILINE)
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.trim()
}
