/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

class MosaicCodebook(
    val endpoints: IntArray,
    val selectors: IntArray,
    assignedEndpoints: Map<Int, Int>,
    assignedSelectors: Map<Int, Int>,
) {
    private val endpointAssignment = java.util.concurrent.ConcurrentHashMap(assignedEndpoints)

    private val selectorAssignment = java.util.concurrent.ConcurrentHashMap(assignedSelectors)

    val palettes: Array<IntArray> = Array(endpoints.size) {
        MosaicBc1.paletteOf(pairE0(endpoints[it]), pairE1(endpoints[it]))
    }

    val rows: Array<IntArray> = Array(selectors.size) { patternRows(selectors[it]) }

    fun endpointEntry(pair: Int): Int = endpointAssignment.getOrPut(pair) {
        nearestEndpoint(pair)
    }

    fun selectorEntry(pattern: Int): Int = selectorAssignment.getOrPut(pattern) {
        nearestSelector(pattern)
    }

    private val endpointVectors by lazy { Array(endpoints.size) { endpointVector(endpoints[it]) } }

    private val selectorVectors by lazy { Array(selectors.size) { selectorVector(selectors[it]) } }

    private fun nearestEndpoint(pair: Int): Int {
        val target = endpointVector(pair)
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (i in endpointVectors.indices) {
            val d = squaredDistance(target, endpointVectors[i])
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    private fun nearestSelector(pattern: Int): Int {
        val target = selectorVector(pattern)
        var best = 0
        var bestDistance = Double.MAX_VALUE
        for (i in selectorVectors.indices) {
            val d = squaredDistance(target, selectorVectors[i])
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    companion object {

        fun pairOf(e0: Int, e1: Int): Int = (e0 shl 16) or e1

        fun pairE0(pair: Int): Int = (pair ushr 16) and 0xFFFF

        fun pairE1(pair: Int): Int = pair and 0xFFFF

        fun patternOf(rows: IntArray): Int =
            (rows[0] and 0xFF) or ((rows[1] and 0xFF) shl 8) or
                ((rows[2] and 0xFF) shl 16) or ((rows[3] and 0xFF) shl 24)

        fun patternRows(pattern: Int): IntArray = intArrayOf(
            pattern and 0xFF,
            (pattern ushr 8) and 0xFF,
            (pattern ushr 16) and 0xFF,
            (pattern ushr 24) and 0xFF,
        )

        private const val TRAINING_CAP = 16384

        private const val ITERATIONS = 6

        fun build(
            endpointCounts: Map<Int, Long>,
            selectorCounts: Map<Int, Long>,
        ): MosaicCodebook {
            val endpointTraining = cap(endpointCounts)
            val selectorTraining = cap(selectorCounts)

            val endpointResult = cluster(
                endpointTraining,
                MosaicFormat.CODEBOOK,
            ) { endpointVector(it) }
            val selectorResult = cluster(
                selectorTraining,
                MosaicFormat.CODEBOOK,
            ) { selectorVector(it) }

            return MosaicCodebook(
                endpoints = endpointResult.entries,
                selectors = selectorResult.entries,
                assignedEndpoints = endpointResult.assignment,
                assignedSelectors = selectorResult.assignment,
            )
        }

        private fun cap(counts: Map<Int, Long>): List<Pair<Int, Long>> =
            counts.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
                .take(TRAINING_CAP)
                .map { it.key to it.value }

        private class Clustering(val entries: IntArray, val assignment: HashMap<Int, Int>)

        private fun cluster(
            training: List<Pair<Int, Long>>,
            size: Int,
            vector: (Int) -> DoubleArray,
        ): Clustering {
            if (training.isEmpty()) {
                return Clustering(IntArray(size), HashMap())
            }

            val keys = IntArray(training.size) { training[it].first }
            val weights = LongArray(training.size) { training[it].second }
            val vectors = Array(training.size) { vector(keys[it]) }
            val dims = vectors[0].size

            val k = minOf(size, training.size)
            val centroids = Array(k) { vectors[it].copyOf() }
            val assigned = IntArray(training.size)

            repeat(ITERATIONS) {
                for (i in vectors.indices) {
                    var best = 0
                    var bestDistance = Double.MAX_VALUE
                    for (c in 0 until k) {
                        val d = squaredDistance(vectors[i], centroids[c])
                        if (d < bestDistance) {
                            bestDistance = d
                            best = c
                        }
                    }
                    assigned[i] = best
                }

                val sums = Array(k) { DoubleArray(dims) }
                val totals = LongArray(k)
                for (i in vectors.indices) {
                    val c = assigned[i]
                    val w = weights[i]
                    totals[c] += w
                    val v = vectors[i]
                    val s = sums[c]
                    for (d in 0 until dims) s[d] += v[d] * w
                }
                for (c in 0 until k) {
                    if (totals[c] == 0L) continue
                    val s = sums[c]
                    for (d in 0 until dims) centroids[c][d] = s[d] / totals[c]
                }
            }

            val entries = IntArray(size)
            val medoidDistance = DoubleArray(k) { Double.MAX_VALUE }
            val medoid = IntArray(k) { -1 }
            for (i in vectors.indices) {
                val c = assigned[i]
                val d = squaredDistance(vectors[i], centroids[c])
                if (d < medoidDistance[c] ||
                    (d == medoidDistance[c] && medoid[c] >= 0 && keys[i] < keys[medoid[c]])
                ) {
                    medoidDistance[c] = d
                    medoid[c] = i
                }
            }
            for (c in 0 until k) {
                entries[c] = if (medoid[c] >= 0) keys[medoid[c]] else keys[0]
            }
            for (c in k until size) entries[c] = entries[0]

            val entryVectors = Array(k) { vector(entries[it]) }
            val assignment = HashMap<Int, Int>(training.size * 2)
            for (i in vectors.indices) {
                var best = 0
                var bestDistance = Double.MAX_VALUE
                for (c in 0 until k) {
                    val d = squaredDistance(vectors[i], entryVectors[c])
                    if (d < bestDistance) {
                        bestDistance = d
                        best = c
                    }
                }
                assignment[keys[i]] = best
            }

            return Clustering(entries, assignment)
        }

        private fun endpointVector(pair: Int): DoubleArray {
            val palette = MosaicBc1.paletteOf(pairE0(pair), pairE1(pair))
            val out = DoubleArray(12)
            for (entry in 0 until 4) {
                val at = entry * 3
                out[at] = palette[at] * MosaicBc1.WEIGHT_R
                out[at + 1] = palette[at + 1] * MosaicBc1.WEIGHT_G
                out[at + 2] = palette[at + 2] * MosaicBc1.WEIGHT_B
            }
            return out
        }

        private fun selectorVector(pattern: Int): DoubleArray {
            val out = DoubleArray(16)
            for (i in 0 until 16) {
                val index = (pattern ushr (i * 2)) and 3
                out[i] = MosaicBc1.INDEX_WEIGHT_4[index]
            }
            return out
        }

        private fun squaredDistance(a: DoubleArray, b: DoubleArray): Double {
            var total = 0.0
            for (i in a.indices) {
                val d = a[i] - b[i]
                total += d * d
            }
            return total
        }
    }
}
