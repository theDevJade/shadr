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
import dev.shadr.core.action.ActionHost
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * `runAsPlayer` dispatches as the player, so it inherits their permissions. [ActionRunner] only
 * reaches `runAsConsole` when the element carries an explicit permission gate. Without that
 * gate, a player-authored page could hand itself op.
 */
class PaperActionHost(
    private val plugin: Plugin,
    private val openPageHandler: (PlayerId, String, Boolean) -> Unit,
    private val closePageHandler: (PlayerId) -> Unit,
) : ActionHost {

    override fun runAsPlayer(player: PlayerId, command: String) {
        val bukkit = player.bukkit() ?: return
        Bukkit.getScheduler().runTask(plugin, Runnable { bukkit.performCommand(command) })
    }

    override fun runAsConsole(command: String) {
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command) },
        )
    }

    override fun message(player: PlayerId, text: String) {
        player.bukkit()?.sendMessage(MiniMessageText.parseTrusted(text))
    }

    override fun playSound(player: PlayerId, sound: String, volume: Double) {
        val bukkit = player.bukkit() ?: return
        val key = org.bukkit.NamespacedKey.fromString(sound.lowercase()) ?: return
        // Resource-pack sounds have no registry entry, so this builds the Adventure sound
        // straight from the key instead of going through Bukkit's Sound registry.
        bukkit.playSound(
            net.kyori.adventure.sound.Sound.sound()
                .type(net.kyori.adventure.key.Key.key(key.namespace, key.key))
                .volume(volume.toFloat())
                .pitch(1f)
                .build(),
        )
    }

    override fun closePage(player: PlayerId) = closePageHandler(player)

    override fun openPage(player: PlayerId, page: String, replacing: Boolean) =
        openPageHandler(player, page, replacing)

    override fun teleport(player: PlayerId, destination: String) {
        val bukkit = player.bukkit() ?: return
        val parts = destination.trim().split(Regex("\\s+"))
        // `world x y z` or `x y z` in the player's current world.
        val world = if (parts.size >= 4) Bukkit.getWorld(parts[0]) ?: return else bukkit.world
        val offset = if (parts.size >= 4) 1 else 0
        if (parts.size < offset + 3) return
        val x = parts[offset].toDoubleOrNull() ?: return
        val y = parts[offset + 1].toDoubleOrNull() ?: return
        val z = parts[offset + 2].toDoubleOrNull() ?: return
        Bukkit.getScheduler().runTask(plugin, Runnable { bukkit.teleport(org.bukkit.Location(world, x, y, z)) })
    }

    override fun hasPermission(player: PlayerId, permission: String): Boolean =
        player.bukkit()?.hasPermission(permission) == true

    override fun scheduleTicks(ticks: Long, task: () -> Unit) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { task() }, ticks)
    }

    private fun PlayerId.bukkit() = runCatching { Bukkit.getPlayer(UUID.fromString(uuid)) }.getOrNull()
}
