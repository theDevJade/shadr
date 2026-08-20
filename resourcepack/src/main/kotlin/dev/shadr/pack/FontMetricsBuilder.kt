/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.text.CodepointRange
import dev.shadr.core.text.FontMetrics
import dev.shadr.core.text.MetricsTable
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import java.io.File

object FontMetricsBuilder {

    private const val PROBE_SIZE = 128f

    private val context = FontRenderContext(null, true, true)

    fun ttf(ttf: File, size: Double, codepoints: Iterable<Int>): FontMetrics {
        val base = Font.createFont(Font.TRUETYPE_FONT, ttf).deriveFont(PROBE_SIZE)
        val line = base.getLineMetrics("Hxy", context)
        val span = (line.ascent + line.descent).toDouble().takeIf { it > 0.0 } ?: PROBE_SIZE.toDouble()
        val perFontPixel = size / span

        val advances = linkedMapOf<Int, Double>()
        for (codepoint in codepoints) {
            if (!base.canDisplay(codepoint)) continue
            val text = String(Character.toChars(codepoint))
            val vector = base.createGlyphVector(context, text)
            advances[codepoint] = vector.getGlyphMetrics(0).advanceX.toDouble() * perFontPixel
        }

        val default = advances[' '.code] ?: advances.values.firstOrNull() ?: MetricsTable.FALLBACK.advance
        return FontMetrics(
            advance = default,
            ascent = line.ascent.toDouble() * perFontPixel,
            descent = line.descent.toDouble() * perFontPixel,
            lineHeight = MetricsTable.DEFAULT_LINE_HEIGHT,
            advances = advances.filterValues { it != default },
            coverage = advances.keys.map { CodepointRange(it, it) }.compress(),
        )
    }

    fun bitmapAdvance(image: BufferedImage, renderedHeight: Int): Double {
        val opaque = opaqueWidth(image)
        if (opaque <= 0) return 1.0
        return Math.round(opaque.toDouble() * renderedHeight / image.height).toDouble() + 1.0
    }

    fun opaqueWidth(image: BufferedImage): Int {
        var widest = 0
        for (y in 0 until image.height) {
            for (x in image.width - 1 downTo widest) {
                if ((image.getRGB(x, y) ushr 24) != 0) {
                    widest = maxOf(widest, x + 1)
                    break
                }
            }
        }
        return widest
    }

    fun msdf(advanceTexels: Int, cell: Int, renderedHeight: Int, ascent: Double, charset: String): FontMetrics {
        val advance = Math.round(advanceTexels.toDouble() * renderedHeight / cell).toDouble() + 1.0
        val codepoints = charset.map { it.code }.sorted()
        return FontMetrics(
            advance = advance,
            ascent = ascent,
            descent = renderedHeight - ascent,
            lineHeight = MetricsTable.DEFAULT_LINE_HEIGHT,
            coverage = codepoints.map { CodepointRange(it, it) }.compress(),
        )
    }

    fun withBitmaps(base: FontMetrics, bitmaps: Map<Int, Double>): FontMetrics = base.copy(
        advances = base.advances + bitmaps,
        coverage = (base.coverage + bitmaps.keys.map { CodepointRange(it, it) }).compress(),
    )

    private fun List<CodepointRange>.compress(): List<CodepointRange> {
        if (isEmpty()) return emptyList()
        val sorted = sortedBy { it.from }
        val out = mutableListOf(sorted.first())
        for (range in sorted.drop(1)) {
            val last = out.last()
            if (range.from <= last.to + 1) {
                out[out.lastIndex] = CodepointRange(last.from, maxOf(last.to, range.to))
            } else {
                out += range
            }
        }
        return out
    }
}
