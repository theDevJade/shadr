/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.shader

import java.io.File

class EnvironmentSource(private val shaderRoot: File) {
    private fun customFile(relative: String) = File(File(shaderRoot, "custom/all"), relative)

    private fun defaultFile(relative: String) =
        File(File(shaderRoot, "overlays/$DEFAULT_OVERLAY"), relative)

    fun isCustomised(relative: String): Boolean = customFile(relative).isFile

    fun read(relative: String): String? {
        customFile(relative).takeIf { it.isFile }?.let { return it.readText() }
        return defaultFile(relative).takeIf { it.isFile }?.readText()
    }

    fun write(relative: String, source: String): File =
        customFile(relative).apply {
            parentFile.mkdirs()
            writeText(source)
        }

    fun revert(relative: String): Boolean = customFile(relative).delete()

    fun programs(): List<String> = EnvironmentEffect.entries.flatMap { it.programs }

    companion object {
        const val DEFAULT_OVERLAY = "mc_26_2"
    }
}
