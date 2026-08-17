/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/** A built pack: the bytes to serve, and the SHA-1 the client verifies them against. */
data class PackArchive(val bytes: ByteArray, val sha1: ByteArray, val file: File?) {
    val sha1Hex: String get() = sha1.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?) = other is PackArchive && sha1.contentEquals(other.sha1)
    override fun hashCode() = sha1.contentHashCode()
}

/**
 * Zips a generated pack directory into the file the client downloads.
 *
 * Two things happen on the way in, both of which have to be here rather than in
 * [PackGenerator], because they depend on what the *server operator* configured rather
 * than on what shadr draws:
 *
 *  - **Vanilla HUD stripping.** A full-screen UI with hearts and a hotbar painted over it
 *    is unusable. There is no "hide the HUD" packet, so the sprites are replaced with a
 *    transparent 1x1 PNG, so the vanilla HUD still renders, into nothing.
 *  - **PNG recompression.** Pack size is the join-time stall a player actually feels.
 */
object PackBuilder {

    private const val HUD_SPRITES_PATH = "assets/minecraft/textures/gui/sprites/hud/"

    /**
     * Every vanilla HUD sprite that would otherwise draw over a shadr page. Enumerated
     * rather than globbed because the pack must only blank what it means to: an
     * over-broad wildcard here would silently break unrelated GUI textures.
     */
    val HUD_SPRITE_FILES: List<String> = buildList {
        addAll(
            listOf(
                "air.png", "air_bursting.png", "air_empty.png",
                "armor_empty.png", "armor_full.png", "armor_half.png",
                "experience_bar_background.png", "experience_bar_progress.png",
                "food_empty.png", "food_empty_hunger.png", "food_full.png",
                "food_full_hunger.png", "food_half.png", "food_half_hunger.png",
                "hotbar.png", "hotbar_attack_indicator_background.png",
                "hotbar_attack_indicator_progress.png", "hotbar_offhand_left.png",
                "hotbar_offhand_right.png", "hotbar_selection.png",
                "jump_bar_background.png", "jump_bar_cooldown.png", "jump_bar_progress.png",
                "locator_bar_arrow_down.png", "locator_bar_arrow_up.png", "locator_bar_background.png",
                "locator_bar_dot/bowtie.png", "locator_bar_dot/default_0.png",
                "locator_bar_dot/default_1.png", "locator_bar_dot/default_2.png",
                "locator_bar_dot/default_3.png",
            ),
        )
        // The heart family is combinatorial: {variant} x {full,half} x {,_blinking}.
        val variants = listOf(
            "absorbing", "absorbing_hardcore", "container", "container_hardcore",
            "frozen", "frozen_hardcore", "poisoned", "poisoned_hardcore",
            "withered", "withered_hardcore", "", "hardcore",
        )
        for (variant in variants) {
            val prefix = if (variant.isEmpty()) "" else "${variant}_"
            if (variant == "container" || variant == "container_hardcore") {
                add("heart/$variant.png")
                add("heart/${variant}_blinking.png")
                continue
            }
            for (state in listOf("full", "half")) {
                add("heart/$prefix$state.png")
                add("heart/$prefix${state}_blinking.png")
            }
        }
        addAll(listOf("heart/vehicle_container.png", "heart/vehicle_full.png", "heart/vehicle_half.png"))
    }.distinct()

    /**
     * @param removeVanillaHud replace the sprites above with a transparent pixel.
     * @param compressImages re-encode PNGs losslessly; typically 10-25% off the download.
     * @param outFile when given, the archive is also written there.
     */
    fun build(
        packRoot: File,
        outFile: File? = null,
        removeVanillaHud: Boolean = false,
        compressImages: Boolean = true,
    ): PackArchive {
        require(packRoot.isDirectory) { "pack root does not exist: ${packRoot.path}" }

        val buffer = ByteArrayOutputStream(1 shl 20)
        ZipOutputStream(buffer).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            val files = packRoot.walkTopDown().filter { it.isFile }.sortedBy { it.invariantPath(packRoot) }
            for (file in files) {
                val name = file.invariantPath(packRoot)
                val bytes = when {
                    compressImages && name.endsWith(".png") -> recompressPng(file)
                    else -> file.readBytes()
                }
                zip.putEntry(name, bytes)
            }
            if (removeVanillaHud) {
                val blank = transparentPixelPng()
                for (sprite in HUD_SPRITE_FILES) {
                    zip.putEntry(HUD_SPRITES_PATH + sprite, blank)
                }
            }
        }

        val bytes = buffer.toByteArray()
        val sha1 = MessageDigest.getInstance("SHA-1").digest(bytes)
        outFile?.let {
            it.parentFile?.mkdirs()
            it.writeBytes(bytes)
        }
        return PackArchive(bytes, sha1, outFile)
    }

    /**
     * Deterministic entries: no timestamps, fixed order. Two builds of an unchanged pack
     * must hash identically, or every player re-downloads on every server restart.
     */
    private fun ZipOutputStream.putEntry(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            time = 0L
            crc = CRC32().apply { update(bytes) }.value
            size = bytes.size.toLong()
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    private fun File.invariantPath(root: File): String =
        relativeTo(root).path.replace(File.separatorChar, '/')

    /** Re-encode through ImageIO; keeps only the pixels, dropping any ancillary chunks. */
    private fun recompressPng(file: File): ByteArray = try {
        val image = ImageIO.read(file) ?: return file.readBytes()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        val encoded = out.toByteArray()
        if (encoded.isNotEmpty() && encoded.size < file.length()) encoded else file.readBytes()
    } catch (_: Exception) {
        file.readBytes()
    }

    private fun transparentPixelPng(): ByteArray {
        val image = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
