/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack

import dev.shadr.core.shader.ShaderDef
import dev.shadr.core.shader.ShaderRegistry
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The item-model half of the custom shader path.
 *
 * ## How a shader reaches the GPU
 *
 * There is no per-item shader in Minecraft: `core/item` draws every item there is. So the
 * pack overrides that one program, and each shader element carries a **marker texture**: a
 * texel whose exact bytes say "this is not an item, it is shader N".
 *
 * The trick is Arkane's. Two details of it are not optional and both were learned the hard
 * way there:
 *
 *  - **`texelFetch`, and `round(x * 255)`.** Filtering blends in whatever texel is next door,
 *    and `1/255` does not survive a round trip through a float. Multiplied back up it lands
 *    a hair under 1.0, which truncation turns into 0 and the comparison fails everywhere.
 *  - **The UV must point at a texel's middle.** A vertex sitting exactly on a sprite boundary
 *    reads the first texel of whichever sprite the stitcher put next door. Land on a marker
 *    that way and one vertex claims the whole quad, because the index is `flat`.
 *
 * ## What shadr does differently
 *
 * Arkane's markers are one texel and the geometry is thrown away for a raymarched billboard.
 * shadr's shader items are already HUD quads placed by `make_hud()`, so the quad is kept and
 * used as the canvas. That needs a coordinate *within* the quad, which `texCoord0` cannot
 * give, because it is an atlas coordinate and a fragment has no idea where its sprite starts.
 *
 * The answer is the one [ShapeAssets] already uses here: store the answer in the texture. Each
 * texel carries its own position in the sprite, so the fragment recovers a continuous 0..1
 * coordinate from the texel it landed on plus the sub-texel remainder.
 *
 * ```
 *   R = 0x5D   marker byte 0        G = 0x52   marker byte 1
 *   B = index  which shader         A = 0xFC   marker alpha
 * ```
 * ...in the first row, and below it a [GRID] x [GRID] block where
 * ```
 *   R = u * 255   G = v * 255   B = index   A = 0xFB   (position texel)
 * ```
 */
object ItemShaderAssets {

    /**
     * Resolution of the position grid.
     *
     * Only sets how finely position is quantised before the sub-texel term refines it. The
     * shader itself is evaluated analytically, so this is not the smoothness of the result.
     * 16 gives a texel every 1/16th with 8 bits of sub-texel precision on top, which is finer
     * than a 1080p HUD can show.
     */
    const val GRID = dev.shadr.core.shader.GlslComposer.GRID

    /** Alpha of a position texel, distinct from the marker's own. */
    const val POSITION_ALPHA = dev.shadr.core.shader.GlslComposer.POSITION_ALPHA

    private const val TEXTURE_DIR = "assets/minecraft/textures/item/shadr"
    private const val MODEL_DIR = "assets/minecraft/models/item/shadr"
    private const val ITEMS_DIR = "assets/minecraft/items/shadr"

    /**
     * The item a shader rides.
     *
     * Dyeable, so `dyed_color` carries the element's colour to the shader as the one
     * per-instance parameter. A marker addresses a program, not a call site, so everything
     * else is baked. Its vanilla behaviour is preserved by the definition's fallback.
     */
    private const val BASE_ITEM = "leather_horse_armor"

    fun writeAll(root: File, registry: ShaderRegistry) {
        if (registry.isEmpty) return
        for (shader in registry.shaders) {
            writeMarkerTexture(root, shader)
            writeModel(root, shader)
            writeItemDefinition(root, shader)
        }
    }

    /**
     * The marker sprite: one marker texel and a grid of position texels beneath it.
     *
     * Everything outside those is left fully transparent, so a client without the pack, or one
     * whose shader failed to load, draws nothing rather than a stray coloured square.
     * That is the same property Arkane gets from a zero-area face, arrived at differently
     * because shadr needs the quad to have area.
     */
    private fun writeMarkerTexture(root: File, shader: ShaderDef) {
        // 16 x 32, and both halves matter.
        //
        // Power-of-two because the block atlas mipmaps its sprites: a 17-row texture is not
        // divisible down the mip chain, and the stitcher's response is to drop mip levels or
        // the sprite itself, silently, which is exactly the failure that is impossible to
        // debug from a client that logs nothing.
        //
        //   rows  0..15   position grid  (u, v, index, POSITION_ALPHA)
        //   rows 16..31   signature      (0x5D, 0x52, index, MARKER_A)
        //
        // The model's UV covers the top half only. The signature is never sampled by the UV,
        // since the fragment fetches it by offset, and it exists because `core/item` draws every
        // item in the game. One byte of alpha is not enough to tell a shader apart from a
        // sword's texture that happens to contain it; four bytes across two texels is.
        val image = BufferedImage(GRID, GRID * 2, BufferedImage.TYPE_INT_ARGB)

        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                // Texel centres, so a value is the middle of the cell it stands for rather
                // than its leading edge. Otherwise the last cell can never reach 1.0.
                val u = ((x + 0.5) / GRID * 255.0).toInt().coerceIn(0, 255)
                val v = ((y + 0.5) / GRID * 255.0).toInt().coerceIn(0, 255)
                image.setRGB(x, y, argb(POSITION_ALPHA, u, v, shader.index))
                image.setRGB(
                    x, y + GRID,
                    argb(ShaderDef.MARKER_A, ShaderDef.MARKER_R, ShaderDef.MARKER_G, shader.index),
                )
            }
        }

        val file = File(root, "$TEXTURE_DIR/shader_${shader.id}.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "PNG", file)
        // Deliberately no `.mcmeta`. Neither working reference for this technique ships one,
        // and an unrecognised field in it drops the sprite rather than being ignored.
    }

    /**
     * A flat quad, built exactly as [ShapeAssets] builds its own, which is the one version of
     * this that is known to render in this codebase.
     *
     * No `parent`: inheriting `block/block` brings display transforms meant for blocks. No
     * `item/generated` either, since that extrudes the alpha silhouette into a shaded slab.
     * A thin two-sided box rather than a zero-thickness face, so it is visible from behind and
     * so the loader has a volume to work with.
     *
     * The UV spans the **top half** of the sprite: the bottom half is the signature block and
     * must never be sampled as position.
     */
    private fun writeModel(root: File, shader: ShaderDef) {
        val texture = "minecraft:item/shadr/shader_${shader.id}"
        val model = """
            |{
            |  "textures": { "0": "$texture", "particle": "$texture" },
            |  "elements": [
            |    {
            |      "from": [0, 0, 7.5],
            |      "to": [16, 16, 8.5],
            |      "faces": {
            |        "north": { "uv": [0, 0, 16, 8], "texture": "#0", "tintindex": 0 },
            |        "south": { "uv": [0, 0, 16, 8], "texture": "#0", "tintindex": 0 }
            |      }
            |    }
            |  ]
            |}
            |
        """.trimMargin()
        File(root, "$MODEL_DIR/shader_${shader.id}.json").apply {
            parentFile.mkdirs()
            writeText(model)
        }
    }

    /**
     * The item definition `item_model` selects, with `minecraft:dye` supplying the tint.
     *
     * `"type": "model"` unprefixed, matching both working references. The namespaced form is
     * accepted too, but there is no reason to differ from the version known to load.
     */
    private fun writeItemDefinition(root: File, shader: ShaderDef) {
        val definition = """
            |{
            |  "model": {
            |    "type": "model",
            |    "model": "minecraft:item/shadr/shader_${shader.id}",
            |    "tints": [ { "type": "minecraft:dye", "default": -1 } ]
            |  }
            |}
            |
        """.trimMargin()
        File(root, "$ITEMS_DIR/shader_${shader.id}.json").apply {
            parentFile.mkdirs()
            writeText(definition)
        }
    }

    /**
     * Blanks every marker sprite inside one overlay, for clients that cannot run the shader.
     *
     * The marker texture is a *parameter carrier*, not art, and it is nearly opaque by
     * construction: the position grid the model's UV covers is alpha [POSITION_ALPHA], 251 of
     * 255, because a marker that a filter could blend away is a marker that fails to decode.
     * That is fine on a client whose overlay ships `core/item.fsh`, which reads the texel and
     * never lets it reach the framebuffer. On any other client the vanilla fragment program
     * samples it as an ordinary sprite, and a `type: shader` element renders as a *visible
     * red-green gradient square* rather than as nothing.
     *
     * Overlays are whole pack directories, not just shader trees, so the cheapest correct
     * answer is to layer a fully transparent sprite of the same size over the shared one for
     * exactly those clients. They then draw nothing, which is what a client that cannot run
     * the effect should do.
     *
     * Sized [GRID] x [GRID] * 2 to match the real sprite. A different size would re-stitch the
     * atlas differently for those clients and, worse, a non-power-of-two would drop the sprite
     * silently, the failure this whole file is written around.
     */
    fun writeBlankMarkers(root: File, overlayDirectory: String, registry: ShaderRegistry) {
        if (registry.isEmpty) return
        val blank = BufferedImage(GRID, GRID * 2, BufferedImage.TYPE_INT_ARGB)
        for (shader in registry.shaders) {
            val file = File(root, "$overlayDirectory/$TEXTURE_DIR/shader_${shader.id}.png")
            file.parentFile.mkdirs()
            ImageIO.write(blank, "PNG", file)
        }
    }

    /** The item every shader element carries. Exposed so the renderer and pack agree. */
    const val ITEM = "minecraft:$BASE_ITEM"

    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun fmt(value: Double) =
        if (value == value.toInt().toDouble()) "${value.toInt()}" else String.format("%.4f", value)
}
