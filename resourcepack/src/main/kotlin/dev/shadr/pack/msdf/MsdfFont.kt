/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack.msdf

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil

/**
 * Bakes a TTF into an MSDF atlas in the grid layout Minecraft's `bitmap` font provider
 * expects, plus the provider JSON that binds it.
 *
 * Why bother, when Minecraft can load the TTF directly? Because a `ttf` provider rasterises
 * once at a fixed size, and shadr then scales that bitmap up by 40x or more through a
 * display entity's transform. `oversample` buys headroom but not scale independence, and
 * large text goes soft. A distance field is resolution-independent by construction: the
 * fragment shader reconstructs the outline at whatever size the quad lands on screen.
 *
 * The grid layout is Minecraft's constraint, not a choice: a `bitmap` provider divides its
 * image into equal cells, one per character in `chars`. So every glyph gets the same cell
 * and is positioned inside it by its own metrics.
 */
class MsdfFont(
    private val ttf: File,
    /** Texel size of one grid cell. */
    private val cell: Int = 64,
    /** Distance range in texels, mapped across the field's 0..1 range. */
    private val spread: Double = 6.0,
    private val columns: Int = 16,
) {
    data class Baked(
        val image: BufferedImage,
        val chars: List<String>,
        /** Em size in texels, so callers can derive `height`/`ascent` for the provider. */
        val emTexels: Double,
        val ascentTexels: Double,
    )

    /**
     * @param charset characters to bake, in the order they will occupy the grid. Order is
     * the atlas layout *and* the provider's `chars` rows, so it must stay stable across
     * rebuilds or every page's text silently remaps.
     */
    fun bake(charset: String = DEFAULT_CHARSET): Baked {
        val base = Font.createFont(Font.TRUETYPE_FONT, ttf)
        val context = FontRenderContext(null, true, true)

        // Fit the em box inside the cell with room for the field to spread past the outline.
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

            // Glyph outlines come out in baseline-relative, y-up coordinates. Flip to y-down
            // texel space and sit the baseline at the cell's ascent line, so every cell
            // shares one baseline and Minecraft's own layout lines the row up correctly.
            val transform = AffineTransform().apply {
                translate(padding.toDouble(), padding + ascent)
            }

            val field = Msdf.render(outline, cell, cell, transform, spread)
            image.setRGB(column * cell, row * cell, cell, cell, field, 0, cell)
        }

        return Baked(
            image = image,
            chars = glyphs.chunked(columns).map { chunk ->
                // Pad the final row so every row is the same length; the provider requires it.
                chunk.joinToString("").padEnd(columns, PADDING_CHAR)
            },
            emTexels = usable,
            ascentTexels = ascent,
        )
    }

    /**
     * Write the atlas and return the provider entry.
     *
     * @param emPixels the size the em box should render at, in game pixels. Matching the
     * TTF provider's `size` is what makes this font a drop-in swap for it.
     *
     * `height` in the provider sizes the whole *cell*, not the em box, and the cell also
     * holds the field's padding on all four sides. Deriving it here rather than hardcoding
     * it means changing [cell] or [spread] cannot silently resize every page's text.
     */
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

        // Ascent is where the baseline sits within the rendered cell, in the same units.
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
        /**
         * Printable ASCII. Deliberately not the full Unicode plane: every character costs a
         * full cell whether or not a page uses it, and a UI's text is overwhelmingly Latin.
         * Anything outside this set falls through to the TTF provider behind it in the font.
         */
        const val DEFAULT_CHARSET: String =
            " !\"#$%&'()*+,-./0123456789:;<=>?@" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`" +
                "abcdefghijklmnopqrstuvwxyz{|}~"

        /**
         * Match the TTF provider's `size: 11.0`, so `shadr_sharp` and `shadr` render at the
         * same visual size and an element can switch between them without moving.
         */
        const val DEFAULT_EM_PIXELS = 11.0

        /** Rasterisation probe size; large enough that metrics are stable, then scaled. */
        private const val PROBE_SIZE = 128f

        /**
         * Fills the tail of the last grid row so every row is the same length, which the
         * provider requires. U+0000 is Minecraft's "no glyph here" cell, and padding with a
         * space instead would map a second, empty cell onto the space character.
         */
        private const val PADDING_CHAR = '\u0000'
    }
}
