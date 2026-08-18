/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class PostShaderCompileTest {

    private val overlay = File("../shaders/overlays/mc_26_2").canonicalFile

    private val globals = """
        layout(std140) uniform Globals {
            ivec3 CameraBlockPos;
            vec3 CameraOffset;
            vec2 ScreenSize;
            float GlintAlpha;
            float GameTime;
            int MenuBlurRadius;
            int UseRgss;
        };
    """.trimIndent()

    private val generated = mapOf(
        "shadr_map.glsl" to dev.shadr.core.stream.MapPalette.glsl() + "\n" +
            dev.shadr.core.stream.StreamFormat.glsl() + "\n" +
            dev.shadr.core.stream.StreamLayout.glsl() + "\n" +
            dev.shadr.core.stream.StreamImage.glsl() + "\n" +
            dev.shadr.core.stream.StreamBlocks.glsl() + "\n" +
            dev.shadr.core.stream.StreamCodec.glsl(dev.shadr.core.stream.StreamPresets.CODEC_1080),
    )

    private fun compiler(): Boolean = runCatching {
        ProcessBuilder("glslangValidator", "-v").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun resolve(file: File, seen: MutableSet<String> = mutableSetOf()): String =
        buildString {
            for (line in file.readLines()) {
                val match = Regex("""\s*#moj_import\s+<([^>]+)>""").find(line)
                if (match == null) {
                    appendLine(line)
                    continue
                }
                val reference = match.groupValues[1]
                val name = reference.substringAfterLast(':')
                if (!seen.add(name)) continue

                if (reference.contains(':')) {
                    if (name == "globals.glsl") appendLine(globals)
                    continue
                }
                val include = File(overlay, "include/$name")
                if (!include.isFile) {
                    val produced = generated[name]
                    assertTrue(produced != null, "${file.name} imports $name, which does not exist")
                    appendLine(produced)
                    continue
                }
                appendLine(
                    resolve(include, seen).lineSequence()
                        .filterNot { it.trimStart().startsWith("#version") }
                        .joinToString("\n"),
                )
            }
        }

    @Test
    fun `every post program compiles`() {
        if (!compiler()) return

        val dir = createTempDirectory("shadr-glsl").toFile()
        val programs = File(overlay, "post").listFiles { f -> f.extension == "fsh" }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue(programs.isNotEmpty(), "no post programs to compile")

        val broken = mutableListOf<String>()
        for (program in programs) {
            val flattened = File(dir, program.nameWithoutExtension + ".frag")
            flattened.writeText(resolve(program))

            val process = ProcessBuilder("glslangValidator", "-S", "frag", flattened.path)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) {
                broken += "${program.name}:\n" + output.lines().take(8).joinToString("\n")
            }
        }

        assertTrue(broken.isEmpty(), "these post programs do not compile:\n" + broken.joinToString("\n\n"))
    }

    @Test
    fun `the fullscreen vertex program compiles`() {
        if (!compiler()) return

        val source = File(overlay, "post/shadr_fullscreen.vsh")
        assertTrue(source.isFile, "no fullscreen vertex program")

        val dir = createTempDirectory("shadr-glsl-vert").toFile()
        val flattened = File(dir, "fullscreen.vert")
        flattened.writeText(resolve(source))

        val process = ProcessBuilder("glslangValidator", "-S", "vert", flattened.path)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor() == 0, "the fullscreen vertex program does not compile:\n$output")
    }
}
