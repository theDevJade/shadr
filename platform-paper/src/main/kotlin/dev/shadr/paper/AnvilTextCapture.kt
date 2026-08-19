/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.PlayerId
import java.util.UUID
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType

class AnvilTextCapture(
    private val onValue: (PlayerId, String, String) -> Unit,
    private val log: (String) -> Unit = { },
) {
    private class Open(val elementId: String, val maxLength: Int, var committed: String, var box: String = "") {
        fun value(): String = committed + box
    }

    private val open = mutableMapOf<UUID, Open>()

    fun focusedElement(player: Player): String? = open[player.uniqueId]?.elementId

    fun focus(player: Player, elementId: String, current: String, maxLength: Int) {
        val existing = open[player.uniqueId]
        if (existing != null && existing.elementId == elementId) return

        open[player.uniqueId] = Open(elementId, maxLength.coerceAtLeast(1), current)
        openAnvil(player)
        log("anvil open for '$elementId' maxLength=$maxLength current='$current'")
    }

    fun release(player: Player) {
        if (open.remove(player.uniqueId) == null) return
        player.closeInventory()
    }

    fun releaseAll() {
        open.keys.toList().forEach { id -> org.bukkit.Bukkit.getPlayer(id)?.let { release(it) } }
        open.clear()
    }

    fun forget(player: PlayerId) {
        runCatching { UUID.fromString(player.uuid) }.getOrNull()?.let { open.remove(it) }
    }

    fun accept(player: Player, box: String) {
        val session = open[player.uniqueId] ?: return
        val raw = box

        if (raw.length >= ANVIL_LIMIT && session.committed.length + raw.length < session.maxLength) {
            session.committed = (session.committed + raw).take(session.maxLength)
            session.box = ""
            openAnvil(player)
            log("anvil refilled for '${session.elementId}' at ${session.committed.length} of ${session.maxLength}")
            publish(player, session)
            return
        }

        session.box = raw
        publish(player, session)
    }

    private fun publish(player: Player, session: Open) {
        onValue(
            PlayerId(player.uniqueId.toString()),
            session.elementId,
            session.value().take(session.maxLength),
        )
    }

    private fun openAnvil(player: Player) {
        val view = MenuType.ANVIL.create(player, Component.empty())
        val blank = ItemStack(Material.PAPER).apply {
            setData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL, org.bukkit.NamespacedKey.minecraft("air"))
            setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_NAME, Component.empty())
        }
        view.topInventory.setItem(0, blank)
        player.openInventory(view)
    }

    companion object {
        const val ANVIL_LIMIT = 50
    }
}
