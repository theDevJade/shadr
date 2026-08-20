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

    private val shared = File("../shaders/overlays/${PackOverlay.SHARED_DIRECTORY}").canonicalFile

    private val overlays: List<File> = PackOverlay.entries
        .map { File("../shaders/overlays/${it.sourceDirectory}").canonicalFile }

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

    private fun compiler(): Boolean = GlslCompiler.available()

    private fun profileFor(root: File): Map<String, String> {
        val overlay = PackOverlay.entries.firstOrNull { it.sourceDirectory == root.name }
            ?: error("${root.name} has no PackOverlay entry, so it cannot be given a shader profile")
        return mapOf(PackOverlay.PROFILE_INCLUDE to overlay.profileGlsl())
    }

    private fun resolve(root: File, file: File, seen: MutableSet<String> = mutableSetOf()): String =
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
                val include = File(root, "include/$name").takeIf { it.isFile }
                    ?: File(shared, "include/$name")
                if (!include.isFile) {
                    val produced = generated[name] ?: profileFor(root)[name]
                    assertTrue(produced != null, "${file.name} imports $name, which does not exist")
                    appendLine(produced)
                    continue
                }
                appendLine(
                    resolve(root, include, seen).lineSequence()
                        .filterNot { it.trimStart().startsWith("#version") }
                        .joinToString("\n"),
                )
            }
        }

    private fun postProgramsFor(root: File): List<File> {
        val byName = linkedMapOf<String, File>()
        for (dir in listOf(File(shared, "post"), File(root, "post"))) {
            dir.listFiles { f -> f.extension == "fsh" }.orEmpty().forEach { byName[it.name] = it }
        }
        return byName.values.sortedBy { it.name }
    }

    @Test
    fun `every post program compiles for every overlay that ships it`() {
        if (!compiler()) return
        assertTrue(overlays.isNotEmpty(), "no shader overlays found")

        val broken = mutableListOf<String>()
        val counts = mutableMapOf<String, Int>()
        for (root in overlays) {
            val programs = postProgramsFor(root)
            counts[root.name] = programs.size
            val dir = createTempDirectory("shadr-post-${root.name}").toFile()
            for (program in programs) {
                val flattened = File(dir, program.nameWithoutExtension + ".frag")
                flattened.writeText(resolve(root, program))

                val process = ProcessBuilder("glslangValidator", "-S", "frag", flattened.path)
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) {
                    broken += "${root.name}/${program.name}:\n" + output.lines().take(8).joinToString("\n")
                }
            }
        }

        assertTrue(broken.isEmpty(), "these post programs do not compile:\n" + broken.joinToString("\n\n"))
        assertTrue(
            counts.values.all { it > 0 },
            "every overlay must ship post programs now that the blur chain is shared, but got $counts",
        )
    }

    @Test
    fun `the fullscreen vertex program compiles for every overlay`() {
        if (!compiler()) return

        for (root in overlays) {
            val source = File(root, "post/shadr_fullscreen.vsh").takeIf { it.isFile }
                ?: File(shared, "post/shadr_fullscreen.vsh")
            assertTrue(source.isFile, "${root.name} has no fullscreen vertex program")

            val dir = createTempDirectory("shadr-post-vert-${root.name}").toFile()
            val flattened = File(dir, "fullscreen.vert")
            flattened.writeText(resolve(root, source))

            val process = ProcessBuilder("glslangValidator", "-S", "vert", flattened.path)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(
                process.waitFor() == 0,
                "${root.name}: the fullscreen vertex program does not compile:\n$output",
            )
        }
    }
}
