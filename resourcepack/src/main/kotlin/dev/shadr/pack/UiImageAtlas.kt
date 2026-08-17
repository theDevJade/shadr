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
import java.awt.image.BufferedImage
import java.io.File
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.min

/**
 * Turns arbitrary user PNGs into drawable UI elements.
 *
 * A Minecraft bitmap font glyph is capped in practical size, and an image of any real
 * resolution has to be split. So each source image is tiled into 256px squares, every tile
 * gets its own private-use codepoint, and drawing the image means drawing its tile grid,
 * with rows joined by the negative-advance space so they stack without a newline.
 *
 * Codepoints are assigned in sorted filename order and persisted, so an image keeps its
 * codepoint across rebuilds. If they were reassigned, every page referencing an image
 * would point at whatever tile happened to take its slot.
 */
class UiImageAtlas(
    private val contentsDir: File,
    private val packRoot: File,
    private val stateFile: File,
) {
    data class GeneratedImage(
        val name: String,
        val columns: Int,
        val rows: Int,
        val firstCodepoint: Int,
    ) {
        fun glyphAt(row: Int, column: Int): String? {
            if (row !in 0 until rows || column !in 0 until columns) return null
            return String(Character.toChars(firstCodepoint + row * columns + column))
        }

        /**
         * The full image as one drawable string: each row of tiles, separated by the
         * carriage-return glyph so the next row starts back at column 0.
         */
        fun glyphMatrix(): String = (0 until rows).joinToString(Glyphs.HEAD_JOIN.toString()) { row ->
            (0 until columns).mapNotNull { glyphAt(row, it) }.joinToString("")
        }
    }

    data class BuildResult(val images: Map<String, GeneratedImage>, val issues: List<String>)

    fun rebuild(): BuildResult {
        val sourceDir = File(contentsDir, IMAGES_FOLDER)
        val issues = mutableListOf<String>()
        val sources = sourceDir.takeIf { it.isDirectory }
            ?.listFiles { f -> f.isFile && f.extension.lowercase() in SUPPORTED_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        val assigned = loadAssignments()
        val images = linkedMapOf<String, GeneratedImage>()
        val providers = mutableListOf<String>()
        var nextCodepoint = (assigned.values.maxOrNull()?.plus(1)) ?: Glyphs.IMAGE_ATLAS_START

        for (source in sources) {
            val name = source.nameWithoutExtension
            val image = runCatching { ImageIO.read(source) }.getOrNull()
            if (image == null) {
                issues += "unreadable image: ${source.name}"
                continue
            }
            val columns = ceil(image.width / TILE_SIZE.toDouble()).toInt().coerceAtLeast(1)
            val rows = ceil(image.height / TILE_SIZE.toDouble()).toInt().coerceAtLeast(1)
            val tileCount = columns * rows

            val first = assigned.getOrPut(name) {
                val start = nextCodepoint
                nextCodepoint += tileCount
                start
            }
            if (first + tileCount > Glyphs.IMAGE_ATLAS_END) {
                issues += "image atlas full; skipped ${source.name}"
                continue
            }
            nextCodepoint = maxOf(nextCodepoint, first + tileCount)

            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val tile = crop(image, column * TILE_SIZE, row * TILE_SIZE)
                    val rel = "assets/minecraft/textures/font/images/$name/${row}_$column.png"
                    val out = File(packRoot, rel)
                    out.parentFile.mkdirs()
                    ImageIO.write(tile, "png", out)
                    providers += provider("images/$name/${row}_$column.png", first + row * columns + column)
                }
            }
            images[name] = GeneratedImage(name, columns, rows, first)
        }

        writeFont(providers)
        saveAssignments(assigned)
        return BuildResult(images, issues)
    }

    /** Read back the codepoint map without regenerating, for when only serving. */
    fun loadGenerated(): Map<String, Int> = loadAssignments()

    private fun crop(source: BufferedImage, x: Int, y: Int): BufferedImage {
        val tile = BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB)
        val width = min(TILE_SIZE, source.width - x)
        val height = min(TILE_SIZE, source.height - y)
        if (width > 0 && height > 0) {
            val g = tile.createGraphics()
            g.drawImage(source.getSubimage(x, y, width, height), 0, 0, null)
            g.dispose()
        }
        // Mark the bottom-right pixel with a barely-visible alpha so the client keeps the
        // glyph's full advance width even when the tile's real content is narrower.
        if (tile.getRGB(TILE_SIZE - 1, TILE_SIZE - 1) ushr 24 == 0) {
            tile.setRGB(TILE_SIZE - 1, TILE_SIZE - 1, ADVANCE_MARKER_ALPHA shl 24)
        }
        return tile
    }

    private fun provider(texture: String, codepoint: Int) =
        """{"type": "bitmap", "file": "minecraft:font/$texture", "ascent": $TILE_ASCENT, "height": $TILE_SIZE, """ +
            """"chars": ["${FontAssets.escape(codepoint)}"]}"""

    private fun writeFont(providers: List<String>) {
        val target = File(packRoot, "assets/minecraft/font/${Glyphs.FONT_IMAGES}.json")
        target.parentFile.mkdirs()
        target.writeText(FontAssets.fontJson(providers.ifEmpty { listOf(EMPTY_PROVIDER) }))
    }

    private fun loadAssignments(): MutableMap<String, Int> {
        if (!stateFile.isFile) return linkedMapOf()
        val properties = Properties()
        stateFile.inputStream().use { properties.load(it) }
        return properties.entries
            .mapNotNull { (k, v) -> v.toString().toIntOrNull()?.let { k.toString() to it } }
            .sortedBy { it.second }
            .toMap(linkedMapOf())
    }

    private fun saveAssignments(assigned: Map<String, Int>) {
        val properties = Properties()
        assigned.forEach { (name, codepoint) -> properties[name] = codepoint.toString() }
        stateFile.parentFile?.mkdirs()
        stateFile.outputStream().use { properties.store(it, "shadr UI image codepoints: do not reorder") }
    }

    private companion object {
        const val TILE_SIZE = 256
        const val TILE_ASCENT = 256
        const val IMAGES_FOLDER = "images"
        const val ADVANCE_MARKER_ALPHA = 32
        val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg")

        /** A font file with zero providers fails to load; keep one inert entry. */
        const val EMPTY_PROVIDER =
            """{"type": "space", "advances": {" ": 4}}"""
    }
}
