/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
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

/**
 * Multi-channel signed distance field generation from vector outlines.
 *
 * A plain SDF stores one distance per texel, so a shape's corners get rounded off at the
 * texel scale, because the field cannot represent two edges meeting at a point. MSDF stores three
 * distances, to three interleaved subsets of the outline, and the shader takes their
 * median. Where two differently-coloured edge runs meet, two channels disagree and the
 * median reconstructs the sharp corner exactly.
 *
 * This is a deliberately compact take on Chlumsky's msdfgen:
 *
 *  - outlines are flattened to polylines before colouring, and corners are recovered from
 *    the turn angle between consecutive segments rather than from the original curve
 *    structure. At the flatness we use, a real corner and a flattening artefact are
 *    separated by an order of magnitude in turn angle.
 *  - the *sign* comes from `Shape.contains`, which implements the same non-zero winding
 *    rule the font does, rather than from per-edge winding. That removes a whole class of
 *    contour-orientation bugs (counters in `o`, `8`, `%`) for one `contains` call.
 *  - the *magnitude* per channel is the closest edge's distance, extended past that edge's
 *    endpoints to its infinite line where that is nearer. The extension is what keeps
 *    corners sharp; plain segment distance would collapse MSDF back to an SDF.
 */
object Msdf {

    /** RGB channel masks. Each channel appears in exactly two, per msdfgen's scheme. */
    private const val YELLOW = 0b011   // R+G
    private const val MAGENTA = 0b101  // R+B
    private const val CYAN = 0b110     // G+B
    private const val WHITE = 0b111

    private val COLOR_CYCLE = intArrayOf(YELLOW, CYAN, MAGENTA)

    /** A flattened outline segment, tagged with the channels it contributes to. */
    private class Edge(
        val ax: Double, val ay: Double,
        val bx: Double, val by: Double,
        var channels: Int,
    ) {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy

        /** Distance to the segment, used only to pick which edge is closest. */
        fun trueDistance(px: Double, py: Double): Double {
            val t = if (lengthSq <= 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lengthSq).coerceIn(0.0, 1.0)
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }

        /**
         * The value a channel reports once it has chosen this edge.
         *
         * Inside the segment's span this is just the true distance. Past either end it
         * becomes the perpendicular distance to the segment's *infinite* line, where the
         * extension past the endpoint is what keeps a corner sharp instead of letting the
         * field round it off.
         *
         * The extension only applies when it is *closer* than the true distance. Without
         * that guard a texel far off the end of an edge, but nearly in line with it, would
         * report a near-zero distance and the shader would reconstruct a flange running
         * out along the edge's extension. msdfgen carries the same guard.
         */
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
     * Rasterise [shape] into an RGBA field.
     *
     * @param width/[height] target texel size.
     * @param transform maps shape space into texel space; the caller owns scaling and
     * centring so glyph metrics stay its business.
     * @param spread distance range, in texels, mapped across the full 0..1 value range.
     * A texel exactly on the outline encodes 0.5.
     * @param cornerAngleDegrees turn angle above which a joint counts as a corner.
     *
     * Alpha is a flat marker over the field's meaningful extent, not coverage.
     *
     * Minecraft measures a bitmap glyph's advance from its rightmost non-transparent texel,
     * so whatever alpha marks *is* the letter-spacing. Marking coverage gave an extent that
     * hugged the ink and produced text with no sidebearings at all, so letters touched.
     * Marking everything within [spread] of the outline buys a consistent gap on both
     * sides, scaled with the glyph like a real advance.
     *
     * The trade is that the sheet no longer renders as legible text without the shader that
     * decodes it. [FIELD_ALPHA] is deliberately near-zero so that failure mode is invisible
     * text rather than a row of solid blocks.
     */
    fun render(
        shape: Shape,
        width: Int,
        height: Int,
        transform: AffineTransform,
        spread: Double = 6.0,
        cornerAngleDegrees: Double = 30.0,
    ): IntArray {
        val transformed = transform.createTransformedShape(shape)
        val edges = colorEdges(contoursOf(transformed), cornerAngleDegrees)
        val pixels = IntArray(width * height)

        if (edges.isEmpty()) return pixels

        for (y in 0 until height) {
            for (x in 0 until width) {
                // Sample at the texel centre, as the GPU will.
                val px = x + 0.5
                val py = y + 0.5
                val inside = transformed.contains(px, py)
                val sign = if (inside) 1.0 else -1.0

                val r = channelValue(edges, 0, px, py) * sign
                val g = channelValue(edges, 1, px, py) * sign
                val b = channelValue(edges, 2, px, py) * sign

                val nearest = edges.minOf { it.trueDistance(px, py) }
                val trueDistance = nearest * sign

                // Error correction. Where three fields meet awkwardly, most often at the
                // interior vertex of an M or a W, the median can land on the wrong side of
                // the outline and paint ink outside the letter, or punch a hole inside it.
                // A plain single-channel field is a second opinion that cannot have that
                // failure, so any texel whose median disagrees with it about inside-versus-
                // outside is replaced wholesale by it.
                //
                // Only *sign* disagreements are corrected: a corner is supposed to make the
                // two differ in magnitude, and correcting on magnitude would undo the very
                // sharpness the three channels exist to provide.
                val reference = encode(trueDistance, spread)
                val medianValue = median(encode(r, spread), encode(g, spread), encode(b, spread))
                val disagrees = (medianValue - 128) * (reference - 128) < 0

                val red = if (disagrees) reference else encode(r, spread)
                val green = if (disagrees) reference else encode(g, spread)
                val blue = if (disagrees) reference else encode(b, spread)

                // Mark the band the field actually describes; see the class docs on advance.
                val alpha = if (nearest <= spread || inside) FIELD_ALPHA else 0

                pixels[y * width + x] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return pixels
    }

    /** Perpendicular distance to the nearest edge carrying [channel]. */
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

    /** Flatten to closed polylines. Flatness is well below one texel, so curves stay smooth. */
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

    /**
     * Assign channel masks so that adjacent runs of edges never share a channel pair.
     *
     * A contour with no corners at all (an `o`, a `0`) gets WHITE throughout: with nothing
     * to preserve, all three channels agree and the median degenerates to a plain SDF,
     * which is exactly right for a smooth curve.
     */
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

            // A joint is a corner when the outline turns sharply through it.
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

            // Walk from the first corner so every run is bounded by corners at both ends.
            var colorIndex = 0
            val start = corners.first()
            for (offset in edges.indices) {
                val index = (start + offset) % edges.size
                if (offset > 0 && index in corners) colorIndex++
                edges[index].channels = COLOR_CYCLE[colorIndex % COLOR_CYCLE.size]
            }
            // A run count not divisible by 3 would give the first and last runs the same
            // colour across the seam; nudge the final run so the seam still disagrees.
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

    /**
     * Alpha written over the field's extent. Any non-zero value would do for advance; a
     * near-invisible one means a missing shader shows nothing rather than solid blocks.
     */
    const val FIELD_ALPHA = 6

    /** Sub-texel flatness: curve error stays invisible at any sampling density we use. */
    private const val FLATNESS = 0.02

    /**
     * Texels of margin to leave around a glyph's ink inside its cell.
     *
     * The field itself needs [spread] texels to decay from the outline to fully-outside.
     * Beyond that the shader reconstructs bilinearly, so the outermost texel it reads must
     * have a real neighbour on the far side. If the field ran right up to the cell edge,
     * that tap would land in the *next glyph's* cell and blend a stray edge into the gap.
     * Those show up as thin stray lines between letters.
     */
    fun paddingFor(spread: Double): Int = max(2, kotlin.math.ceil(spread).toInt() + BILINEAR_MARGIN)

    /** One texel for the bilinear tap, one for rounding. */
    private const val BILINEAR_MARGIN = 2

    internal fun clampToCell(value: Double, cell: Int): Double = min(cell.toDouble(), max(0.0, value))
}
