/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.hud.ShapeBuckets
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object ShapeAssets {
    private const val BASE_ITEM = "leather_horse_armor"

    fun writeAll(root: File) {
        for (bucket in 0 until ShapeBuckets.COUNT) {
            writeParameterTexture(root, bucket)
            writeModel(root, bucket)
        }
        writeItemDefinition(root)
    }

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

    const val GRID = 64

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
