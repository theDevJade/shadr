/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.stream.StreamChannel
import dev.shadr.core.stream.StreamSink
import dev.shadr.paper.nms.FakeEntityKind
import dev.shadr.paper.nms.MetaValue
import dev.shadr.paper.nms.PacketBackend
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.MapId
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

class PacketStreamSink(private val backend: PacketBackend) {

    private class Session(val channel: StreamChannel, val carrierIds: IntArray) {
        var serial: Int = 0
        var bytesSent: Long = 0
        var relockTicks: Int = RELOCK_INTERVAL_TICKS
    }

    private val sessions = mutableMapOf<UUID, Session>()

    fun isActive(viewer: Player): Boolean = sessions.containsKey(viewer.uniqueId)

    fun channel(viewer: Player): StreamChannel? = sessions[viewer.uniqueId]?.channel

    fun bytesSent(viewer: Player): Long = sessions[viewer.uniqueId]?.bytesSent ?: 0

    fun start(viewer: Player, slots: Int, mapIdBase: Int) {
        if (sessions.containsKey(viewer.uniqueId)) return

        val channel = StreamChannel(slots, mapIdBase)
        val carrierIds = IntArray(slots) { backend.nextEntityId(viewer) }
        val at = carrierLocation(viewer)

        backend.bundle(viewer) {
            for (slot in 0 until slots) {
                backend.spawn(viewer, carrierIds[slot], FakeEntityKind.ITEM_FRAME, at, backend.slots().frameFacing())
                backend.metadata(viewer, carrierIds[slot], carrierMeta(channel.mapId(slot)))
            }
        }

        sessions[viewer.uniqueId] = Session(channel, carrierIds)
    }

    fun stop(viewer: Player) {
        val session = sessions.remove(viewer.uniqueId) ?: return
        backend.bundle(viewer) { backend.remove(viewer, *session.carrierIds) }
    }

    fun push(viewer: Player) {
        val session = sessions[viewer.uniqueId] ?: return
        val sink = StreamSink { mapId, startX, startY, width, height, colors ->
            backend.mapData(viewer, mapId, startX, startY, width, height, colors)
        }
        backend.bundle(viewer) { session.bytesSent += session.channel.flush(sink) }
    }

    fun nextSerial(viewer: Player): Int {
        val session = sessions[viewer.uniqueId] ?: return 0
        session.serial += 1
        return session.serial
    }

    fun tick(viewer: Player) {
        val session = sessions[viewer.uniqueId] ?: return
        session.relockTicks -= 1
        if (session.relockTicks > 0) return
        session.relockTicks = RELOCK_INTERVAL_TICKS

        val at = carrierLocation(viewer)
        backend.bundle(viewer) {
            for (id in session.carrierIds) backend.teleport(viewer, id, at)
        }
    }

    private fun carrierLocation(viewer: Player): Location = viewer.eyeLocation.clone()

    private fun carrierMeta(mapId: Int): List<MetaValue> = listOf(
        MetaValue.of(backend.slots().sharedFlags(), backend.slots().invisibleFlag()),
        MetaValue.item(backend.slots().frameItem(), mapItem(mapId)),
        MetaValue.of(backend.slots().frameRotation(), 0),
    )

    private fun mapItem(mapId: Int): ItemStack {
        val stack = ItemStack(Material.FILLED_MAP)
        stack.setData(DataComponentTypes.MAP_ID, MapId.mapId(mapId))
        return stack
    }

    private companion object {
        const val RELOCK_INTERVAL_TICKS = 5
    }
}
