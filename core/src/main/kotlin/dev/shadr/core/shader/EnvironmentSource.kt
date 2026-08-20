/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.shader

import java.io.File

class EnvironmentSource(val shaderRoot: File) {
    private fun customFile(relative: String) = File(File(shaderRoot, "custom/all"), relative)

    private fun defaultFile(relative: String) =
        File(File(shaderRoot, "overlays/$DEFAULT_OVERLAY"), relative)

    private fun sharedFile(relative: String) =
        File(File(shaderRoot, "overlays/$SHARED_OVERLAY"), relative)

    fun isCustomised(relative: String): Boolean = customFile(relative).isFile

    fun read(relative: String): String? {
        customFile(relative).takeIf { it.isFile }?.let { return it.readText() }
        defaultFile(relative).takeIf { it.isFile }?.let { return it.readText() }
        return sharedFile(relative).takeIf { it.isFile }?.readText()
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

        const val SHARED_OVERLAY = "_shared"
    }
}
