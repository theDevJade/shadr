/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.stream

object MapPalette {

    const val MAP_EDGE = 128

    const val MAP_WORDS = MAP_EDGE * MAP_EDGE

    const val WORD_BITS = 7

    const val WORDS = 1 shl WORD_BITS

    const val PACKED_IDS = 256

    const val NO_WORD = -1

    private val BASE = intArrayOf(
        0, 8368696, 16247203, 13092807, 16711680, 10526975, 10987431, 31744,
        16777215, 10791096, 9923917, 7368816, 4210943, 9402184, 16776437, 14188339,
        11685080, 6724056, 15066419, 8375321, 15892389, 5000268, 10066329, 5013401,
        8339378, 3361970, 6704179, 6717235, 10040115, 1644825, 16445005, 6085589,
        4882687, 55610, 8476209, 7340544, 13742497, 10441252, 9787244, 7367818,
        12223780, 6780213, 10505550, 3746083, 8874850, 5725276, 8014168, 4996700,
        4993571, 5001770, 9321518, 2430480, 12398641, 9715553, 6035741, 1474182,
        3837580, 5647422, 1356933, 6579300, 14200723, 8365974,
    )

    private val BRIGHTNESS = intArrayOf(180, 220, 255, 135)

    fun argb(packedId: Int): Int {
        val value = packedId and 0xFF
        val id = value shr 2
        if (id == 0 || id >= BASE.size) return 0
        val color = BASE[id]
        val modifier = BRIGHTNESS[value and 3]
        val r = (color shr 16 and 0xFF) * modifier / 255
        val g = (color shr 8 and 0xFF) * modifier / 255
        val b = (color and 0xFF) * modifier / 255
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun red(packedId: Int): Int = argb(packedId) shr 16 and 0xFF

    val alphabet: ByteArray = selectAlphabet()

    val redToWord: IntArray = IntArray(PACKED_IDS) { NO_WORD }.also { table ->
        for (word in alphabet.indices) table[red(alphabet[word].toInt() and 0xFF)] = word
    }

    fun encode(word: Int): Byte {
        require(word in 0 until WORDS) { "word $word out of range" }
        return alphabet[word]
    }

    fun decode(colorByte: Byte): Int = redToWord[red(colorByte.toInt() and 0xFF)]

    fun packedId(word: Int): Int = alphabet[word].toInt() and 0xFF

    private fun selectAlphabet(): ByteArray {
        val firstByRed = LinkedHashMap<Int, Int>()
        for (packedId in 0 until PACKED_IDS) {
            if (argb(packedId) ushr 24 == 0) continue
            firstByRed.putIfAbsent(red(packedId), packedId)
        }
        val candidates = firstByRed.entries.sortedBy { it.key }.map { it.key to it.value }.toMutableList()
        check(candidates.size >= WORDS) { "only ${candidates.size} red-unique map colours available" }
        while (candidates.size > WORDS) {
            var tightest = 0
            var tightestGap = Int.MAX_VALUE
            for (i in 0 until candidates.size - 1) {
                val gap = candidates[i + 1].first - candidates[i].first
                if (gap < tightestGap) {
                    tightestGap = gap
                    tightest = i
                }
            }
            val leftGap = if (tightest > 0) candidates[tightest].first - candidates[tightest - 1].first else Int.MAX_VALUE
            val rightGap =
                if (tightest + 2 < candidates.size) candidates[tightest + 2].first - candidates[tightest + 1].first else Int.MAX_VALUE
            candidates.removeAt(if (leftGap <= rightGap) tightest else tightest + 1)
        }
        return ByteArray(WORDS) { candidates[it].second.toByte() }
    }

    fun glsl(): String {
        val builder = StringBuilder()
        builder.append("#define SHADR_MAP_EDGE ").append(MAP_EDGE).append('\n')
        builder.append("#define SHADR_MAP_WORD_BITS ").append(WORD_BITS).append('\n')
        builder.append("#define SHADR_MAP_WORDS ").append(WORDS).append('\n')
        builder.append("#define SHADR_MAP_NO_WORD ").append(NO_WORD).append('\n')
        builder.append('\n')
        builder.append("const int SHADR_MAP_WORD[").append(PACKED_IDS).append("] = int[").append(PACKED_IDS).append("](")
        for (i in 0 until PACKED_IDS) {
            if (i > 0) builder.append(',')
            if (i % 16 == 0) builder.append("\n    ")
            builder.append(redToWord[i])
        }
        builder.append("\n);\n")
        builder.append('\n')
        builder.append("int shadr_map_word(vec4 texel) {\n")
        builder.append("    return SHADR_MAP_WORD[int(texel.r * 255.0 + 0.5)];\n")
        builder.append("}\n")
        return builder.toString()
    }
}
