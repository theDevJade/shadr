/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.hud

import dev.shadr.core.Vec3
import dev.shadr.core.text.Glyphs

object HeaderEmitter {
    const val KEY = "shadr__frame_header"

    const val TRANSLATION_Y = -200_000.0

    fun draws(enabled: Boolean): List<HudDraw> {
        if (!enabled) return emptyList()
        return listOf(
            HudDraw(
                key = KEY,
                kind = HudDraw.Kind.TEXT,
                translation = Vec3(0.0, TRANSLATION_Y, 0.0),
                scale = Vec3(1.0, 1.0, 1.0),
                content = "<#ffffff><font:${Glyphs.FONT_UI}>${Glyphs.BACKGROUND}",
            ),
        )
    }
}
