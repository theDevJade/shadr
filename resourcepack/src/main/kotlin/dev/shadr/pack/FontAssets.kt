/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.text.FontMetrics
import dev.shadr.core.text.Glyphs
import dev.shadr.core.text.MetricsTable
import dev.shadr.pack.msdf.MsdfFont
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object FontAssets {
    private const val FONT_DIR = "assets/minecraft/font"
    private const val TEXTURE_DIR = "assets/minecraft/textures/font/shadr"

    fun writeAll(root: File, ttfDir: File): MetricsTable {
        val images = writeTextures(root)
        writeTtf(root, ttfDir)
        val bitmaps = writeUiFont(root, images)
        val fonts = linkedMapOf<String, FontMetrics>()

        for ((key, name) in listOf(
            Glyphs.FONT_UI to "nerd_mono.ttf",
            Glyphs.FONT_UI_SEMIBOLD to "nerd_mono_semibold.ttf",
        )) {
            val ttf = File(ttfDir, name).takeIf { it.isFile } ?: continue
            val base = FontMetricsBuilder.ttf(ttf, MetricsTable.TTF_PIXEL_SIZE, TTF_CODEPOINTS)
            fonts[key] = FontMetricsBuilder.withBitmaps(base, bitmaps)
        }

        fonts += writeSharpFont(root, ttfDir)
        writeHeadFonts(root)
        return MetricsTable(fonts)
    }

    private fun writeSharpFont(root: File, ttfDir: File): Map<String, FontMetrics> = buildMap {
        writeSharpWeight(root, File(ttfDir, "nerd_mono.ttf"), "msdf_nerd_mono", Glyphs.FONT_UI_SHARP)
            ?.let { put(Glyphs.FONT_UI_SHARP, it) }
        writeSharpWeight(
            root, File(ttfDir, "nerd_mono_semibold.ttf"), "msdf_nerd_mono_semibold",
            Glyphs.FONT_UI_SHARP_SEMIBOLD,
        )?.let { put(Glyphs.FONT_UI_SHARP_SEMIBOLD, it) }
    }

    private fun writeSharpWeight(
        root: File,
        ttf: File,
        textureName: String,
        fontKey: String,
    ): FontMetrics? {
        if (!ttf.isFile) return null
        val written = MsdfFont(ttf, cell = MSDF_CELL, spread = MSDF_SPREAD).writeWithMetrics(
            packRoot = root,
            texturePath = "font/shadr/$textureName.png",
            codepointStart = 0,
        )
        write(root, "$FONT_DIR/$fontKey.json", fontJson(listOf(written.provider)))
        return FontMetricsBuilder.msdf(
            advanceTexels = written.advanceTexels,
            cell = MSDF_CELL,
            renderedHeight = written.renderedHeight,
            ascent = written.ascent.toDouble(),
            charset = written.charset,
        )
    }

    private fun writeTextures(root: File): Map<String, BufferedImage> {
        val images = linkedMapOf<String, BufferedImage>()
        fun png(rel: String, image: BufferedImage) {
            images[rel.removePrefix("$TEXTURE_DIR/").let { "shadr/$it" }] = image
            png(root, rel, image)
        }

        png("$TEXTURE_DIR/background.png", solid(1, 1, Color.WHITE))

        png("$TEXTURE_DIR/rounded.png", roundedSquare(26, 4.0))
        png("$TEXTURE_DIR/rounded2.png", roundedSquare(26, 7.0))
        png("$TEXTURE_DIR/rounded3.png", roundedSquare(26, 11.0))

        png("$TEXTURE_DIR/gradient.png", verticalGradient(512, 512))
        png("$TEXTURE_DIR/slider.png", sliderTrack(64))
        png("$TEXTURE_DIR/circle.png", filledEllipse(512))

        png("$TEXTURE_DIR/spaces.png", transparent(256, 256))

        Glyphs.RADII.forEachIndexed { radiusIndex, radiusName ->
            val size = CORNER_SOURCE_SIZE
            Glyphs.CORNERS.indices.forEach { corner ->
                png("$TEXTURE_DIR/rounding/$radiusName/${corner + 1}.png", quarterDisc(size, corner))
            }
            check(radiusIndex in Glyphs.RADII.indices)
        }

        Glyphs.CURSOR_NAMES.forEachIndexed { index, name ->
            png("$TEXTURE_DIR/cursors/$name.png", cursor(index))
        }

        png("$TEXTURE_DIR/head_pixel.png", solid(1, 1, Color.WHITE))
        return images
    }

    private fun solid(width: Int, height: Int, color: Color) = image(width, height) { g ->
        g.color = color
        g.fillRect(0, 0, width, height)
    }

    private fun transparent(width: Int, height: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    private fun roundedSquare(size: Int, radius: Double) = image(size, size) { g ->
        g.color = Color.WHITE
        g.fillRoundRect(0, 0, size, size, (radius * 2).toInt(), (radius * 2).toInt())
    }

    private fun quarterDisc(size: Int, corner: Int) = image(size, size) { g ->
        val d = size * 2.0
        val (ox, oy) = when (corner) {
            0 -> 0.0 to 0.0
            1 -> -size.toDouble() to 0.0
            2 -> -size.toDouble() to -size.toDouble()
            else -> 0.0 to -size.toDouble()
        }
        g.color = Color.WHITE
        g.fill(Ellipse2D.Double(ox, oy, d, d))
    }

    private fun verticalGradient(width: Int, height: Int): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            val alpha = (255.0 * (1.0 - y.toDouble() / (height - 1))).toInt().coerceIn(0, 255)
            val argb = (alpha shl 24) or 0xFFFFFF
            for (x in 0 until width) img.setRGB(x, y, argb)
        }
        return img
    }

    private fun filledEllipse(size: Int) = image(size, size) { g ->
        g.color = Color.WHITE
        g.fillOval(0, 0, size, size)
    }

    private fun sliderTrack(size: Int) = image(size, size) { g ->
        val thickness = size / 4
        g.color = Color.WHITE
        g.fillRoundRect(0, (size - thickness) / 2, size, thickness, thickness, thickness)
    }

    private fun cursor(index: Int) = image(CURSOR_SIZE, CURSOR_SIZE) { g ->
        val outline = Color(0, 0, 0, 200)
        when (Glyphs.CURSOR_NAMES[index]) {
            "cursor" -> arrow(g, outline)
            "hover" -> hand(g, outline)
            "move" -> arrows(g, outline, horizontal = true, vertical = true)
            "scale-l-r" -> arrows(g, outline, horizontal = true, vertical = false)
            "scale-t-b" -> arrows(g, outline, horizontal = false, vertical = true)
            "scale-tl-br" -> diagonalArrows(g, outline, leaning = true)
            "scale-tr-bl" -> diagonalArrows(g, outline, leaning = false)
            "text" -> caret(g, outline)
        }
    }

    private fun arrow(g: java.awt.Graphics2D, outline: Color) {
        val p = Path2D.Double().apply {
            moveTo(1.0, 0.0); lineTo(1.0, 12.0); lineTo(4.5, 9.0)
            lineTo(6.5, 14.0); lineTo(9.0, 13.0); lineTo(7.0, 8.2); lineTo(11.5, 8.2)
            closePath()
        }
        g.color = Color.WHITE
        g.fill(p)
        g.color = outline
        g.draw(p)
    }

    private fun hand(g: java.awt.Graphics2D, outline: Color) {
        g.color = Color.WHITE
        g.fillRoundRect(5, 2, 3, 8, 2, 2)
        g.fillRoundRect(8, 5, 3, 6, 2, 2)
        g.fillRoundRect(2, 6, 3, 5, 2, 2)
        g.fillRoundRect(3, 8, 9, 6, 3, 3)
        g.color = outline
        g.drawRoundRect(3, 8, 9, 6, 3, 3)
    }

    private fun arrows(g: java.awt.Graphics2D, outline: Color, horizontal: Boolean, vertical: Boolean) {
        g.color = Color.WHITE
        if (horizontal) {
            g.fillRect(2, 7, 12, 2)
            g.fillPolygon(intArrayOf(0, 4, 4), intArrayOf(8, 4, 12), 3)
            g.fillPolygon(intArrayOf(16, 12, 12), intArrayOf(8, 4, 12), 3)
        }
        if (vertical) {
            g.fillRect(7, 2, 2, 12)
            g.fillPolygon(intArrayOf(8, 4, 12), intArrayOf(0, 4, 4), 3)
            g.fillPolygon(intArrayOf(8, 4, 12), intArrayOf(16, 12, 12), 3)
        }
        g.color = outline
        g.drawRect(2, 7, 12, 2)
    }

    private fun diagonalArrows(g: java.awt.Graphics2D, outline: Color, leaning: Boolean) {
        g.color = Color.WHITE
        val stroke = java.awt.BasicStroke(2f)
        g.stroke = stroke
        if (leaning) {
            g.drawLine(3, 3, 13, 13)
            g.fillPolygon(intArrayOf(1, 6, 1), intArrayOf(1, 1, 6), 3)
            g.fillPolygon(intArrayOf(15, 10, 15), intArrayOf(15, 15, 10), 3)
        } else {
            g.drawLine(13, 3, 3, 13)
            g.fillPolygon(intArrayOf(15, 10, 15), intArrayOf(1, 1, 6), 3)
            g.fillPolygon(intArrayOf(1, 6, 1), intArrayOf(15, 15, 10), 3)
        }
        g.color = outline
    }

    private fun caret(g: java.awt.Graphics2D, outline: Color) {
        g.color = Color.WHITE
        g.fillRect(7, 1, 2, 14)
        g.fillRect(4, 1, 8, 2)
        g.fillRect(4, 13, 8, 2)
        g.color = outline
        g.drawRect(7, 1, 2, 14)
    }

    private inline fun image(width: Int, height: Int, draw: (java.awt.Graphics2D) -> Unit): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.composite = AlphaComposite.Src
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.composite = AlphaComposite.SrcOver
        draw(g)
        g.dispose()
        return img
    }

    private fun png(root: File, rel: String, image: BufferedImage) {
        val file = File(root, rel)
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }

    private fun writeTtf(root: File, ttfDir: File) {
        for (name in listOf("nerd_mono.ttf", "nerd_mono_semibold.ttf")) {
            val source = File(ttfDir, name)
            if (!source.isFile) continue
            val target = File(root, "$FONT_DIR/$name")
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }

    private fun writeUiFont(root: File, images: Map<String, BufferedImage>): Map<Int, Double> {
        val (bitmaps, advances) = uiBitmapProviders(images)
        write(
            root,
            "$FONT_DIR/${Glyphs.FONT_UI}.json",
            fontJson(listOf(ttfProvider("nerd_mono.ttf")) + bitmaps),
        )
        write(
            root,
            "$FONT_DIR/${Glyphs.FONT_UI_SEMIBOLD}.json",
            fontJson(listOf(ttfProvider("nerd_mono_semibold.ttf")) + bitmaps),
        )
        return advances
    }

    private fun uiBitmapProviders(
        images: Map<String, BufferedImage>,
    ): Pair<List<String>, Map<Int, Double>> {
        val providers = mutableListOf<String>()
        val advances = linkedMapOf<Int, Double>()

        fun add(texture: String, ascent: Int, height: Int, codepoint: Int) {
            providers += bitmapProvider(texture, ascent, height, codepoint)
            images[texture]?.let { advances[codepoint] = FontMetricsBuilder.bitmapAdvance(it, height) }
        }

        for ((codepoint, texture) in Glyphs.SHAPE_TEXTURES) {
            add("shadr/$texture.png", ascent = 64, height = 64, codepoint = codepoint)
        }
        Glyphs.RADII.forEachIndexed { radiusIndex, radiusName ->
            Glyphs.CORNERS.forEachIndexed { cornerIndex, cornerName ->
                add(
                    "shadr/rounding/$radiusName/$cornerName.png",
                    ascent = 64, height = 64,
                    codepoint = Glyphs.cornerBase(radiusIndex) + cornerIndex,
                )
            }
        }
        Glyphs.CURSOR_NAMES.forEachIndexed { index, name ->
            add("shadr/cursors/$name.png", 64, 64, Glyphs.CURSOR_BASE + index)
        }
        add("shadr/spaces.png", NEGATIVE_SPACE_ASCENT, NEGATIVE_SPACE_HEIGHT, Glyphs.NEGATIVE_SPACE.code)
        advances[Glyphs.NEGATIVE_SPACE.code] = NEGATIVE_SPACE_ADVANCE
        return providers to advances
    }

    private fun writeHeadFonts(root: File) {
        Glyphs.HEAD_ASCENTS.forEachIndexed { index, ascent ->
            val providers = listOf(
                bitmapProvider("shadr/head_pixel.png", ascent = ascent, height = 8, Glyphs.HEAD_PIXEL.code),
                bitmapProvider(
                    "shadr/spaces.png",
                    ascent = NEGATIVE_SPACE_ASCENT,
                    height = NEGATIVE_SPACE_HEIGHT,
                    codepoint = Glyphs.HEAD_JOIN.code,
                ),
            )
            write(root, "$FONT_DIR/${Glyphs.headFont(index + 1)}.json", fontJson(providers))
        }
    }

    private val RESERVED_SKIP: String = Glyphs.RESERVED.joinToString("") { escape(it) }

    private fun ttfProvider(file: String) = """
        {"type": "ttf", "file": "minecraft:$file", "shift": [0, 0], "size": 11.0, "oversample": 20.0, "skip": "$RESERVED_SKIP"}
    """.trimIndent()

    private fun bitmapProvider(texture: String, ascent: Int, height: Int, codepoint: Int) = """
        {"type": "bitmap", "file": "minecraft:font/$texture", "ascent": $ascent, "height": $height, "chars": ["${escape(codepoint)}"]}
    """.trimIndent()

    fun fontJson(providers: List<String>) =
        providers.joinToString(",\n    ", prefix = "{\n  \"providers\": [\n    ", postfix = "\n  ]\n}\n")

    fun escape(codepoint: Int): String = "\\u%04X".format(codepoint)

    private fun write(root: File, rel: String, text: String) {
        val file = File(root, rel)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private const val MSDF_CELL = 64

    private const val MSDF_SPREAD = 4.0

    private const val CORNER_SOURCE_SIZE = 256
    private const val CURSOR_SIZE = 16

    private const val NEGATIVE_SPACE_ASCENT = -5000
    private const val NEGATIVE_SPACE_HEIGHT = -3

    private const val NEGATIVE_SPACE_ADVANCE = -2.0

    /** Latin-1 plus the punctuation the shipped pages use; the reserved range is bitmap-only. */
    private val TTF_CODEPOINTS: List<Int> =
        ((0x20..0x7E) + (0xA0..0x24F) + (0x2000..0x206F) + (0x20A0..0x20BF) + (0x2190..0x21FF)).toList()
}
