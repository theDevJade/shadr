/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.hud

object DisplayMeta {
    const val BRIGHTNESS_LEVEL = 15

    const val TEXT_BACKGROUND_DEFAULT = 0x40000000
    const val TEXT_BACKGROUND_TRANSPARENT = 0

    const val FLAG_SHADOW: Byte = 1
    const val FLAG_SEE_THROUGH: Byte = 2
    const val FLAG_DEFAULT_BACKGROUND: Byte = 4
    const val FLAG_ALIGN_LEFT: Byte = 8
    const val FLAG_ALIGN_RIGHT: Byte = 16

    const val DEFAULT_TEXT_WRAP_LINE_WIDTH = 200

    const val UNWRAPPED_LINE_WIDTH = 20_000

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
