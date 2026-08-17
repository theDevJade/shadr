/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.page

import dev.shadr.core.PlayerId

fun interface PlaceholderResolver {
    fun resolve(player: PlayerId, name: String): String?

    companion object {
        val NONE = PlaceholderResolver { _, _ -> null }

        fun chain(vararg resolvers: PlaceholderResolver) = PlaceholderResolver { player, name ->
            resolvers.firstNotNullOfOrNull { it.resolve(player, name) }
        }
    }
}

object PlaceholderScanner {
    private val pattern = Regex("""%([A-Za-z0-9_:.\-]+)%""")

    fun hasPlaceholder(text: String): Boolean = text.contains('%') && pattern.containsMatchIn(text)

    fun apply(text: String, player: PlayerId, resolver: PlaceholderResolver): String {
        if (!hasPlaceholder(text)) return text
        return pattern.replace(text) { match ->
            val name = match.groupValues[1]
            resolver.resolve(player, name.lowercase()) ?: match.value
        }
    }
}

class BuiltinPlaceholders(private val snapshot: () -> Snapshot) : PlaceholderResolver {
    data class Snapshot(
        val playerName: String = "",
        val online: Int = 0,
        val maxPlayers: Int = 0,
        val tps: String = "20.0",
        val pingMillis: Int = 0,
        val world: String = "",
        val worldTime: String = "",
    )

    override fun resolve(player: PlayerId, name: String): String? {
        val now = snapshot()
        return when (name) {
            "shadr_player" -> now.playerName
            "shadr_online" -> now.online.toString()
            "shadr_max_players" -> now.maxPlayers.toString()
            "shadr_tps" -> now.tps
            "shadr_ping" -> now.pingMillis.toString()
            "shadr_world" -> now.world
            "shadr_time" -> now.worldTime
            else -> null
        }
    }

    companion object {
        val NAMES = listOf(
            "shadr_player", "shadr_online", "shadr_max_players",
            "shadr_tps", "shadr_ping", "shadr_world", "shadr_time",
        )
    }
}
