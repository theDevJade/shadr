/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.video.MosaicClip
import dev.shadr.core.video.MosaicFormat
import dev.shadr.core.video.VideoClip
import dev.shadr.core.video.VideoFormat
import dev.shadr.core.video.VideoRegistry
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object VideoAssets {

    private const val TEXTURE_DIR = "assets/minecraft/textures/item/shadr"
    private const val MODEL_DIR = "assets/minecraft/models/item/shadr"
    private const val ITEMS_DIR = "assets/minecraft/items/shadr"
    private const val DATA_DIR = "assets/minecraft/textures/effect/shadr/video"

    data class Source(
        val clip: VideoClip,
        val mosaic: MosaicClip?,
        val audio: ByteArray? = null,
        val streamed: Boolean = false,
    )

    fun writeAll(root: File, sources: List<Source>) {
        for (source in sources) {
            writeMarkerTexture(root, source.clip)
            writeModel(root, source.clip)
            writeItemDefinition(root, source.clip)
            if (source.mosaic != null) writeData(root, source)
            writeAudio(root, source)
        }
    }

    fun soundPath(clip: VideoClip): String = "shadr/video/" + clip.id

    private fun writeAudio(root: File, source: Source) {
        val audio = source.audio ?: return
        val file = File(root, "assets/minecraft/sounds/" + soundPath(source.clip) + ".ogg")
        file.parentFile.mkdirs()
        file.writeBytes(audio)
    }

    fun dataRows(mosaic: MosaicClip): Int =
        (mosaic.texelCount + MosaicFormat.SHEET_EDGE - 1) / MosaicFormat.SHEET_EDGE

    private fun writeData(root: File, source: Source) {
        val mosaic = source.mosaic ?: return
        val rows = dataRows(mosaic)
        val image = BufferedImage(MosaicFormat.SHEET_EDGE, rows, BufferedImage.TYPE_INT_ARGB)

        val row = IntArray(MosaicFormat.SHEET_EDGE)
        for (y in 0 until rows) {
            val base = y * MosaicFormat.SHEET_EDGE
            for (x in 0 until MosaicFormat.SHEET_EDGE) {
                val at = base + x
                row[x] = if (at < mosaic.texelCount) mosaic.data[at] else 0
            }
            image.setRGB(0, y, MosaicFormat.SHEET_EDGE, 1, row, 0, MosaicFormat.SHEET_EDGE)
        }

        val file = File(root, "$DATA_DIR/${source.clip.id}.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "PNG", file)

        File(root, "$DATA_DIR/${source.clip.id}.png.mcmeta").writeText(
            """
            |{
            |  "texture": { "blur": false, "clamp": true }
            |}
            |
            """.trimMargin(),
        )
    }

    private fun writeMarkerTexture(root: File, clip: VideoClip) {
        val file = File(root, "$TEXTURE_DIR/video_${clip.id}.png")
        file.parentFile.mkdirs()
        ImageIO.write(
            ItemShaderAssets.markerTexture(VideoFormat.MARKER_ALPHA, clip.index),
            "PNG",
            file,
        )
    }

    private fun writeModel(root: File, clip: VideoClip) {
        val texture = "minecraft:item/shadr/video_${clip.id}"
        File(root, "$MODEL_DIR/video_${clip.id}.json").apply {
            parentFile.mkdirs()
            writeText(
                """
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
                """.trimMargin(),
            )
        }
    }

    private fun writeItemDefinition(root: File, clip: VideoClip) {
        File(root, "$ITEMS_DIR/video_${clip.id}.json").apply {
            parentFile.mkdirs()
            writeText(
                """
                |{
                |  "model": {
                |    "type": "model",
                |    "model": "minecraft:item/shadr/video_${clip.id}",
                |    "tints": [ { "type": "minecraft:dye", "default": -1 } ]
                |  }
                |}
                |
                """.trimMargin(),
            )
        }
    }

    fun writeBlankMarkers(root: File, overlayDirectory: String, registry: VideoRegistry) {
        if (registry.isEmpty) return
        val blank = BufferedImage(
            ItemShaderAssets.GRID,
            ItemShaderAssets.GRID * 2,
            BufferedImage.TYPE_INT_ARGB,
        )
        for (clip in registry.clips) {
            val file = File(root, "$overlayDirectory/$TEXTURE_DIR/video_${clip.id}.png")
            file.parentFile.mkdirs()
            ImageIO.write(blank, "PNG", file)
        }
    }
}
