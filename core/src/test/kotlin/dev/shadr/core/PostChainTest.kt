/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.hud.HudPositionCalculator
import dev.shadr.core.shader.EnvironmentEffect
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PostChainTest {
    private val overlay = File("../shaders/overlays/mc_26_2").canonicalFile
    private val chainFile = File(overlay, "post_effect/creeper.json")
    private val postInclude = File(overlay, "include/shadr_post.glsl")

    @Test
    fun `hud_glsl recognises the blur panel at the translation the renderer gives it`() {
        val translationZ = -HudPositionCalculator.BLUR_PANEL_LAYER *
            HudPositionCalculator.LEGACY_LAYER_Z_PIXEL_MULTIPLIER

        val hud = File(overlay, "include/hud.glsl").readText()
        val declared = Regex("""#define\s+SHADR_BLUR_PANEL_Z\s+([0-9.]+)""")
            .find(hud)?.groupValues?.get(1)?.toDouble()

        assertEquals(
            translationZ, declared,
            "hud.glsl keys bad",
        )
    }

    @Test
    fun `the vertex stage tags the panel and the fragment stage reads that tag`() {
        val hud = File(overlay, "include/hud.glsl").readText()
        val fragment = File(overlay, "include/hud_fragment.glsl").readText()

        assertTrue(
            hud.contains("shadrMode = 3.0"),
            "hud.glsl no longer tags the blur panel, so nothing downstream can find it",
        )
        assertTrue(
            fragment.contains("shadr_is_blur_panel()"),
            "hud_fragment.glsl exposes no way to read the tag",
        )
        assertTrue(
            Regex("""shadr_is_field\(\)[^}]*shadrMode < 2\.5""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(fragment),
            "the blur tag (3.0) also reads as a distance field, which would corrupt the glyph",
        )
        assertTrue(
            File(overlay, "core/text.fsh").readText().contains("SHADR_BLUR_KEY"),
            "text.fsh never paints the key, so the post chain has nothing to detect",
        )
    }

    @Test
    fun `the core shaders and the post chain agree on the key colour`() {
        fun keyIn(file: String): String? = Regex("""#define\s+SHADR_BLUR_KEY\s+(vec3\([^)]*\))""")
            .find(File(overlay, file).readText())?.groupValues?.get(1)?.replace(" ", "")

        val core = keyIn("include/hud_fragment.glsl")
        val post = keyIn("include/shadr_post.glsl")

        assertNotNull(core, "hud_fragment.glsl declares no SHADR_BLUR_KEY")
        assertEquals(core, post, "the panel is painted in one colour and looked for in another")
    }

    @Test
    fun `the chain asks for no depth buffer`() {
        assertTrue(
            !chainFile.readText().contains("use_depth_buffer"),
            "an entity post effect is handed an empty depth buffer, so this input reads all zeroes",
        )
        for (shader in listOf("post/shadr_blur_extract.fsh", "post/shadr_blur_composite.fsh")) {
            assertTrue(
                !File(overlay, shader).readText().contains("DepthSampler"),
                "$shader still samples a depth buffer that is never populated",
            )
        }
    }

    @Test
    fun `the blur layer stays reserved, so no authored layer is mistaken for a panel`() {
        for (layer in listOf(0.0, 1.0, 10.0, 100.0, 9700.0, 10_000.0)) {
            assertTrue(
                layer != HudPositionCalculator.BLUR_PANEL_LAYER,
                "layer $layer collides with the reserved blur layer",
            )
        }
        assertTrue(HudPositionCalculator.BLUR_PANEL_LAYER < 0.0)
    }

    @Test
    fun `the GLSL and Kotlin agree on the blur layer`() {
        val source = postInclude.readText()
        val declared = Regex("""#define\s+SHADR_BLUR_PANEL_LAYER\s+(-?[0-9.]+)""")
            .find(source)?.groupValues?.get(1)?.toDouble()
        assertEquals(
            HudPositionCalculator.BLUR_PANEL_LAYER, declared,
            "shadr_post.glsl and HudPositionCalculator disagree on the blur layer",
        )

        val base = Regex("""#define\s+SHADR_HUD_DEPTH_BASE\s+([0-9.]+)""")
            .find(source)?.groupValues?.get(1)?.toDouble()
        assertEquals(HudPositionCalculator.HUD_DEPTH_BASE, base, "the HUD depth base drifted")
    }

    @Test
    fun `hud_glsl still writes the depth base the post pass assumes`() {
        val hud = File(overlay, "include/hud.glsl").readText()
        val base = Regex("""pos\.z\s*=\s*([0-9.]+)\s*-""").find(hud)?.groupValues?.get(1)?.toDouble()
        assertEquals(
            HudPositionCalculator.HUD_DEPTH_BASE, base,
            "hud.glsl no longer writes the depth base shadr_post.glsl keys off",
        )
    }

    @Test
    fun `the chain parses and every shader it names exists`() {
        assertTrue(chainFile.isFile, "no post chain at ${chainFile.path}")
        val json = chainFile.readText()

        val referenced = Regex(""""(?:vertex|fragment)_shader":\s*"minecraft:([^"]+)"""")
            .findAll(json).map { it.groupValues[1] }.toSet()
        assertTrue(referenced.isNotEmpty(), "the chain names no shaders")

        for (id in referenced) {
            val extension = if (id.endsWith("fullscreen")) "vsh" else "fsh"
            val file = File(overlay, "$id.$extension")
            assertTrue(file.isFile, "the chain references $id but ${file.path} does not exist")
        }
    }

    @Test
    fun `the blur config uniforms are declared in the order the shader reads them`() {
        val json = chainFile.readText()

        val blocks = json.split(""""vertex_shader"""")
            .drop(1)
            .filter { it.contains("ShadrBlurConfig") }
            .map { it.substringAfter("ShadrBlurConfig") }
        assertTrue(blocks.isNotEmpty(), "no ShadrBlurConfig uniforms in the chain")

        for (block in blocks) {
            val types = Regex(""""type":\s*"(\w+)"""").findAll(block).map { it.groupValues[1] }.toList()
            assertEquals(
                listOf("vec2", "float"), types,
                "ShadrBlurConfig is declared `vec2 BlurDir; float Radius;`, and this list disagrees",
            )
            assertTrue(!block.contains("\"name\""), "UniformValue has no name field; it is ignored")
        }

        val shader = File(overlay, "post/shadr_blur_box.fsh").readText()
        val declared = Regex("""uniform ShadrBlurConfig \{(.*?)}""", RegexOption.DOT_MATCHES_ALL)
            .find(shader)?.groupValues?.get(1)
        assertNotNull(declared, "shadr_blur_box.fsh declares no ShadrBlurConfig block")
        assertTrue(
            declared.indexOf("BlurDir") < declared.indexOf("Radius"),
            "the shader's block order no longer matches the chain's uniform list",
        )
    }

    @Test
    fun `no pass reads and writes the same target`() {
        val json = chainFile.readText()
        val passes = Regex("""\{\s*"vertex_shader".*?"output":\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)

        val chunks = json.split(""""vertex_shader"""").drop(1)
        for (chunk in chunks) {
            val output = Regex(""""output":\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1) ?: continue
            val inputs = Regex(""""target":\s*"([^"]+)"""").findAll(chunk).map { it.groupValues[1] }.toSet()
            assertTrue(
                output !in inputs,
                "a pass both reads and writes '$output', which is undefined",
            )
        }
        assertTrue(passes.containsMatchIn(json))
    }

    @Test
    fun `a blur element is forced onto the reserved layer`() {
        val renderer = dev.shadr.core.hud.PageRenderer()
        for (authored in listOf(0.0, 12.0, 9700.0)) {
            val element = dev.shadr.core.page.Element(
                id = "panel",
                type = dev.shadr.core.page.ElementType.BLUR,
                layer = authored,
                width = 400.0,
                height = 200.0,
            )
            val page = dev.shadr.core.page.Page(name = "t", elements = listOf(element))
            val draw = renderer.render(page).draws.first { it.key.startsWith("panel") }

            val layer = -draw.translation.z / HudPositionCalculator.LEGACY_LAYER_Z_PIXEL_MULTIPLIER
            assertEquals(
                HudPositionCalculator.BLUR_PANEL_LAYER, layer, 1e-6,
                "a blur element authored at layer $authored did not land in the band",
            )
        }
    }

    @Test
    fun `an ordinary element keeps its authored layer`() {
        val element = dev.shadr.core.page.Element(
            id = "box",
            type = dev.shadr.core.page.ElementType.BLOCK,
            layer = 12.0,
            width = 100.0,
            height = 40.0,
        )
        val page = dev.shadr.core.page.Page(name = "t", elements = listOf(element))
        val draw = dev.shadr.core.hud.PageRenderer().render(page).draws.first()
        val layer = -draw.translation.z / HudPositionCalculator.LEGACY_LAYER_Z_PIXEL_MULTIPLIER
        assertEquals(12.0, layer, 1e-6)
    }

    @Test
    fun `the frosted glass toggle names files that exist`() {
        val effect = EnvironmentEffect.entries.firstOrNull { it.id == "blur" }
        assertNotNull(effect, "no 'blur' environment effect")
        assertTrue(effect.programs.isNotEmpty())
        for (program in effect.programs) {
            assertTrue(
                File(overlay, program).isFile,
                "the blur toggle lists '$program', which does not exist in the overlay",
            )
        }

        assertTrue(
            effect.programs.contains("post_effect/creeper.json"),
            "turning the effect off would leave the chain in the pack",
        )
    }
}
