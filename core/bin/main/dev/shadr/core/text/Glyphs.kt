/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.text

object Glyphs {
    const val BACKGROUND = '\uE000'

    const val ROUNDED_SOFT = '\uE001'
    const val ROUNDED_SOFT2 = '\uE002'
    const val ROUNDED_SOFT3 = '\uE003'
    const val GRADIENT = '\uE004'
    const val SLIDER = '\uE005'

    const val CIRCLE = '\uE006'

    val SHAPE_TEXTURES: List<Pair<Int, String>> = listOf(
        BACKGROUND.code to "background",
        ROUNDED_SOFT.code to "rounded",
        ROUNDED_SOFT2.code to "rounded2",
        ROUNDED_SOFT3.code to "rounded3",
        GRADIENT.code to "gradient",
        SLIDER.code to "slider",
        CIRCLE.code to "circle",
    )

    private const val CORNER_BASE = 0xE010

    val RADII = listOf("small", "medium", "regular", "large")

    val CORNERS = listOf("1", "2", "3", "4")

    fun corner(radius: Int, corner: Int): Char {
        require(radius in RADII.indices) { "radius index $radius out of range" }
        require(corner in CORNERS.indices) { "corner index $corner out of range" }
        return (CORNER_BASE + radius * 4 + corner).toChar()
    }

    fun cornerBase(radius: Int): Int = CORNER_BASE + radius * 4

    const val CURSOR = '\uE030'
    const val CURSOR_HOVER = '\uE031'
    const val CURSOR_MOVE = '\uE032'
    const val CURSOR_SCALE_LR = '\uE033'
    const val CURSOR_SCALE_TB = '\uE034'
    const val CURSOR_SCALE_TL_BR = '\uE035'
    const val CURSOR_SCALE_TR_BL = '\uE036'
    const val CURSOR_TEXT = '\uE037'

    const val CURSOR_BASE = 0xE030
    val CURSOR_NAMES = listOf(
        "cursor", "hover", "move", "scale-l-r", "scale-t-b", "scale-tl-br", "scale-tr-bl", "text",
    )

    const val NEGATIVE_SPACE = '\uE040'

    const val IMAGE_ATLAS_START = 0xE100
    const val IMAGE_ATLAS_END = 0xEF6F

    const val HEAD_PIXEL = '\uEF70'
    const val HEAD_JOIN = '\uEF71'

    val RESERVED: IntRange = BACKGROUND.code..HEAD_JOIN.code

    val HEAD_ASCENTS = listOf(8, -10, -28, -46, -64, -82, -100, -118)

    const val FONT_UI = "shadr"
    const val FONT_UI_SEMIBOLD = "shadr_semibold"

    const val FONT_UI_SHARP = "shadr_sharp"
    const val FONT_UI_SHARP_SEMIBOLD = "shadr_sharp_semibold"
    const val FONT_IMAGES = "uiimages"
    fun headFont(index: Int) = "head_${index.coerceIn(1, HEAD_ASCENTS.size)}"
}
