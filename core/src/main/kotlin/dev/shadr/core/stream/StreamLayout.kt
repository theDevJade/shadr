/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.stream

import kotlin.math.floor
import kotlin.math.max

object StreamLayout {

    data class Origin(val x: Int, val y: Int)

    data class Word(val x: Int, val y: Int)

    fun regionSize(columns: Int, rows: Int): Origin =
        Origin(columns * MapPalette.MAP_EDGE, rows * MapPalette.MAP_EDGE)

    fun regionOrigin(
        screenWidth: Double,
        screenHeight: Double,
        designX: Double,
        designY: Double,
        columns: Int,
        rows: Int,
    ): Origin {
        val size = regionSize(columns, rows)
        val x = designX / StreamFormat.DESIGN_WIDTH * screenWidth
        val y = screenHeight - designY / StreamFormat.DESIGN_HEIGHT * screenHeight - size.y
        val limitX = max(screenWidth - size.x, 0.0)
        val limitY = max(screenHeight - size.y, 0.0)
        return Origin(
            floor(x.coerceIn(0.0, limitX) + 0.5).toInt(),
            floor(y.coerceIn(0.0, limitY) + 0.5).toInt(),
        )
    }

    fun slotOrigin(region: Origin, slot: Int, columns: Int): Origin = Origin(
        region.x + slot % columns * MapPalette.MAP_EDGE,
        region.y + slot / columns * MapPalette.MAP_EDGE,
    )

    fun wordAt(slot: Origin, pixelX: Int, pixelY: Int): Word =
        Word(pixelX - slot.x, MapPalette.MAP_EDGE - 1 - (pixelY - slot.y))

    fun pixelFor(slot: Origin, word: Word): Origin =
        Origin(slot.x + word.x, slot.y + MapPalette.MAP_EDGE - 1 - word.y)

    fun fits(screenWidth: Int, screenHeight: Int, slots: Int): Boolean {
        val size = regionSize(StreamFormat.slotColumns(slots), StreamFormat.slotRows(slots))
        return size.x <= screenWidth && size.y <= screenHeight
    }

    fun maxSlots(screenWidth: Int, screenHeight: Int): Int {
        for (slots in StreamFormat.MAX_SLOTS downTo 1) {
            if (fits(screenWidth, screenHeight, slots)) return slots
        }
        return 0
    }

    fun glsl(): String = """
        int shadr_stream_join(int low, int high) {
            return (high << SHADR_MAP_WORD_BITS) | low;
        }

        ivec2 shadr_stream_region_size(int columns, int rows) {
            return ivec2(columns, rows) * SHADR_MAP_EDGE;
        }

        ivec2 shadr_stream_region_origin(vec2 screenSize, vec2 designOrigin, int columns, int rows) {
            vec2 size = vec2(shadr_stream_region_size(columns, rows));
            float x = designOrigin.x / SHADR_STREAM_DESIGN_WIDTH * screenSize.x;
            float y = screenSize.y - designOrigin.y / SHADR_STREAM_DESIGN_HEIGHT * screenSize.y - size.y;
            vec2 limit = max(screenSize - size, vec2(0.0));
            return ivec2(floor(clamp(vec2(x, y), vec2(0.0), limit) + 0.5));
        }

        ivec2 shadr_stream_slot_origin(ivec2 regionOrigin, int slot, int columns) {
            return regionOrigin + ivec2(slot % columns, slot / columns) * SHADR_MAP_EDGE;
        }

        ivec2 shadr_stream_word_at(ivec2 slotOrigin, ivec2 pixel) {
            ivec2 local = pixel - slotOrigin;
            return ivec2(local.x, SHADR_MAP_EDGE - 1 - local.y);
        }

        ivec2 shadr_stream_pixel_for(ivec2 slotOrigin, ivec2 word) {
            return ivec2(slotOrigin.x + word.x, slotOrigin.y + SHADR_MAP_EDGE - 1 - word.y);
        }

        bool shadr_stream_in_region(ivec2 regionOrigin, int columns, int rows, ivec2 pixel) {
            ivec2 local = pixel - regionOrigin;
            ivec2 size = shadr_stream_region_size(columns, rows);
            return local.x >= 0 && local.y >= 0 && local.x < size.x && local.y < size.y;
        }
    """.trimIndent() + "\n"
}
