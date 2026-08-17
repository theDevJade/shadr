/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack.msdf

import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.PathIterator
import java.awt.geom.Point2D
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object Msdf {
    private const val YELLOW = 0b011
    private const val MAGENTA = 0b101
    private const val CYAN = 0b110
    private const val WHITE = 0b111

    private val COLOR_CYCLE = intArrayOf(YELLOW, CYAN, MAGENTA)

    private class Edge(
        val ax: Double, val ay: Double,
        val bx: Double, val by: Double,
        var channels: Int,
    ) {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy

        fun trueDistance(px: Double, py: Double): Double {
            val t = if (lengthSq <= 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }

        fun channelDistance(px: Double, py: Double): Double {
            val trueDistance = trueDistance(px, py)
            if (lengthSq <= 0.0) return trueDistance
            val t = ((px - ax) * dx + (py - ay) * dy) / lengthSq
            if (t in 0.0..1.0) return trueDistance
            val length = hypot(dx, dy)
            val perpendicular = abs((px - ax) * dy - (py - ay) * dx) / length
            return min(perpendicular, trueDistance)
        }

        fun direction(): Pair<Double, Double> {
            val length = hypot(dx, dy)
            return if (length <= 0.0) 0.0 to 0.0 else (dx / length) to (dy / length)
        }
    }

    /**
     * @param opaqueWidth how many columns carry [FIELD_ALPHA].
     */
    fun render(
        shape: Shape,
        width: Int,
        height: Int,
        transform: AffineTransform,
        spread: Double = 6.0,
        cornerAngleDegrees: Double = 30.0,
        opaqueWidth: Int = width,
        fieldWidth: Int = width,
    ): IntArray {
        val transformed = transform.createTransformedShape(shape)
        val edges = colorEdges(contoursOf(transformed), cornerAngleDegrees)
        val pixels = IntArray(width * height)
        val ink = opaqueWidth.coerceIn(0, width)
        val live = fieldWidth.coerceIn(0, width)

        if (edges.isEmpty()) {
            val empty = encode(-spread, spread)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val alpha = if (x < ink) FIELD_ALPHA else 0
                    pixels[y * width + x] = (alpha shl 24) or (empty shl 16) or (empty shl 8) or empty
                }
            }
            return pixels
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val px = x + 0.5
                val py = y + 0.5
                val inside = transformed.contains(px, py)
                val sign = if (inside) 1.0 else -1.0

                val r = channelValue(edges, 0, px, py) * sign
                val g = channelValue(edges, 1, px, py) * sign
                val b = channelValue(edges, 2, px, py) * sign

                val nearest = edges.minOf { it.trueDistance(px, py) }
                val trueDistance = nearest * sign

                val reference = encode(trueDistance, spread)
                val medianValue = median(encode(r, spread), encode(g, spread), encode(b, spread))
                val disagrees = (medianValue - 128) * (reference - 128) < 0

                val outside = encode(-spread, spread)
                val red = if (x >= live) outside else if (disagrees) reference else encode(r, spread)
                val green = if (x >= live) outside else if (disagrees) reference else encode(g, spread)
                val blue = if (x >= live) outside else if (disagrees) reference else encode(b, spread)

                val alpha = if (x < ink) FIELD_ALPHA else 0

                pixels[y * width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return pixels
    }

    private fun channelValue(edges: List<Edge>, channel: Int, px: Double, py: Double): Double {
        val mask = 1 shl channel
        var bestTrue = Double.MAX_VALUE
        var best: Edge? = null
        for (edge in edges) {
            if (edge.channels and mask == 0) continue
            val distance = edge.trueDistance(px, py)
            if (distance < bestTrue) {
                bestTrue = distance
                best = edge
            }
        }
        val edge = best ?: return Double.MAX_VALUE
        return edge.channelDistance(px, py)
    }

    private fun median(a: Int, b: Int, c: Int): Int = maxOf(minOf(a, b), minOf(maxOf(a, b), c))

    private fun encode(distance: Double, spread: Double): Int {
        val normalized = 0.5 + distance / spread
        return (normalized.coerceIn(0.0, 1.0) * 255.0 + 0.5).toInt()
    }

    private fun contoursOf(shape: Shape): List<List<Point2D.Double>> {
        val contours = mutableListOf<List<Point2D.Double>>()
        var current = mutableListOf<Point2D.Double>()
        val coords = DoubleArray(6)
        val iterator = shape.getPathIterator(null, FLATNESS)

        while (!iterator.isDone) {
            when (iterator.currentSegment(coords)) {
                PathIterator.SEG_MOVETO -> {
                    if (current.size > 1) contours += current
                    current = mutableListOf(Point2D.Double(coords[0], coords[1]))
                }
                PathIterator.SEG_LINETO -> current += Point2D.Double(coords[0], coords[1])
                PathIterator.SEG_CLOSE -> {
                    if (current.size > 1) contours += current
                    current = mutableListOf()
                }
            }
            iterator.next()
        }
        if (current.size > 1) contours += current
        return contours
    }

    private fun colorEdges(contours: List<List<Point2D.Double>>, cornerAngleDegrees: Double): List<Edge> {
        val cornerCos = kotlin.math.cos(Math.toRadians(cornerAngleDegrees))
        val out = mutableListOf<Edge>()

        for (contour in contours) {
            val points = if (contour.first().distance(contour.last()) < 1e-9) contour.dropLast(1) else contour
            if (points.size < 2) continue

            val edges = ArrayList<Edge>(points.size)
            for (i in points.indices) {
                val a = points[i]
                val b = points[(i + 1) % points.size]
                if (a.distance(b) < 1e-9) continue
                edges += Edge(a.x, a.y, b.x, b.y, WHITE)
            }
            if (edges.isEmpty()) continue

            val corners = edges.indices.filter { index ->
                val previous = edges[(index - 1 + edges.size) % edges.size]
                val (px, py) = previous.direction()
                val (cx, cy) = edges[index].direction()
                (px * cx + py * cy) < cornerCos
            }

            if (corners.isEmpty()) {
                out += edges
                continue
            }

            var colorIndex = 0
            val start = corners.first()
            for (offset in edges.indices) {
                val index = (start + offset) % edges.size
                if (offset > 0 && index in corners) colorIndex++
                edges[index].channels = COLOR_CYCLE[colorIndex % COLOR_CYCLE.size]
            }
            if (corners.size >= 2 && (corners.size % COLOR_CYCLE.size) == 1) {
                val lastRunStart = corners.last()
                var index = lastRunStart
                do {
                    edges[index].channels = COLOR_CYCLE[1]
                    index = (index + 1) % edges.size
                } while (index != start)
            }
            out += edges
        }
        return out
    }

    const val FIELD_ALPHA = 6

    private const val FLATNESS = 0.02

    fun paddingFor(spread: Double): Int = max(2, kotlin.math.ceil(spread).toInt() + BILINEAR_MARGIN)

    private const val BILINEAR_MARGIN = 2

    internal fun clampToCell(value: Double, cell: Int): Double = min(cell.toDouble(), max(0.0, value))
}
