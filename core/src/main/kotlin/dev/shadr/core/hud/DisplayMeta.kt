/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.hud

// The things that make it work!
object DisplayMeta {
    const val INTERPOLATION_DELAY = 8
    const val INTERPOLATION_DURATION = 9
    const val TRANSFORMATION_DURATION = 10
    const val TRANSLATION = 11
    const val SCALE = 12
    const val LEFT_ROTATION = 13
    const val RIGHT_ROTATION = 14
    const val BILLBOARD = 15
    const val BRIGHTNESS = 16

    const val TEXT = 23
    const val LINE_WIDTH = 24
    const val BACKGROUND_COLOR = 25
    const val TEXT_OPACITY = 26
    const val TEXT_FLAGS = 27

    const val BRIGHTNESS_FULL = (15 shl 20) or (15 shl 4)
    const val BRIGHTNESS_LEVEL = 15

    // Transparency.
    const val TEXT_BACKGROUND_DEFAULT = 0x40000000
    const val TEXT_BACKGROUND_TRANSPARENT = 0

    const val FLAG_SHADOW: Byte = 1
    const val FLAG_SEE_THROUGH: Byte = 2
    const val FLAG_DEFAULT_BACKGROUND: Byte = 4
    const val FLAG_ALIGN_LEFT: Byte = 8
    const val FLAG_ALIGN_RIGHT: Byte = 16

    const val DEFAULT_TEXT_WRAP_LINE_WIDTH = 200

    const val UNWRAPPED_LINE_WIDTH = 20_000

    const val BILLBOARD_FIXED: Byte = 0

    fun textFlags(alignment: dev.shadr.core.TextAlignment, useDefaultBackground: Boolean = false): Byte {
        var flags = 0
        if (useDefaultBackground) flags = flags or FLAG_DEFAULT_BACKGROUND.toInt()
        flags = flags or when (alignment) {
            dev.shadr.core.TextAlignment.LEFT -> FLAG_ALIGN_LEFT.toInt()
            dev.shadr.core.TextAlignment.RIGHT -> FLAG_ALIGN_RIGHT.toInt()
            dev.shadr.core.TextAlignment.CENTER -> 0
        }
        return flags.toByte()
    }

    val TEXT_LAYOUT_PREFIX: String = " ".repeat(200) + "\n"
}
