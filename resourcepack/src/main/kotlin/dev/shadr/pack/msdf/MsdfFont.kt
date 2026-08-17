/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack.msdf

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil

class MsdfFont(
    private val ttf: File,
    private val cell: Int = 64,
    private val spread: Double = 6.0,
    private val columns: Int = 16,
) {
    data class Baked(
        val image: BufferedImage,
        val chars: List<String>,
        val emTexels: Double,
        val ascentTexels: Double,
    )

    fun bake(charset: String = DEFAULT_CHARSET): Baked {
        val base = Font.createFont(Font.TRUETYPE_FONT, ttf)
        val context = FontRenderContext(null, true, true)

        val padding = Msdf.paddingFor(spread)
        val usable = (cell - padding * 2).toDouble()
        val probe = base.deriveFont(PROBE_SIZE)
        val metrics = probe.getLineMetrics("Hxy", context)
        val emAtProbe = (metrics.ascent + metrics.descent).toDouble()
        val scale = usable / emAtProbe
        val font = base.deriveFont((PROBE_SIZE * scale).toFloat())
        val scaledMetrics = font.getLineMetrics("Hxy", context)
        val ascent = scaledMetrics.ascent.toDouble()

        val glyphs = charset.toList()
        val rows = ceil(glyphs.size / columns.toDouble()).toInt()
        val image = BufferedImage(columns * cell, rows * cell, BufferedImage.TYPE_INT_ARGB)

        glyphs.forEachIndexed { index, character ->
            val column = index % columns
            val row = index / columns
            val outline = font.createGlyphVector(context, character.toString()).getGlyphOutline(0)

            val transform = AffineTransform().apply {
                translate(padding.toDouble(), padding + ascent)
            }

            val field = Msdf.render(outline, cell, cell, transform, spread)
            image.setRGB(column * cell, row * cell, cell, cell, field, 0, cell)
        }

        return Baked(
            image = image,
            chars = glyphs.chunked(columns).map { chunk ->
                chunk.joinToString("").padEnd(columns, PADDING_CHAR)
            },
            emTexels = usable,
            ascentTexels = ascent,
        )
    }

    fun write(
        packRoot: File,
        texturePath: String,
        codepointStart: Int,
        emPixels: Double = DEFAULT_EM_PIXELS,
        charset: String = DEFAULT_CHARSET,
    ): String {
        val baked = bake(charset)
        val file = File(packRoot, "assets/minecraft/textures/$texturePath")
        file.parentFile.mkdirs()
        ImageIO.write(baked.image, "png", file)

        val renderedHeight = Math.round(emPixels * cell / baked.emTexels).toInt().coerceAtLeast(1)

        val ascent = (renderedHeight * (baked.ascentTexels + Msdf.paddingFor(spread)) / cell).toInt()
        val rows = baked.chars.joinToString(", ") { row ->
            "\"" + row.map { escapeChar(it) }.joinToString("") + "\""
        }
        return """{"type": "bitmap", "file": "minecraft:$texturePath", "ascent": $ascent, """ +
            """"height": $renderedHeight, "chars": [$rows]}"""
    }

    private fun escapeChar(c: Char): String = when {
        c == '"' || c == '\\' -> "\\$c"
        c.code < 0x20 || c.code > 0x7E -> "\\u%04X".format(c.code)
        else -> c.toString()
    }

    companion object {
        const val DEFAULT_CHARSET: String =
            " !\"#$%&'()*+,-./0123456789:;<=>?@" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`" +
                "abcdefghijklmnopqrstuvwxyz{|}~"

        const val DEFAULT_EM_PIXELS = 11.0

        private const val PROBE_SIZE = 128f

        private const val PADDING_CHAR = '\u0000'
    }
}
