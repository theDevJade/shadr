/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.page

import dev.shadr.core.PlayerId

class InputPlaceholders(private val lookup: (PlayerId, String) -> String?) : PlaceholderResolver {

    override fun resolve(player: PlayerId, name: String): String? {
        if (!name.startsWith(PREFIX)) return null
        val id = name.removePrefix(PREFIX)
        if (id.isEmpty()) return null
        return lookup(player, id) ?: ""
    }

    companion object {
        const val PREFIX = "input_"

        fun nameFor(id: String): String = "%$PREFIX${id.lowercase()}%"
    }
}
