/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack

import dev.shadr.core.hud.ShapeBuckets
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The item-model half of the SDF shape path.
 *
 * A shape is an item display, and everything that distinguishes one shape from another has
 * to reach the client through the item stack. Two channels carry it:
 *
 *  - **`custom_model_data`** selects which model, and therefore which corner radius.
 *  - **`dyed_color`** carries the colour, read by the model's `minecraft:dye` tint source.
 *
 * The textures are not art. Each texel carries the corner radius plus its own position in
 * the sprite, and the fragment program reconstructs the whole rounded rectangle
 * analytically from those. That is the point of the item-model route: the texture is a
 * parameter carrier, so one flat quad becomes any rounded box at any size and aspect.
 */
object ShapeAssets {

    /** The dyeable item the shapes ride. Its vanilla behaviour is preserved by a fallback. */
    private const val BASE_ITEM = "leather_horse_armor"

    fun writeAll(root: File) {
        // One texture and model per radius bucket. The renderer picks the bucket; this
        // bakes the fraction that bucket promises, so the two cannot disagree.
        for (bucket in 0 until ShapeBuckets.COUNT) {
            writeParameterTexture(root, bucket)
            writeModel(root, bucket)
        }
        writeItemDefinition(root)
    }

    /**
     * A grid of parameters rather than art: red is the corner radius, green and blue are
     * each texel's own position within the sprite.
     *
     * The position channels exist because `texCoord0` reaching the fragment shader is an
     * *atlas* coordinate, and the sprite's offset and extent within the item atlas are unknown
     * to the shader, so it cannot recover where in the quad it is. Storing the answer makes
     * it recoverable: the texel says which cell, and `fract(texCoord0 * atlasSize)` supplies
     * the remainder inside that cell, which together give a continuous 0..1 position.
     *
     * [GRID] therefore sets how finely the *position* is quantised before the sub-texel term
     * refines it, not how smooth the shape is; the shape itself is evaluated analytically.
     */
    private fun writeParameterTexture(root: File, bucket: Int) {
        val fraction = ShapeBuckets.fractionFor(bucket)
        val red = (fraction.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
        val image = BufferedImage(GRID, GRID, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                val green = Math.round(x * 255.0 / (GRID - 1)).toInt()
                val blue = Math.round(y * 255.0 / (GRID - 1)).toInt()
                image.setRGB(x, y, (0xFF shl 24) or (red shl 16) or (green shl 8) or blue)
            }
        }
        write(root, "assets/minecraft/textures/item/shadr/box_$bucket.png", image)
    }

    /** Sprite size of a parameter texture. Must match SHADR_SHAPE_GRID in the shader. */
    const val GRID = 64

    /**
     * A flat quad rather than `item/generated`.
     *
     * `item/generated` extrudes the texture's alpha silhouette into a 3D slab with shaded
     * side faces: correct for a sword, wrong for a UI panel, and it would light the edges
     * differently from the face. An explicit single element keeps the geometry flat and the
     * UVs spanning exactly 0..1, which the shader relies on to know where the quad's edges
     * are.
     *
     * **`tintindex: 0` on both faces is not optional.** The item definition declares a
     * `minecraft:dye` tint source, but a tint source only reaches geometry that asks for it by
     * index, so a face without `tintindex` is drawn at vertex colour white, whatever the stack's
     * `dyed_color` says. Its absence is why every SDF shape rendered white (SURVEY.md §4.3),
     * and it looks like a shader bug rather than a model one because the colour *is* being set
     * on the item; it simply never reaches the quad.
     */
    private fun writeModel(root: File, bucket: Int) {
        val texture = "minecraft:item/shadr/box_$bucket"
        write(
            root, "assets/minecraft/models/item/shadr/box_$bucket.json",
            """
            |{
            |  "textures": { "0": "$texture", "particle": "$texture" },
            |  "elements": [
            |    {
            |      "from": [0, 0, 7.5],
            |      "to": [16, 16, 8.5],
            |      "faces": {
            |        "north": { "uv": [0, 0, 16, 16], "texture": "#0", "tintindex": 0 },
            |        "south": { "uv": [0, 0, 16, 16], "texture": "#0", "tintindex": 0 }
            |      }
            |    }
            |  ]
            |}
            |
            """.trimMargin(),
        )
    }

    /**
     * Redefines the base item to dispatch on `custom_model_data`.
     *
     * The `fallback` is what keeps this safe: an ordinary leather horse armor carries no
     * shadr custom model data, misses every threshold, and renders exactly as vanilla. Only
     * a stack shadr built takes one of the shape models.
     */
    private fun writeItemDefinition(root: File) {
        val entries = (0 until ShapeBuckets.COUNT).joinToString(",\n") { bucket ->
            """
            |      {
            |        "threshold": $bucket,
            |        "model": {
            |          "type": "minecraft:model",
            |          "model": "minecraft:item/shadr/box_$bucket",
            |          "tints": [ { "type": "minecraft:dye", "default": -1 } ]
            |        }
            |      }
            """.trimMargin()
        }
        write(
            root, "assets/minecraft/items/$BASE_ITEM.json",
            """
            |{
            |  "model": {
            |    "type": "minecraft:range_dispatch",
            |    "property": "minecraft:custom_model_data",
            |    "index": 0,
            |    "entries": [
            |$entries
            |    ],
            |    "fallback": {
            |      "type": "minecraft:model",
            |      "model": "minecraft:item/$BASE_ITEM",
            |      "tints": [ { "type": "minecraft:dye", "default": -6265536 } ]
            |    }
            |  }
            |}
            |
            """.trimMargin(),
        )
    }

    /**
     * Blanks every parameter sprite inside one overlay, for clients without `core/item.fsh`.
     *
     * Same reasoning as [ItemShaderAssets.writeBlankMarkers], and the same failure: these
     * textures are fully opaque parameter grids, so a client whose overlay has no item
     * fragment program draws a `block_sdf` panel as a coloured gradient instead of a rounded
     * box. Blanking it there degrades the shape to nothing, which the glyph path
     * (`block_rounded`) then covers for those clients.
     */
    fun writeBlankParameters(root: File, overlayDirectory: String) {
        val blank = BufferedImage(GRID, GRID, BufferedImage.TYPE_INT_ARGB)
        for (bucket in 0 until ShapeBuckets.COUNT) {
            write(root, "$overlayDirectory/assets/minecraft/textures/item/shadr/box_$bucket.png", blank)
        }
    }

    private fun write(root: File, rel: String, text: String) {
        val file = File(root, rel)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun write(root: File, rel: String, image: BufferedImage) {
        val file = File(root, rel)
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }
}
