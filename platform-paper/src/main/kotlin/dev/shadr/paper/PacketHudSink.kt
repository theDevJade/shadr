/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.PlayerId
import dev.shadr.core.hud.HudDiff
import dev.shadr.core.hud.HudDraw
import dev.shadr.paper.nms.FakeEntityKind
import dev.shadr.paper.nms.PacketBackend
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

class PacketHudSink(private val backend: PacketBackend) : ShadrHudSink {
    private class Part(val id: Int, val kind: HudDraw.Kind)

    private class PlayerHud(val mountId: Int) {
        val parts = LinkedHashMap<String, Part>()
        val everSpawned = linkedSetOf(mountId)
        var lastDraws: Map<String, HudDraw> = emptyMap()
        var relockTicks = RELOCK_INTERVAL_TICKS
        var reassertCycles = REASSERT_EVERY_RELOCKS
    }

    private val huds = mutableMapOf<UUID, PlayerHud>()

    override fun mount(player: PlayerId) {
        val bukkit = player.bukkit() ?: return
        if (huds.containsKey(bukkit.uniqueId)) return

        val mountId = backend.nextEntityId(bukkit)
        backend.bundle(bukkit) {
            backend.spawn(bukkit, mountId, FakeEntityKind.TEXT_DISPLAY, bukkit.hudLocation())
            backend.mountOn(bukkit, mountId)
        }
        huds[bukkit.uniqueId] = PlayerHud(mountId)
    }

    override fun apply(player: PlayerId, draws: List<HudDraw>) {
        val bukkit = player.bukkit() ?: return
        val hud = huds[bukkit.uniqueId] ?: return

        val diff = HudDiff.between(hud.lastDraws, draws) { key -> hud.parts.containsKey(key) }
        if (diff.isEmpty) return

        backend.bundle(bukkit) {
            val gone = mutableListOf<Int>()
            for (key in diff.removed) hud.parts.remove(key)?.let { gone += it.id }
            for (draw in diff.spawned) hud.parts.remove(draw.key)?.let { gone += it.id }
            if (gone.isNotEmpty()) backend.remove(bukkit, *gone.toIntArray())

            for (draw in diff.spawned) {
                val id = backend.nextEntityId(bukkit)
                backend.spawn(bukkit, id, Displays.kindOf(draw), bukkit.hudLocation())
                backend.metadata(bukkit, id, Displays.metaFor(draw, backend.slots()))
                hud.parts[draw.key] = Part(id, draw.kind)
                hud.everSpawned += id
            }

            for (draw in diff.updated) {
                val part = hud.parts[draw.key] ?: continue
                backend.metadata(bukkit, part.id, Displays.metaFor(draw, backend.slots()))
            }

            if (gone.isNotEmpty() || diff.spawned.isNotEmpty()) sendPassengers(bukkit, hud)
        }

        hud.lastDraws = draws.associateBy { it.key }
    }

    override fun clear(player: PlayerId) {
        val hud = huds.remove(player.uuid()) ?: return
        val bukkit = player.bukkit() ?: return
        backend.remove(bukkit, *hud.everSpawned.toIntArray())
    }

    override fun ensureMounted(player: Player) {
        val hud = huds[player.uniqueId] ?: return
        if (--hud.relockTicks > 0) return
        hud.relockTicks = RELOCK_INTERVAL_TICKS

        val reassert = --hud.reassertCycles <= 0
        if (reassert) hud.reassertCycles = REASSERT_EVERY_RELOCKS

        backend.bundle(player) {
            backend.mountOn(player, hud.mountId)
            sendPassengers(player, hud)
            if (reassert) {
                for ((key, part) in hud.parts) {
                    val draw = hud.lastDraws[key] ?: continue
                    backend.metadata(player, part.id, Displays.metaFor(draw, backend.slots()))
                }
            }
        }
    }

    private fun sendPassengers(viewer: Player, hud: PlayerHud) {
        backend.passengers(viewer, hud.mountId, *hud.parts.values.map { it.id }.toIntArray())
    }

    private fun Player.hudLocation() = location.also {
        it.yaw = 0f
        it.pitch = 0f
    }

    private fun PlayerId.uuid(): UUID = UUID.fromString(uuid)
    private fun PlayerId.bukkit(): Player? = runCatching { Bukkit.getPlayer(uuid()) }.getOrNull()

    private companion object {
        const val RELOCK_INTERVAL_TICKS = 5
        const val REASSERT_EVERY_RELOCKS = 8
    }
}
