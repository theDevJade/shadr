/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.shader.GlslComposer
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class ItemShaderCompileTest {

    private val items = File("../shaders/items").canonicalFile

    private fun compiler(): Boolean = runCatching {
        ProcessBuilder("glslangValidator", "-v").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    @Test
    fun `every shipped shader compiles as the editor previews it`() {
        if (!compiler()) return

        val sources = items.listFiles { f -> f.extension == "glsl" }.orEmpty().sortedBy { it.name }
        assertTrue(sources.isNotEmpty(), "no shaders in ${items.path}")

        val dir = createTempDirectory("shadr-item-glsl").toFile()
        val broken = mutableListOf<String>()

        for (source in sources) {
            val (program, _) = GlslComposer.previewProgram(source.readText())
            val flattened = File(dir, source.nameWithoutExtension + ".frag")
            flattened.writeText(program)

            val process = ProcessBuilder("glslangValidator", "-S", "frag", flattened.path)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) {
                broken += "${source.name}:\n" + output.lines().take(8).joinToString("\n")
            }
        }

        assertTrue(broken.isEmpty(), "these shaders do not compile:\n" + broken.joinToString("\n\n"))
    }
}
