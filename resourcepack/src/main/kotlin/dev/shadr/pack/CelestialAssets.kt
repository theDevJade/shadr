/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Replaces the sun and moon art with parameter textures, so `core/position_tex` can draw them
 * procedurally instead.
 *
 * ## Why this program, and why it is safe
 *
 * `RenderPipelines.CELESTIAL` is the only pipeline in the client that uses `core/position_tex`,
 * verified against the decompiled source. Overriding it therefore affects the sun, the moon and
 * the end flash, and nothing else in the game. That is unusually clean for a core shader.
 *
 * ## Why the textures are replaced too
 *
 * The program alone cannot tell a sun from a moon. Both are drawn by the same pipeline, both
 * translate to `(0, 100, 0)`, and only the scale (30 vs 20) and the rotation differ, neither of which
 * reaches the fragment stage. The sprites live in one stitched atlas
 * (`atlases/celestials.json`, sources `textures/environment/celestial/`), so `texCoord0` is an
 * *atlas* coordinate and a fragment cannot recover which sprite it landed on.
 *
 * So the texture carries the answer, exactly as [ItemShaderAssets] does for shader items: each
 * texel says which body it belongs to and where in that body it sits.
 *
 * ```
 *   R = u * 255      G = v * 255      B = body id      A = 0xFA
 * ```
 *
 * A client without this pack still gets vanilla art, because these files simply replace the
 * vanilla ones, so there is no new path to resolve and nothing to fall back from.
 */
object CelestialAssets {

    /** Sprite resolution. Sun and moon are drawn at most a few hundred pixels across. */
    const val GRID = 64

    /** Alpha marking a texel as one of ours. Distinct from the item shaders' own markers. */
    const val MARKER_A = 0xFA

    const val ID_SUN = 1
    const val ID_MOON = 2

    private const val DIR = "assets/minecraft/textures/environment/celestial"

    /**
     * The eight phases the client stitches. Each is its own sprite in 26.2 (the old single
     * `moon_phases.png` strip is gone) so each needs its own parameter texture.
     */
    private val MOON_PHASES = listOf(
        "full_moon", "waning_gibbous", "third_quarter", "waning_crescent",
        "new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous",
    )

    fun writeAll(root: File) {
        write(root, "$DIR/sun.png", parameterTexture(ID_SUN))
        for (phase in MOON_PHASES) {
            write(root, "$DIR/moon/$phase.png", parameterTexture(ID_MOON))
        }
    }

    /**
     * Every texel carries its own position and which body it belongs to.
     *
     * Position is stored at texel centres so the last cell can reach 1.0, and the fragment
     * refines it with the sub-texel remainder, the same reconstruction the item shaders use,
     * and the reason a 64px sprite can carry a smooth coordinate across a sun drawn large.
     */
    private fun parameterTexture(id: Int): BufferedImage {
        val image = BufferedImage(GRID, GRID, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                val u = ((x + 0.5) / GRID * 255.0).toInt().coerceIn(0, 255)
                val v = ((y + 0.5) / GRID * 255.0).toInt().coerceIn(0, 255)
                image.setRGB(x, y, (MARKER_A shl 24) or (u shl 16) or (v shl 8) or id)
            }
        }
        return image
    }

    private fun write(root: File, rel: String, image: BufferedImage) {
        val file = File(root, rel)
        file.parentFile.mkdirs()
        ImageIO.write(image, "PNG", file)
    }
}
