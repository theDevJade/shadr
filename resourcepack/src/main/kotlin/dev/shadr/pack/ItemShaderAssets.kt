/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.shader.ShaderDef
import dev.shadr.core.shader.ShaderRegistry
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object ItemShaderAssets {
    const val GRID = dev.shadr.core.shader.GlslComposer.GRID

    const val POSITION_ALPHA = dev.shadr.core.shader.GlslComposer.POSITION_ALPHA

    private const val TEXTURE_DIR = "assets/minecraft/textures/item/shadr"
    private const val MODEL_DIR = "assets/minecraft/models/item/shadr"
    private const val ITEMS_DIR = "assets/minecraft/items/shadr"

    private const val BASE_ITEM = "leather_horse_armor"

    fun writeAll(root: File, registry: ShaderRegistry) {
        if (registry.isEmpty) return
        for (shader in registry.shaders) {
            writeMarkerTexture(root, shader)
            writeModel(root, shader)
            writeItemDefinition(root, shader)
        }
    }

    fun markerTexture(alpha: Int, index: Int): BufferedImage {
        val image = BufferedImage(GRID, GRID * 2, BufferedImage.TYPE_INT_ARGB)

        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                val u = ((x + 0.5) / GRID * 255.0).toInt().coerceIn(0, 255)
                val v = ((y + 0.5) / GRID * 255.0).toInt().coerceIn(0, 255)
                image.setRGB(x, y, argb(alpha, u, v, index))
                image.setRGB(
                    x, y + GRID,
                    argb(ShaderDef.MARKER_A, ShaderDef.MARKER_R, ShaderDef.MARKER_G, index),
                )
            }
        }
        return image
    }

    private fun writeMarkerTexture(root: File, shader: ShaderDef) {
        val file = File(root, "$TEXTURE_DIR/shader_${shader.id}.png")
        file.parentFile.mkdirs()
        ImageIO.write(markerTexture(POSITION_ALPHA, shader.index), "PNG", file)
    }

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

    fun writeBlankMarkers(root: File, overlayDirectory: String, registry: ShaderRegistry) {
        if (registry.isEmpty) return
        val blank = BufferedImage(GRID, GRID * 2, BufferedImage.TYPE_INT_ARGB)
        for (shader in registry.shaders) {
            val file = File(root, "$overlayDirectory/$TEXTURE_DIR/shader_${shader.id}.png")
            file.parentFile.mkdirs()
            ImageIO.write(blank, "PNG", file)
        }
    }

    const val ITEM = "minecraft:$BASE_ITEM"

    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun fmt(value: Double) =
        if (value == value.toInt().toDouble()) "${value.toInt()}" else String.format("%.4f", value)
}
