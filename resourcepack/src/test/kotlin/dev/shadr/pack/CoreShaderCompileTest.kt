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

    private val overlays: List<File> = File("../shaders/overlays").canonicalFile
        .listFiles { f -> f.isDirectory && File(f, "core").isDirectory }
        .orEmpty()
        .sortedBy { it.name }

    private val sharedInclude =
        File("../shaders/overlays/${PackOverlay.SHARED_DIRECTORY}/include").canonicalFile

    private data class VanillaApi(
        val label: String,
        val includes: Map<String, String>,
        val perFaceLighting: Boolean,
    )

    private val glsl150Light = """
        #define MINECRAFT_LIGHT_POWER (0.6)
        #define MINECRAFT_AMBIENT_LIGHT (0.4)
        layout(std140) uniform Lighting {
            vec3 Light0_Direction;
            vec3 Light1_Direction;
        };
        vec4 minecraft_mix_light(vec3 a, vec3 b, vec3 n, vec4 c) { return c; }
    """.trimIndent()

    private val glsl330Light = glsl150Light + "\n" + """
        vec2 minecraft_compute_light(vec3 a, vec3 b, vec3 n) { return vec2(dot(a, n), dot(b, n)); }
        vec4 minecraft_mix_light_separate(vec2 l, vec4 c) { return c; }
    """.trimIndent()

    private val fogInclude = """
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
    """.trimIndent()

    private val projectionInclude = """
        layout(std140) uniform Projection {
            mat4 ProjMat;
        };
        vec4 projection_from_position(vec4 position) {
            vec4 projection = position * 0.5;
            projection.xy = vec2(projection.x + projection.w, projection.y + projection.w);
            projection.zw = position.zw;
            return projection;
        }
    """.trimIndent()

    private val sampleLightmapInclude = """
        vec4 sample_lightmap(sampler2D lightMap, ivec2 uv) {
            return texture(lightMap, clamp(vec2(uv) / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
        }
    """.trimIndent()

    private fun globals(camera: Boolean) = buildString {
        appendLine("layout(std140) uniform Globals {")
        if (camera) {
            appendLine("    ivec3 CameraBlockPos;")
            appendLine("    vec3 CameraOffset;")
        }
        appendLine("    vec2 ScreenSize;")
        appendLine("    float GlintAlpha;")
        appendLine("    float GameTime;")
        appendLine("    int MenuBlurRadius;")
        if (camera) appendLine("    int UseRgss;")
        appendLine("};")
    }

    private fun dynamicTransforms(lineWidth: Boolean) = buildString {
        appendLine("layout(std140) uniform DynamicTransforms {")
        appendLine("    mat4 ModelViewMat;")
        appendLine("    vec4 ColorModulator;")
        appendLine("    vec3 ModelOffset;")
        appendLine("    mat4 TextureMat;")
        if (lineWidth) appendLine("    float LineWidth;")
        appendLine("};")
    }

    private fun api(label: String, camera: Boolean, lineWidth: Boolean, newLight: Boolean, lightmap: Boolean) =
        VanillaApi(
            label = label,
            perFaceLighting = newLight,
            includes = buildMap {
                put("globals.glsl", globals(camera))
                put("projection.glsl", projectionInclude)
                put("dynamictransforms.glsl", dynamicTransforms(lineWidth))
                put("fog.glsl", fogInclude)
                put("light.glsl", if (newLight) glsl330Light else glsl150Light)
                if (lightmap) put("sample_lightmap.glsl", sampleLightmapInclude)
            },
        )

    private val oneTwentyOneEarly =
        api("1.21.6-1.21.8", camera = false, lineWidth = true, newLight = false, lightmap = false)

    private val oneTwentyOneLate =
        api("1.21.9-1.21.11", camera = false, lineWidth = true, newLight = true, lightmap = false)

    private val twentySix =
        api("26.1-26.2", camera = true, lineWidth = false, newLight = true, lightmap = true)

    private val apisFor = mapOf(
        "mc_1_21_x" to listOf(oneTwentyOneEarly, oneTwentyOneLate),
        "mc_26_1" to listOf(twentySix),
        "mc_26_2" to listOf(twentySix),
    )

    private fun profileFor(root: File): Map<String, String> {
        val overlay = PackOverlay.entries.firstOrNull { it.sourceDirectory == root.name }
            ?: error("${root.name} has no PackOverlay entry, so it cannot be given a shader profile")
        return mapOf(PackOverlay.PROFILE_INCLUDE to overlay.profileGlsl())
    }

    private fun generated(): Map<String, String> = mapOf(
        "shadr_map.glsl" to MapPalette.glsl() + "\n" + StreamFormat.glsl() + "\n" +
            dev.shadr.core.stream.StreamLayout.glsl() + "\n" +
            dev.shadr.core.stream.StreamImage.glsl() + "\n" +
            dev.shadr.core.stream.StreamBlocks.glsl() + "\n" +
            dev.shadr.core.stream.StreamCodec.glsl(dev.shadr.core.stream.StreamPresets.CODEC_1080),
        "shadr_shaders.glsl" to dev.shadr.core.shader.GlslComposer.compose(dev.shadr.core.shader.ShaderRegistry.EMPTY),
    )

    private fun compiler(): Boolean = GlslCompiler.available()

    private fun resolve(root: File, api: VanillaApi, file: File, body: String, seen: MutableSet<String>): String =
        buildString {
            for (line in body.lines()) {
                val match = Regex("""\s*#moj_import\s+<([^>]+)>""").find(line)
                if (match == null) {
                    appendLine(line)
                    continue
                }
                val name = match.groupValues[1].substringAfterLast(':')
                if (!seen.add(name)) continue

                val authored = File(root, "include/$name").takeIf { it.isFile }
                    ?: File(sharedInclude, name).takeIf { it.isFile }
                val source = when {
                    authored != null -> authored.readText()
                    else -> generated()[name]
                        ?: profileFor(root)[name]
                        ?: api.includes[name]
                        ?: error(
                            "${file.name} imports $name, which ${api.label} does not provide and " +
                                "shadr does not author",
                        )
                }
                appendLine(
                    resolve(root, api, authored ?: file, source, seen)
                        .lineSequence()
                        .filterNot { it.trimStart().startsWith("#version") }
                        .joinToString("\n"),
                )
            }
        }

    private fun variantsFor(name: String, api: VanillaApi): List<List<String>> {
        val base: List<List<String>> = when {
            name == "text" || name == "rendertype_text" || name == "rendertype_text_intensity" -> listOf(
                emptyList(),
                listOf("IS_GRAYSCALE"),
                listOf("IS_GUI"),
                listOf("IS_GUI", "IS_GRAYSCALE"),
                listOf("IS_SEE_THROUGH"),
            )
            name == "item" -> listOf(emptyList(), listOf("ALPHA_CUTOUT 0.1"), listOf("NO_CARDINAL_LIGHTING"))
            name == "entity" -> listOf(emptyList(), listOf("NO_CARDINAL_LIGHTING"), listOf("EMISSIVE"))
            else -> listOf(emptyList())
        }
        if (!api.perFaceLighting) return base
        return base + listOf(listOf("PER_FACE_LIGHTING"))
    }

    @Test
    fun `every core program compiles against every vanilla API its overlay claims`() {
        if (!compiler()) return
        assertTrue(overlays.isNotEmpty(), "no shader overlays found")

        val broken = mutableListOf<String>()
        val covered = mutableMapOf<String, Int>()
        for (root in overlays) {
            val apis = apisFor[root.name]
                ?: error("${root.name} has no vanilla API mapping, so nothing verifies it")
            for (api in apis) {
                val result = compileOverlay(root, api)
                broken += result.failures
                covered[root.name] = (covered[root.name] ?: 0) + result.programs
            }
        }

        assertTrue(broken.isEmpty(), broken.joinToString("\n\n"))
        assertTrue(
            covered.keys == overlays.map { it.name }.toSet() && covered.values.all { it > 0 },
            "every overlay must compile against at least one vanilla API, but got $covered",
        )
    }

    private data class OverlayResult(val programs: Int, val failures: List<String>)

    private fun compileOverlay(root: File, api: VanillaApi): OverlayResult {
        val dir = createTempDirectory("shadr-core-${root.name}").toFile()
        val programs = File(root, "core").listFiles { f -> f.extension == "vsh" || f.extension == "fsh" }
            .orEmpty()
            .sortedBy { it.name }
        if (programs.isEmpty()) return OverlayResult(0, listOf("${root.name}: no core programs to compile"))

        val broken = mutableListOf<String>()
        for (program in programs) {
            val stage = if (program.extension == "vsh") "vert" else "frag"
            for (defines in variantsFor(program.nameWithoutExtension, api)) {
                val flattened = resolve(root, api, program, program.readText(), mutableSetOf())
                val withDefines = flattened.lines().toMutableList()
                val versionAt = withDefines.indexOfFirst { it.trimStart().startsWith("#version") }
                withDefines.addAll(versionAt + 1, defines.map { "#define $it" })

                val tag = defines.joinToString("_").ifEmpty { "default" }
                val out = File(dir, "${program.nameWithoutExtension}_${stage}_${api.label}_$tag.$stage")
                out.writeText(withDefines.joinToString("\n"))

                val process = ProcessBuilder("glslangValidator", "-S", stage, out.path)
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) {
                    broken += "${root.name}/${program.name} on ${api.label} " +
                        "[${defines.joinToString(", ").ifEmpty { "default" }}]:\n" +
                        output.lines().take(8).joinToString("\n")
                }
            }
        }
        return OverlayResult(programs.size, broken)
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
