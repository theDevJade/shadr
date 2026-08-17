/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack

import dev.shadr.core.text.Glyphs
import dev.shadr.pack.msdf.MsdfFont
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Generates every bitmap the UI font needs, plus the font JSONs that bind them to
 * codepoints.
 *
 * The shapes are *drawn*, not shipped as art files, for two reasons: they are exactly the
 * kind of geometry a rasteriser produces better than a human (anti-aliased quarter-discs
 * at four radii, eight cursor silhouettes), and generating them keeps the repo free of
 * binary blobs that drift out of sync with [Glyphs].
 *
 * Everything renders white so the client can tint it: colour comes from the MiniMessage
 * `<#rrggbb>` span on the element, never from the texture.
 */
object FontAssets {

    private const val FONT_DIR = "assets/minecraft/font"
    private const val TEXTURE_DIR = "assets/minecraft/textures/font/shadr"

    fun writeAll(root: File, ttfDir: File) {
        writeTextures(root)
        writeTtf(root, ttfDir)
        writeUiFont(root)
        writeSharpFont(root, ttfDir)
        writeHeadFonts(root)
    }

    /**
     * `font/shadr_sharp.json`: the same typeface as an MSDF atlas.
     *
     * Shipped alongside the plain TTF font rather than replacing it, for two reasons. It is
     * opt-in per element (`font: shadr_sharp`), so the sharp path can be compared against
     * the soft one on a live client instead of on trust. And because the atlas carries
     * ordinary coverage in its alpha channel, it renders correctly as a normal bitmap font
     * even with no fragment-shader override loaded. It only gets *sharp* once the shader
     * lands, so shipping it early cannot break anything.
     */
    private fun writeSharpFont(root: File, ttfDir: File) {
        // Both weights, so `shadr_sharp*` is a drop-in swap for `shadr*` rather than a
        // choice between sharpness and weight.
        writeSharpWeight(root, File(ttfDir, "nerd_mono.ttf"), "msdf_nerd_mono", Glyphs.FONT_UI_SHARP)
        writeSharpWeight(
            root, File(ttfDir, "nerd_mono_semibold.ttf"), "msdf_nerd_mono_semibold",
            Glyphs.FONT_UI_SHARP_SEMIBOLD,
        )
    }

    private fun writeSharpWeight(root: File, ttf: File, textureName: String, fontKey: String) {
        if (!ttf.isFile) return
        val provider = MsdfFont(ttf, cell = MSDF_CELL, spread = MSDF_SPREAD).write(
            packRoot = root,
            texturePath = "font/shadr/$textureName.png",
            codepointStart = 0,
        )
        write(root, "$FONT_DIR/$fontKey.json", fontJson(listOf(provider)))
    }

    private fun writeTextures(root: File) {
        // The universal fill quad. 1x1 white: the display entity's scale does all the
        // sizing, so a larger source would only cost memory and blur under filtering.
        png(root, "$TEXTURE_DIR/background.png", solid(1, 1, Color.WHITE))

        // Soft-rounded fills, for corners too small for four separate corner draws.
        png(root, "$TEXTURE_DIR/rounded.png", roundedSquare(26, 4.0))
        png(root, "$TEXTURE_DIR/rounded2.png", roundedSquare(26, 7.0))
        png(root, "$TEXTURE_DIR/rounded3.png", roundedSquare(26, 11.0))

        png(root, "$TEXTURE_DIR/gradient.png", verticalGradient(512, 512))
        png(root, "$TEXTURE_DIR/slider.png", sliderTrack(64))
        // Generated large and downsampled by the client: an ellipse is all edge, and a small
        // source shows its stair-steps the moment a page scales it up.
        png(root, "$TEXTURE_DIR/circle.png", filledEllipse(512))

        // A fully transparent tile. Paired with a negative `height` in the font JSON it
        // becomes a negative-advance space, which is how stacked rows return to column 0.
        png(root, "$TEXTURE_DIR/spaces.png", transparent(256, 256))

        Glyphs.RADII.forEachIndexed { radiusIndex, radiusName ->
            val size = CORNER_SOURCE_SIZE
            Glyphs.CORNERS.indices.forEach { corner ->
                png(root, "$TEXTURE_DIR/rounding/$radiusName/${corner + 1}.png", quarterDisc(size, corner))
            }
            check(radiusIndex in Glyphs.RADII.indices)
        }

        Glyphs.CURSOR_NAMES.forEachIndexed { index, name ->
            png(root, "$TEXTURE_DIR/cursors/$name.png", cursor(index))
        }

        // One white pixel, used 64 times per player head. Separate from background.png so
        // the head fonts can carry their own ascent ladder without disturbing the UI font.
        png(root, "$TEXTURE_DIR/head_pixel.png", solid(1, 1, Color.WHITE))
    }

    private fun solid(width: Int, height: Int, color: Color) = image(width, height) { g ->
        g.color = color
        g.fillRect(0, 0, width, height)
    }

    private fun transparent(width: Int, height: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

    /** A filled square with all four corners rounded by [radius] source pixels. */
    private fun roundedSquare(size: Int, radius: Double) = image(size, size) { g ->
        g.color = Color.WHITE
        g.fillRoundRect(0, 0, size, size, (radius * 2).toInt(), (radius * 2).toInt())
    }

    /**
     * One corner of a disc, filling the square *except* the outside of the arc.
     *
     * Corner index follows [Glyphs.CORNERS]: 0=top-left, 1=top-right, 2=bottom-right,
     * 3=bottom-left. Drawn as a full circle clipped to the relevant quadrant, so the arc
     * is a true quarter-circle and butts flush against the inset fill rectangles.
     */
    private fun quarterDisc(size: Int, corner: Int) = image(size, size) { g ->
        val d = size * 2.0
        val (ox, oy) = when (corner) {
            0 -> 0.0 to 0.0                    // top-left: circle centre at bottom-right
            1 -> -size.toDouble() to 0.0       // top-right
            2 -> -size.toDouble() to -size.toDouble()
            else -> 0.0 to -size.toDouble()    // bottom-left
        }
        g.color = Color.WHITE
        g.fill(Ellipse2D.Double(ox, oy, d, d))
    }

    /** Opaque white fading to transparent downward; tinted per-element at draw time. */
    private fun verticalGradient(width: Int, height: Int): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            val alpha = (255.0 * (1.0 - y.toDouble() / (height - 1))).toInt().coerceIn(0, 255)
            val argb = (alpha shl 24) or 0xFFFFFF
            for (x in 0 until width) img.setRGB(x, y, argb)
        }
        return img
    }

    /** A filled disc that fills its tile, so scaling the quad gives an exact ellipse. */
    private fun filledEllipse(size: Int) = image(size, size) { g ->
        g.color = Color.WHITE
        g.fillOval(0, 0, size, size)
    }

    /** A centred capsule: a slider groove that stays crisp when stretched horizontally. */
    private fun sliderTrack(size: Int) = image(size, size) { g ->
        val thickness = size / 4
        g.color = Color.WHITE
        g.fillRoundRect(0, (size - thickness) / 2, size, thickness, thickness, thickness)
    }

    /**
     * The eight software cursors. Each is a white silhouette with a 1px dark outline, so
     * it stays legible over both a dark panel and a bright screenshot.
     */
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

    /**
     * `font/shadr.json`: the one font every element uses by default.
     *
     * The TTF provider comes first so real text falls through to the typeface, and the
     * bitmap providers claim the private-use range.
     *
     * That ordering is load-bearing rather than cosmetic, and more so since the UI font
     * became a Nerd Font. `FontManager` *reverses* a font's provider list before handing it
     * to `FontSet`, which then resolves a codepoint to the first provider that supplies it,
     * so later in this list means higher priority. It is no longer the only defence, though:
     * Nerd Fonts pack thousands of icons into U+E000-U+F8FF, which is exactly where shadr's
     * shapes, corners and cursors live, and relying on provider precedence alone left a
     * `block` drawing a Pomicon instead of a box. [Glyphs.RESERVED] is withheld from the
     * typeface via `skip`, so a collision cannot arise whichever provider wins.
     *
     * Both weights get the bitmaps. Semibold once carried the TTF alone, which left every
     * private-use glyph drawn in `font: shadr_semibold` resolving to whatever the typeface
     * happened to put at that codepoint.
     *
     * `oversample: 20` is what keeps 11pt text sharp after the display entity scales it up;
     * without it the client rasterises at 11px and the UI looks like a screenshot of a
     * screenshot.
     */
    private fun writeUiFont(root: File) {
        val bitmaps = uiBitmapProviders()
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
    }

    /** The private-use bitmaps, identical for every weight of the UI font. */
    private fun uiBitmapProviders(): List<String> {
        val providers = mutableListOf<String>()
        for ((codepoint, texture) in Glyphs.SHAPE_TEXTURES) {
            providers += bitmapProvider("shadr/$texture.png", ascent = 64, height = 64, codepoint)
        }
        Glyphs.RADII.forEachIndexed { radiusIndex, radiusName ->
            Glyphs.CORNERS.forEachIndexed { cornerIndex, cornerName ->
                providers += bitmapProvider(
                    "shadr/rounding/$radiusName/$cornerName.png",
                    ascent = 64, height = 64,
                    codepoint = Glyphs.cornerBase(radiusIndex) + cornerIndex,
                )
            }
        }
        Glyphs.CURSOR_NAMES.forEachIndexed { index, name ->
            providers += bitmapProvider("shadr/cursors/$name.png", 64, 64, Glyphs.CURSOR_BASE + index)
        }
        providers += bitmapProvider("shadr/spaces.png", NEGATIVE_SPACE_ASCENT, NEGATIVE_SPACE_HEIGHT, Glyphs.NEGATIVE_SPACE.code)
        return providers
    }

    /**
     * `head_1.json` .. `head_8.json`: eight fonts identical but for `ascent`.
     *
     * A player head is an 8x8 grid of pixel glyphs. There is no way to move the pen
     * vertically inside one line of text, so each *row* of the head is drawn from a
     * different font whose ascent is 18px lower than the last. Pair that with the
     * negative-advance space to return to column 0 and an 8x8 image renders inside a
     * single line.
     */
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

    /**
     * The codepoints withheld from every TTF provider: exactly the range [Glyphs.RESERVED]
     * claims, so the typeface cannot supply a glyph shadr means to draw itself.
     */
    private val RESERVED_SKIP: String = Glyphs.RESERVED.joinToString("") { escape(it) }

    private fun ttfProvider(file: String) = """
        {"type": "ttf", "file": "minecraft:$file", "shift": [0, 0], "size": 11.0, "oversample": 20.0, "skip": "$RESERVED_SKIP"}
    """.trimIndent()

    private fun bitmapProvider(texture: String, ascent: Int, height: Int, codepoint: Int) = """
        {"type": "bitmap", "file": "minecraft:font/$texture", "ascent": $ascent, "height": $height, "chars": ["${escape(codepoint)}"]}
    """.trimIndent()

    fun fontJson(providers: List<String>) =
        providers.joinToString(",\n    ", prefix = "{\n  \"providers\": [\n    ", postfix = "\n  ]\n}\n")

    /** JSON needs the codepoint escaped; a raw private-use char would not survive editing. */
    fun escape(codepoint: Int): String = "\\u%04X".format(codepoint)

    private fun write(root: File, rel: String, text: String) {
        val file = File(root, rel)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    /**
     * Atlas cell size, and through it how many texels the em box gets.
     *
     * This is the number that decides whether fine features survive. Multi-channel fields
     * want 32-64 texels per em; below that a stem is only a texel or two wide, every sample
     * lands within half a texel of an outline, and the median never leaves the edge band, so
     * letters render as grey ghosts with stray marks in the gaps.
     *
     * It is deliberately decoupled from the rendered height. A coverage sheet has to be
     * 1:1 because scaling it resamples pixels; a field does not, so the cell can be large
     * for resolution while the text still draws at 11px.
     */
    private const val MSDF_CELL = 64

    /**
     * Distance range in texels, and the single knob that decides whether strokes look
     * solid or washed out.
     *
     * A wide range antialiases further from an edge, but a feature narrower than the range
     * never resolves to fully-outside, so counters and the troughs of a W fill with grey
     * haze. It is also the letter-spacing: alpha marks everything within this distance of
     * the outline, and Minecraft derives a glyph's advance from its marked extent.
     *
     * Must equal SHADR_FIELD_RANGE in hud_fragment.glsl, and a test enforces it.
     */
    private const val MSDF_SPREAD = 4.0

    /**
     * Corner art is generated large and scaled down by the display entity.
     *
     * Minecraft samples font atlases with nearest filtering, so the source resolution *is* the
     * quality of the arc: at 26px a quarter-disc has visible stair-steps at any radius a page
     * would actually use, and antialiasing at that size only smears them. The four radius
     * folders hold byte-identical art, because the radius is applied by scaling the quad
     * rather than by picking different pictures, so this costs four textures and not sixteen.
     */
    private const val CORNER_SOURCE_SIZE = 256
    private const val CURSOR_SIZE = 16

    /**
     * A glyph with a hugely negative ascent renders off-screen, and a negative height
     * makes its advance negative. Together they are a pen carriage return.
     */
    private const val NEGATIVE_SPACE_ASCENT = -5000
    private const val NEGATIVE_SPACE_HEIGHT = -3
}
