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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShaderOverrideTest {
    private fun repo() = File("..").canonicalFile

    private fun generate(
        environment: Set<dev.shadr.core.shader.EnvironmentEffect> = emptySet(),
        customise: (File) -> Unit = {},
    ): Pair<File, PackGenerator> {
        val root = createTempDirectory("shadr-override").toFile()
        val shaders = File(root, "src").also { File(repo(), "shaders").copyRecursively(it) }

        File(shaders, "custom").deleteRecursively()
        customise(shaders)

        val out = File(root, "pack")
        val generator = PackGenerator(
            shaderSrc = shaders,
            fontDir = File(repo(), "assets/font"),
            soundDir = File(repo(), "assets/shadr/sounds"),
            environment = dev.shadr.core.shader.EnvironmentEffect.entries
                .associateWith { it in environment },
        )
        generator.build(out)
        return out to generator
    }

    private fun write(dir: File, rel: String, text: String) =
        File(dir, rel).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun `a custom program shadr does not ship is copied into every overlay`() {
        val (out, generator) = generate { shaders ->
            write(shaders, "custom/all/core/particle.fsh", "// mine\n")
        }

        for (overlay in PackOverlay.entries) {
            val file = File(out, "${overlay.directory}/assets/minecraft/shaders/core/particle.fsh")
            assertTrue(file.isFile, "particle.fsh missing from ${overlay.directory}")
            assertEquals("// mine\n", file.readText())
        }
        assertTrue(generator.overrides.any { it.endsWith("core/particle.fsh") }, "${generator.overrides}")
    }

    @Test
    fun `a custom program replaces one shadr ships`() {
        val (out, _) = generate(setOf(dev.shadr.core.shader.EnvironmentEffect.SKY)) { shaders ->
            write(shaders, "custom/all/core/sky.fsh", "// replaced\n")
        }
        val file = File(out, "shadr_26_2/assets/minecraft/shaders/core/sky.fsh")
        assertEquals("// replaced\n", file.readText())
    }

    @Test
    fun `a version-specific override beats an all-versions one`() {
        val (out, _) = generate(setOf(dev.shadr.core.shader.EnvironmentEffect.SKY)) { shaders ->
            write(shaders, "custom/all/core/sky.fsh", "// all\n")
            write(shaders, "custom/mc_26_2/core/sky.fsh", "// just 26.2\n")
        }
        assertEquals(
            "// just 26.2\n",
            File(out, "shadr_26_2/assets/minecraft/shaders/core/sky.fsh").readText(),
        )
        assertEquals(
            "// all\n",
            File(out, "shadr_26_1/assets/minecraft/shaders/core/sky.fsh").readText(),
        )
    }

    @Test
    fun `a custom include is importable`() {
        val (out, _) = generate { shaders ->
            write(shaders, "custom/all/include/mine.glsl", "float mine() { return 1.0; }\n")
        }
        assertTrue(
            File(out, "shadr_26_2/assets/minecraft/shaders/include/mine.glsl").isFile,
        )
    }

    @Test
    fun `no overrides are reported when none were supplied`() {
        val (_, generator) = generate()
        assertEquals(emptyList(), generator.overrides)
    }

    @Test
    fun `world overrides are absent unless switched on`() {
        val (off, _) = generate()
        for (effect in dev.shadr.core.shader.EnvironmentEffect.entries) {
            for (program in effect.programs) {
                assertTrue(
                    !File(off, "shadr_26_2/assets/minecraft/shaders/$program").isFile,
                    "${effect.id} shipped $program while switched off",
                )
            }
        }

        val (on, _) = generate(dev.shadr.core.shader.EnvironmentEffect.entries.toSet())
        for (effect in dev.shadr.core.shader.EnvironmentEffect.entries) {
            for (program in effect.programs) {
                assertTrue(
                    File(on, "shadr_26_2/assets/minecraft/shaders/$program").isFile,
                    "${effect.id} did not ship $program while switched on",
                )
            }
        }
    }

    @Test
    fun `celestial textures do not ship without their program`() {
        val (out, _) = generate()
        val sun = File(out, "assets/minecraft/textures/environment/celestial/sun.png")
        assertTrue(!sun.isFile, "the sun texture shipped without the shader that reads it")
    }

    @Test
    fun `the sky and cloud stages agree on what they pass between them`() {
        val core = File(repo(), "shaders/overlays/mc_26_2/core")
        val pairs = mapOf(
            "sky" to listOf("shadrSkyDir"),
            "rendertype_clouds" to listOf("cloudPosition", "cloudFace", "cloudLayerY"),
        )
        for ((program, varyings) in pairs) {
            val vsh = File(core, "$program.vsh").readText()
            val fsh = File(core, "$program.fsh").readText()
            for (name in varyings) {
                assertTrue(
                    Regex("""\bout\s+\w+\s+$name\s*;""").containsMatchIn(vsh) ||
                        Regex("""flat\s+out\s+\w+\s+$name\s*;""").containsMatchIn(vsh),
                    "$program.vsh never declares `out $name`",
                )
                assertTrue(fsh.contains(name), "$program.fsh never reads $name")
                assertTrue(
                    Regex("""$name\s*=""").containsMatchIn(vsh),
                    "$program.vsh declares $name but never assigns it",
                )
            }
        }
    }

    @Test
    fun `the cloud march discards the faces a volume does not have`() {
        val fsh = File(repo(), "shaders/overlays/mc_26_2/core/rendertype_clouds.fsh").readText()
        assertTrue(
            Regex("""if\s*\(\s*cloudFace\s*>=\s*2\s*\)\s*discard""").containsMatchIn(fsh),
            "side faces are not discarded",
        )
    }

    @Test
    fun `the sky and the cloud march both dither`() {
        val core = File(repo(), "shaders/overlays/mc_26_2/core")
        assertTrue(
            File(core, "rendertype_clouds.fsh").readText().contains("jitter"),
            "the cloud march does not jitter its first step",
        )
        assertTrue(
            File(core, "sky.fsh").readText().contains("shadr_dither"),
            "the sky gradient is not dithered",
        )
    }

    @Test
    fun `the cloud march uses its own hash, not the shared sky noise`() {
        val fsh = File(repo(), "shaders/overlays/mc_26_2/core/rendertype_clouds.fsh").readText()
        assertTrue(fsh.contains("float cloud_hash(vec3 p)"), "cloud_hash was replaced")
        assertTrue(
            !fsh.contains("shadr_sky_noise") && !fsh.contains("shadr_sky_fbm"),
            "the cloud march is using the sky's noise; its constants are not tuned for it",
        )
    }

    @Test
    fun `no dotfiles reach the pack`() {
        val (out, _) = generate { shaders ->
            write(shaders, "custom/all/core/.DS_Store", "junk")
            write(shaders, "custom/all/core/particle.fsh", "// real\n")
        }
        val junk = out.walkTopDown().filter { it.isFile && it.name.startsWith(".") }.toList()
        assertTrue(junk.isEmpty(), "filesystem junk shipped: ${junk.map { it.name }}")
        assertTrue(
            File(out, "shadr_26_2/assets/minecraft/shaders/core/particle.fsh").isFile,
            "the real file was dropped along with the junk",
        )
    }

    @Test
    fun `celestial textures carry an id the shader can read`() {
        val (out, _) = generate(setOf(dev.shadr.core.shader.EnvironmentEffect.CELESTIALS))
        val expected = mapOf(
            "sun.png" to CelestialAssets.ID_SUN,
            "moon/full_moon.png" to CelestialAssets.ID_MOON,
            "moon/new_moon.png" to CelestialAssets.ID_MOON,
        )
        for ((rel, id) in expected) {
            val file = File(out, "assets/minecraft/textures/environment/celestial/$rel")
            assertTrue(file.isFile, "missing celestial texture: $rel")
            val image = javax.imageio.ImageIO.read(file)
            assertEquals(CelestialAssets.GRID, image.width, "$rel is the wrong size")

            val argb = image.getRGB(3, 5)
            assertEquals(CelestialAssets.MARKER_A, argb ushr 24, "$rel has the wrong marker alpha")
            assertEquals(id, argb and 0xFF, "$rel carries the wrong body id")
        }
    }

    @Test
    fun `no shader invents a sun direction`() {
        val dir = File(repo(), "shaders/overlays/mc_26_2")
        for (file in dir.walkTopDown().filter { it.extension in setOf("glsl", "fsh", "vsh") }) {
            val body = file.readLines()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                .joinToString("\n")
            assertTrue(
                !body.contains("shadr_sun_dir"),
                "${file.name} derives a sun direction; SUN_ANGLE is not available to core shaders",
            )
        }
    }

    @Test
    fun `the sky uses a horizon-safe air mass`() {
        val glsl = File(repo(), "shaders/overlays/mc_26_2/include/shadr_sky.glsl").readText()
        assertTrue(glsl.contains("93.885"), "Kasten-Young air mass is not being used")
    }

    @Test
    fun `the sky shifts vanilla's colour`() {
        val fsh = File(repo(), "shaders/overlays/mc_26_2/core/sky.fsh").readText()
        assertTrue(fsh.contains("ColorModulator.rgb"), "the sky ignores vanilla's own colour")
        assertTrue(
            fsh.contains("base * mix(") || fsh.contains("base *"),
            "the sky does not derive its output from ColorModulator",
        )
    }
}
