/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object CelestialAssets {
    const val GRID = 64

    const val MARKER_A = 0xFA

    const val ID_SUN = 1
    const val ID_MOON = 2

    private const val DIR = "assets/minecraft/textures/environment/celestial"

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
