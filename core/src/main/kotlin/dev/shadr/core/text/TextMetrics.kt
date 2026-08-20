/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.text

import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
data class CodepointRange(val from: Int, val to: Int) {
    operator fun contains(codepoint: Int): Boolean = codepoint in from..to
}

@Serializable
data class FontMetrics(
    val advance: Double,
    val ascent: Double,
    val descent: Double,
    val lineHeight: Double,
    val advances: Map<Int, Double> = emptyMap(),
    val coverage: List<CodepointRange> = emptyList(),
) {
    fun advanceOf(codepoint: Int): Double = advances[codepoint] ?: advance

    fun covers(codepoint: Int): Boolean =
        advances.containsKey(codepoint) || coverage.isEmpty() || coverage.any { codepoint in it }
}

@Serializable
data class MetricsTable(
    val fonts: Map<String, FontMetrics> = emptyMap(),
    val missingGlyphAdvance: Double = MISSING_GLYPH_ADVANCE,
) {
    fun font(name: String): FontMetrics = fonts[name] ?: fonts[Glyphs.FONT_UI] ?: FALLBACK

    /** Width of [text] in font pixels, ignoring wrapping. */
    fun measure(font: String, text: String): Double {
        val metrics = font(font)
        var total = 0.0
        var i = 0
        while (i < text.length) {
            val codepoint = text.codePointAt(i)
            total += advanceFor(metrics, codepoint)
            i += Character.charCount(codepoint)
        }
        return total
    }

    fun measureDesign(font: String, text: String, scale: Double): Double =
        measure(font, text) * scale / SCALE_UNIT

    fun covers(font: String, codepoint: Int): Boolean = font(font).covers(codepoint)

    private fun advanceFor(metrics: FontMetrics, codepoint: Int): Double =
        if (metrics.covers(codepoint)) metrics.advanceOf(codepoint) else missingGlyphAdvance

    fun wrap(font: String, text: String, lineWidth: Int): List<String> {
        val limit = max(1, lineWidth).toDouble()
        val out = mutableListOf<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isEmpty()) {
                out += ""
                continue
            }
            var line = StringBuilder()
            var width = 0.0
            var breakAt = -1
            var widthAtBreak = 0.0
            var i = 0
            while (i < paragraph.length) {
                val codepoint = paragraph.codePointAt(i)
                val chars = Character.charCount(codepoint)
                val advance = advanceFor(font(font), codepoint)
                if (width + advance > limit && line.isNotEmpty()) {
                    if (breakAt >= 0) {
                        out += line.substring(0, breakAt)
                        val rest = line.substring(breakAt + 1)
                        line = StringBuilder(rest)
                        width -= widthAtBreak
                    } else {
                        out += line.toString()
                        line = StringBuilder()
                        width = 0.0
                    }
                    breakAt = -1
                    widthAtBreak = 0.0
                }
                if (codepoint == SPACE) {
                    // A break gets rid of its space.
                    if (line.isEmpty()) {
                        i += chars
                        continue
                    }
                    breakAt = line.length
                    widthAtBreak = width + advance
                }
                line.appendCodePoint(codepoint)
                width += advance
                i += chars
            }
            out += line.toString()
        }
        return out
    }

    companion object {
        const val SCALE_UNIT = 64.0

        /** The `size` the pack's ttf providers are baked at, see FontAssets.ttfProvider. */
        const val TTF_PIXEL_SIZE = 11.0

        const val TEXT_DISPLAY_PIXELS_PER_BLOCK = 40.0

        const val SPACE = 32

        const val MISSING_GLYPH_ADVANCE = 6.0

        const val DEFAULT_LINE_HEIGHT = 9.0

        val FALLBACK = FontMetrics(
            advance = 6.0,
            ascent = 7.0,
            descent = 2.0,
            lineHeight = DEFAULT_LINE_HEIGHT,
        )

        val EMPTY = MetricsTable()

        fun designPerFontPixel(scale: Double): Double = scale / SCALE_UNIT
    }
}
