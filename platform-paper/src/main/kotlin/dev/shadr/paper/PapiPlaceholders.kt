/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.paper

import dev.shadr.core.PlayerId
import dev.shadr.core.page.PlaceholderResolver
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Answers shadr's `%name%` spans out of PlaceholderAPI, when it is installed.
 *
 * PlaceholderAPI is `compileOnly` and never bundled, and a null check alone does not keep it
 * optional. The JVM loads a class the first time it is used, so a field or signature mentioning
 * `me.clip...` in a class that always loads becomes a `NoClassDefFoundError` at enable on every
 * server without PAPI. [Bridge] is the only type here that names one, and nothing references it
 * until [resolverOrNull] has confirmed the plugin is present.
 *
 * PAPI must be called on the main thread; several expansions are not thread-safe.
 */
object PapiPlaceholders {

    const val PLUGIN = "PlaceholderAPI"

    /**
     * @return a resolver backed by PlaceholderAPI, or null when it is not installed. Call after
     *   the server has enabled its plugins; `softdepend` makes `onEnable` late enough.
     */
    fun resolverOrNull(): PlaceholderResolver? {
        if (Bukkit.getPluginManager().getPlugin(PLUGIN)?.isEnabled != true) return null
        return runCatching { Bridge() as PlaceholderResolver }.getOrNull()
    }

    /** The only class in shadr that names a PlaceholderAPI type; see above. */
    private class Bridge : PlaceholderResolver {
        override fun resolve(player: PlayerId, name: String): String? {
            val bukkit = runCatching { Bukkit.getPlayer(UUID.fromString(player.uuid)) }.getOrNull()
                ?: return null
            val token = "%$name%"
            val resolved = runCatching {
                me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(bukkit, token)
            }.getOrNull() ?: return null
            // PAPI hands back the input unchanged for a name it does not know, so an unchanged
            // string means the next resolver in the chain should try.
            return resolved.takeIf { it != token }
        }
    }
}
