/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.text.Glyphs
import java.awt.image.BufferedImage
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

class PlayerHeadFont(
    private val skinUrlResolver: (String) -> String?,
    private val fetch: (String) -> BufferedImage? = ::download,
) {
    private val cache = ConcurrentHashMap<String, CachedHead>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private data class CachedHead(val text: String, val fetchedAtMillis: Long)

    fun render(playerId: String, nowMillis: Long = System.currentTimeMillis()): String {
        val cached = cache[playerId]
        if (cached != null && nowMillis - cached.fetchedAtMillis < CACHE_TTL_MILLIS) return cached.text
        requestAsync(playerId, nowMillis)
        return cached?.text ?: FALLBACK_HEAD
    }

    fun forget(playerId: String) {
        cache.remove(playerId)
    }

    private fun requestAsync(playerId: String, nowMillis: Long) {
        if (!inFlight.add(playerId)) return
        Thread({
            try {
                val url = skinUrlResolver(playerId) ?: return@Thread
                val skin = fetch(url) ?: return@Thread
                cache[playerId] = CachedHead(buildHeadText(cropFace(skin)), nowMillis)
            } catch (_: Exception) {
            } finally {
                inFlight.remove(playerId)
            }
        }, "shadr-skin-$playerId").apply { isDaemon = true }.start()
    }

    private fun cropFace(skin: BufferedImage): Array<IntArray> {
        val pixels = Array(HEAD_SIZE) { IntArray(HEAD_SIZE) }
        for (y in 0 until HEAD_SIZE) {
            for (x in 0 until HEAD_SIZE) {
                var argb = skin.getRGB(FACE_X + x, FACE_Y + y)
                if (skin.width >= OVERLAY_X + HEAD_SIZE) {
                    val overlay = skin.getRGB(OVERLAY_X + x, OVERLAY_Y + y)
                    if ((overlay ushr 24) and 0xFF >= OVERLAY_ALPHA_THRESHOLD) argb = overlay
                }
                pixels[y][x] = argb and 0xFFFFFF
            }
        }
        return pixels
    }

    private fun buildHeadText(pixels: Array<IntArray>): String = buildString {
        pixels.forEachIndexed { row, columns ->
            append("<font:${Glyphs.headFont(row + 1)}>")
            for (rgb in columns) {
                append("<#%06x>".format(rgb))
                append(Glyphs.HEAD_PIXEL)
            }
            append(Glyphs.HEAD_JOIN)
        }
    }

    companion object {
        const val PLACEHOLDER = "%player_head%"

        const val HEAD_TEXT_WRAP_LINE_WIDTH = 70

        private const val HEAD_SIZE = 8
        private const val FACE_X = 8
        private const val FACE_Y = 8
        private const val OVERLAY_X = 40
        private const val OVERLAY_Y = 8
        private const val OVERLAY_ALPHA_THRESHOLD = 128
        private const val CACHE_TTL_MILLIS = 600_000L

        fun containsPlaceholder(text: String?) = text?.contains(PLACEHOLDER) == true

        private fun download(url: String): BufferedImage? = try {
            URI(url).toURL().openConnection().run {
                connectTimeout = 5_000
                readTimeout = 5_000
                getInputStream().use { ImageIO.read(it) }
            }
        } catch (_: Exception) {
            null
        }

        private val FALLBACK_HEAD: String = run {
            val hair = 0x3F2A18
            val skin = 0xB58C6C
            val eyeWhite = 0xFFFFFF
            val iris = 0x3B2F8C
            val nose = 0xA0745A
            val mouth = 0x6B4632
            val rows = listOf(
                "HHHHHHHH", "HHHHHHHH", "HHHHHHHH", "SWISSIWS",
                "SSSNNSSS", "SSMMMMSS", "SSSSSSSS", "SSSSSSSS",
            )
            buildString {
                rows.forEachIndexed { row, line ->
                    append("<font:${Glyphs.headFont(row + 1)}>")
                    for (code in line) {
                        val rgb = when (code) {
                            'H' -> hair
                            'W' -> eyeWhite
                            'I' -> iris
                            'N' -> nose
                            'M' -> mouth
                            else -> skin
                        }
                        append("<#%06x>".format(rgb))
                        append(Glyphs.HEAD_PIXEL)
                    }
                    append(Glyphs.HEAD_JOIN)
                }
            }
        }
    }
}
