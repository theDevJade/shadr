/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.text.Glyphs
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackAssetsTest {
    private fun generate(): File {
        val root = createTempDirectory("shadr-pack-assets").toFile()
        val repo = File("..").canonicalFile
        PackGenerator(
            shaderSrc = File(repo, "shaders"),
            fontDir = File(repo, "assets/font"),
            soundDir = File(repo, "assets/shadr/sounds"),
        ).build(root)
        return root
    }

    private fun texture(root: File, rel: String) =
        File(root, "assets/minecraft/textures/font/shadr/$rel")

    @Test
    fun `corner art is generated large enough to scale`() {
        val root = generate()
        for (radius in Glyphs.RADII) {
            for (corner in Glyphs.CORNERS) {
                val file = texture(root, "rounding/$radius/$corner.png")
                assertTrue(file.isFile, "missing corner art: $radius/$corner")
                val image = ImageIO.read(file)
                assertTrue(
                    image.width >= 128,
                    "corner $radius/$corner is only ${image.width}px, so it will stair-step",
                )
            }
        }
    }

    @Test
    fun `the typeface provider is outranked by shadr's own glyphs`() {
        val root = generate()
        val json = File(root, "assets/minecraft/font/shadr.json").readText()

        val ttfIndex = json.indexOf(""""type": "ttf"""")
        val firstBitmapIndex = json.indexOf(""""type": "bitmap"""")
        assertTrue(ttfIndex >= 0, "no ttf provider in the UI font")
        assertTrue(firstBitmapIndex >= 0, "no bitmap providers in the UI font")
        assertTrue(
            ttfIndex < firstBitmapIndex,
            "the ttf provider is listed after shadr's bitmaps, so the typeface's private-use " +
                "icons would shadow every shape, corner and cursor",
        )
    }

    @Test
    fun `the typeface never supplies a codepoint shadr reserves`() {
        val root = generate()
        for (weight in listOf(Glyphs.FONT_UI, Glyphs.FONT_UI_SEMIBOLD)) {
            val json = File(root, "assets/minecraft/font/$weight.json").readText()
            val skip = Regex(""""skip"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
            assertTrue(!skip.isNullOrEmpty(), "$weight lets the typeface claim shadr's private-use range")
            for (codepoint in listOf(Glyphs.BACKGROUND, Glyphs.CIRCLE, Glyphs.HEAD_JOIN)) {
                assertTrue(
                    skip!!.contains(FontAssets.escape(codepoint.code)),
                    "$weight does not skip ${FontAssets.escape(codepoint.code)}, " +
                        "so the typeface can shadow it",
                )
            }
        }
    }

    @Test
    fun `both UI weights carry the private-use bitmaps`() {
        val root = generate()
        for (weight in listOf(Glyphs.FONT_UI, Glyphs.FONT_UI_SEMIBOLD)) {
            val json = File(root, "assets/minecraft/font/$weight.json").readText()
            assertTrue(
                json.contains(FontAssets.escape(Glyphs.BACKGROUND.code)),
                "$weight has no provider for the fill glyph every block draws",
            )
        }
    }

    @Test
    fun `every private-use glyph shadr relies on has a provider`() {
        val root = generate()
        val json = File(root, "assets/minecraft/font/shadr.json").readText()

        val required = mapOf(
            "background" to Glyphs.BACKGROUND,
            "gradient" to Glyphs.GRADIENT,
            "slider" to Glyphs.SLIDER,
            "circle" to Glyphs.CIRCLE,
            "cursor" to Glyphs.CURSOR,
        )
        for ((name, glyph) in required) {
            val escaped = "\\u%04X".format(glyph.code)
            assertTrue(
                json.contains(escaped, ignoreCase = true),
                "$name (U+%04X) has no provider, so the Nerd Font's icon at that codepoint " .format(glyph.code) +
                    "would render instead",
            )
        }
    }

    @Test
    fun `each corner glyph is the right quadrant`() {
        val root = generate()

        val quadrants = listOf(0 to (0 to 0), 1 to (1 to 0), 2 to (1 to 1), 3 to (0 to 1))
        for ((index, quadrant) in quadrants) {
            val image = ImageIO.read(texture(root, "rounding/regular/${index + 1}.png"))
            val (qx, qy) = quadrant

            val x = if (qx == 0) image.width / 12 else image.width * 11 / 12
            val y = if (qy == 0) image.height / 12 else image.height * 11 / 12
            assertEquals(
                0,
                image.getRGB(x, y) ushr 24,
                "corner ${index + 1} is opaque where its arc should have cut away",
            )

            val ox = if (qx == 0) image.width * 11 / 12 else image.width / 12
            val oy = if (qy == 0) image.height * 11 / 12 else image.height / 12
            assertTrue((image.getRGB(ox, oy) ushr 24) > 200, "corner ${index + 1} is not filled")
        }
    }

    @Test
    fun `every declared sound event ships an ogg`() {
        val root = generate()
        val json = File(root, "assets/minecraft/sounds.json")
        assertTrue(json.isFile, "no sounds.json in the pack")

        val referenced = Regex("\"(shadr/[a-z_]+)\"").findAll(json.readText())
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(referenced.isNotEmpty(), "sounds.json declares no shadr sounds")

        for (path in referenced) {
            val ogg = File(root, "assets/minecraft/sounds/$path.ogg")
            assertTrue(ogg.isFile, "sounds.json declares $path but no ogg was packed")
            assertTrue(ogg.length() > 0, "$path.ogg is empty")
        }
    }

    @Test
    fun `the typeface the pages are authored against is packed`() {
        val root = generate()
        val fonts = File(root, "assets/minecraft/font").listFiles()?.map { it.name }.orEmpty()
        assertTrue(fonts.any { it.endsWith(".ttf") }, "no typeface in the pack: $fonts")
    }
}
