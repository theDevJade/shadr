/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
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
    private val chainFile = File(overlay, "post_effect/invert.json")
    private val postInclude = File(overlay, "include/shadr_post.glsl")

    @Test
    fun `a blur panel's layer lands at the depth the post pass looks for`() {
        val translationZ = -HudPositionCalculator.BLUR_PANEL_LAYER *
            HudPositionCalculator.LEGACY_LAYER_Z_PIXEL_MULTIPLIER

        val depth = 0.95 - (translationZ / 100_000_000.0)

        assertEquals(HudPositionCalculator.BLUR_PANEL_DEPTH, depth, 1e-12)
        assertEquals(0.945, depth, 1e-9)
    }

    @Test
    fun `the blur band cannot collide with an authored layer`() {
        val epsilon = 0.0005
        fun depthOf(layer: Double) =
            HudPositionCalculator.HUD_DEPTH_BASE + layer * HudPositionCalculator.LAYER_TO_DEPTH

        for (layer in listOf(0.0, 1.0, 10.0, 100.0, 9700.0, 10_000.0)) {
            val gap = Math.abs(depthOf(layer) - HudPositionCalculator.BLUR_PANEL_DEPTH)
            assertTrue(
                gap > epsilon * 2,
                "layer $layer is only $gap from the blur band, which the shader would read as a panel",
            )
        }

        assertTrue(HudPositionCalculator.BLUR_PANEL_DEPTH < depthOf(0.0))
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
            effect.programs.contains("post_effect/invert.json"),
            "turning the effect off would leave the chain in the pack",
        )
    }
}
