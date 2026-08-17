/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.PlayerId
import dev.shadr.core.page.PlaceholderResolver
import org.bukkit.Bukkit
import java.util.UUID

object PapiPlaceholders {
    const val PLUGIN = "PlaceholderAPI"

    fun resolverOrNull(): PlaceholderResolver? {
        if (Bukkit.getPluginManager().getPlugin(PLUGIN)?.isEnabled != true) return null
        return runCatching { Bridge() as PlaceholderResolver }.getOrNull()
    }

    private class Bridge : PlaceholderResolver {
        override fun resolve(player: PlayerId, name: String): String? {
            val bukkit = runCatching { Bukkit.getPlayer(UUID.fromString(player.uuid)) }.getOrNull()
                ?: return null
            val token = "%$name%"
            val resolved = runCatching {
                me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(bukkit, token)
            }.getOrNull() ?: return null
            return resolved.takeIf { it != token }
        }
    }
}
