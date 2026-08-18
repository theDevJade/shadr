/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.stream.MapPalette
import dev.shadr.core.stream.StreamFormat
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class CoreShaderCompileTest {

    private val overlay = File("../shaders/overlays/mc_26_2").canonicalFile

    private val vanilla = mapOf(
        "globals.glsl" to """
            layout(std140) uniform Globals {
                ivec3 CameraBlockPos;
                vec3 CameraOffset;
                vec2 ScreenSize;
                float GlintAlpha;
                float GameTime;
                int MenuBlurRadius;
                int UseRgss;
            };
        """.trimIndent(),
        "projection.glsl" to """
            layout(std140) uniform Projection {
                mat4 ProjMat;
            };
        """.trimIndent(),
        "dynamictransforms.glsl" to """
            layout(std140) uniform DynamicTransforms {
                mat4 ModelViewMat;
                vec4 ColorModulator;
                vec3 ModelOffset;
                mat4 TextureMat;
                float LineWidth;
            };
        """.trimIndent(),
        "fog.glsl" to """
            layout(std140) uniform Fog {
                vec4 FogColor;
                float FogEnvironmentalStart;
                float FogEnvironmentalEnd;
                float FogRenderDistanceStart;
                float FogRenderDistanceEnd;
                float FogSkyEnd;
                float FogCloudsEnd;
            };
            float fog_spherical_distance(vec3 p) { return length(p); }
            float fog_cylindrical_distance(vec3 p) { return max(length(p.xz), abs(p.y)); }
            float linear_fog_value(float d, float s, float e) { return clamp((d - s) / (e - s), 0.0, 1.0); }
            float total_fog_value(float a, float b, float c, float d, float e, float f) {
                return max(linear_fog_value(a, c, d), linear_fog_value(b, e, f));
            }
            vec4 apply_fog(vec4 c, float a, float b, float d, float e, float f, float g, vec4 h) { return c; }
        """.trimIndent(),
        "light.glsl" to """
            layout(std140) uniform Lighting {
                vec3 Light0_Direction;
                vec3 Light1_Direction;
            };
            vec2 minecraft_compute_light(vec3 a, vec3 b, vec3 n) { return vec2(dot(a, n), dot(b, n)); }
            vec4 minecraft_mix_light_separate(vec2 l, vec4 c) { return c; }
            vec4 minecraft_mix_light(vec3 a, vec3 b, vec3 n, vec4 c) { return c; }
        """.trimIndent(),
        "sample_lightmap.glsl" to """
            vec4 sample_lightmap(sampler2D lightMap, ivec2 uv) {
                return texture(lightMap, clamp(vec2(uv) / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
            }
        """.trimIndent(),
    )

    private fun generated(): Map<String, String> = mapOf(
        "shadr_map.glsl" to MapPalette.glsl() + "\n" + StreamFormat.glsl() + "\n" +
            dev.shadr.core.stream.StreamLayout.glsl() + "\n" +
            dev.shadr.core.stream.StreamImage.glsl() + "\n" +
            dev.shadr.core.stream.StreamBlocks.glsl() + "\n" +
            dev.shadr.core.stream.StreamCodec.glsl(dev.shadr.core.stream.StreamPresets.CODEC_1080),
        "shadr_shaders.glsl" to dev.shadr.core.shader.GlslComposer.compose(dev.shadr.core.shader.ShaderRegistry.EMPTY),
    )

    private fun compiler(): Boolean = runCatching {
        ProcessBuilder("glslangValidator", "-v").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun resolve(file: File, body: String, seen: MutableSet<String>): String =
        buildString {
            for (line in body.lines()) {
                val match = Regex("""\s*#moj_import\s+<([^>]+)>""").find(line)
                if (match == null) {
                    appendLine(line)
                    continue
                }
                val reference = match.groupValues[1]
                val name = reference.substringAfterLast(':')
                if (!seen.add(name)) continue

                if (reference.contains(':')) {
                    vanilla[name]?.let { appendLine(it) }
                    continue
                }

                val authored = File(overlay, "include/$name")
                val source = when {
                    authored.isFile -> authored.readText()
                    else -> generated()[name]
                        ?: error("${file.name} imports $name, which is neither authored nor generated")
                }
                appendLine(
                    resolve(authored.takeIf { it.isFile } ?: file, source, seen)
                        .lineSequence()
                        .filterNot { it.trimStart().startsWith("#version") }
                        .joinToString("\n"),
                )
            }
        }

    private fun variantsFor(name: String): List<List<String>> = when {
        name.startsWith("text") -> listOf(
            emptyList(),
            listOf("IS_GRAYSCALE"),
            listOf("IS_GUI"),
            listOf("IS_GUI", "IS_GRAYSCALE"),
            listOf("IS_SEE_THROUGH"),
        )
        name == "item" -> listOf(emptyList(), listOf("ALPHA_CUTOUT 0.1"), listOf("NO_CARDINAL_LIGHTING"))
        else -> listOf(emptyList())
    }

    @Test
    fun `every core program compiles in every pipeline variant`() {
        if (!compiler()) return

        val dir = createTempDirectory("shadr-core-glsl").toFile()
        val programs = File(overlay, "core").listFiles { f -> f.extension == "vsh" || f.extension == "fsh" }
            .orEmpty()
            .sortedBy { it.name }
        assertTrue(programs.isNotEmpty(), "no core programs to compile")

        val broken = mutableListOf<String>()
        for (program in programs) {
            val stage = if (program.extension == "vsh") "vert" else "frag"
            for (defines in variantsFor(program.nameWithoutExtension)) {
                val flattened = resolve(program, program.readText(), mutableSetOf())
                val withDefines = flattened.lines().toMutableList()
                val versionAt = withDefines.indexOfFirst { it.trimStart().startsWith("#version") }
                withDefines.addAll(versionAt + 1, defines.map { "#define $it" })

                val out = File(dir, "${program.nameWithoutExtension}_${stage}_${defines.joinToString("_")}.$stage")
                out.writeText(withDefines.joinToString("\n"))

                val process = ProcessBuilder("glslangValidator", "-S", stage, out.path)
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) {
                    broken += "${program.name} [${defines.joinToString(", ").ifEmpty { "default" }}]:\n" +
                        output.lines().take(8).joinToString("\n")
                }
            }
        }

        assertTrue(broken.isEmpty(), broken.joinToString("\n\n"))
    }

    @Test
    fun `the generated map table is emitted into every overlay that ships the stream include`() {
        val root = createTempDirectory("shadr-pack").toFile()
        val generator = PackGenerator(
            shaderSrc = File("../shaders").canonicalFile,
            fontDir = File("../assets/font").canonicalFile,
            environment = dev.shadr.core.shader.EnvironmentEffect.entries.associateWith { false },
        )
        generator.build(root)

        val streamOverlays = PackOverlay.entries.filter {
            File(File("../shaders/overlays"), it.sourceDirectory).resolve("include/shadr_stream.glsl").isFile
        }
        assertTrue(streamOverlays.isNotEmpty(), "no overlay ships the stream include")

        for (overlay in PackOverlay.entries) {
            val table = File(root, "${overlay.directory}/assets/minecraft/shaders/include/shadr_map.glsl")
            if (overlay in streamOverlays) {
                assertTrue(table.isFile, "${overlay.label} ships the stream include but no shadr_map.glsl")
                val text = table.readText()
                assertTrue(text.contains("const int SHADR_MAP_WORD[256]"), "${overlay.label} table is truncated")
                assertTrue(text.contains("#define SHADR_STREAM_MAGIC_LOW"), "${overlay.label} lacks the stream header")
            } else {
                assertTrue(!table.isFile, "${overlay.label} got a table it cannot use")
            }
        }
    }
}
