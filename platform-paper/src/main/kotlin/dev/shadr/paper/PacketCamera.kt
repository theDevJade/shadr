/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.PlayerId
import dev.shadr.core.hud.DisplayMeta
import dev.shadr.paper.nms.FakeEntityKind
import dev.shadr.paper.nms.MetaValue
import dev.shadr.paper.nms.PacketBackend
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID

class PacketCamera(
    private val backend: PacketBackend,
    private val postEffects: () -> Boolean = { false },
) : ShadrCamera {
    private class Session(
        val eyeId: Int,
        val seat: Entity,
        val origin: Location,
        var clickTargets: Boolean = false,
        var relockTicks: Int = RELOCK_INTERVAL_TICKS,
    )

    private val sessions = mutableMapOf<UUID, Session>()

    override fun isActive(player: PlayerId) = sessions.containsKey(player.uuid())

    override fun start(player: PlayerId) {
        val bukkit = player.bukkit() ?: return
        if (sessions.containsKey(bukkit.uniqueId)) return

        val origin = bukkit.location.clone()
        val eyeLocation = origin.clone().add(0.0, CAMERA_BASE_Y_OFFSET, 0.0)
        val seat = spawnSeat(bukkit, eyeLocation.clone().add(0.0, CAMERA_SEAT_Y_OFFSET, 0.0))
        val eyeId = backend.nextEntityId(bukkit)
        val creeper = postEffects()

        backend.bundle(bukkit) {
            backend.spawn(
                bukkit,
                eyeId,
                if (creeper) FakeEntityKind.CREEPER else FakeEntityKind.TEXT_DISPLAY,
                eyeLocation,
            )
            backend.metadata(bukkit, eyeId, eyeMeta(creeper))
            backend.camera(bukkit, eyeId)
        }

        seat.addPassenger(bukkit)
        bukkit.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false))
        sessions[bukkit.uniqueId] = Session(eyeId, seat, origin)
    }

    override fun stop(player: PlayerId) {
        val bukkit = player.bukkit()
        val session = sessions.remove(player.uuid()) ?: return
        session.seat.remove()
        if (bukkit == null) return

        backend.bundle(bukkit) {
            backend.resetCamera(bukkit)
            backend.remove(bukkit, session.eyeId)
        }
        bukkit.leaveVehicle()
        bukkit.removePotionEffect(PotionEffectType.INVISIBILITY)
        bukkit.teleport(session.origin)
    }

    override fun setClickTargetsEnabled(player: PlayerId, enabled: Boolean) {
        sessions[player.uuid()]?.clickTargets = enabled
    }

    override fun clickTargetsEnabled(player: PlayerId): Boolean = sessions[player.uuid()]?.clickTargets == true

    override fun follow(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        if (--session.relockTicks > 0) return
        session.relockTicks = RELOCK_INTERVAL_TICKS

        if (player.vehicle == null && session.seat.isValid) session.seat.addPassenger(player)
        backend.camera(player, session.eyeId)
    }

    override fun isCameraEntity(entity: Entity): Boolean = sessions.values.any { it.seat == entity }

    override fun stopAll() {
        sessions.keys.toList().forEach { stop(PlayerId(it.toString())) }
    }

    private fun eyeMeta(creeper: Boolean): List<MetaValue> = if (creeper) {
        listOf(MetaValue.of(Displays.ENTITY_FLAGS, Displays.FLAG_INVISIBLE))
    } else {
        listOf(
            MetaValue.of(DisplayMeta.BILLBOARD, DisplayMeta.BILLBOARD_FIXED),
            MetaValue.of(DisplayMeta.BRIGHTNESS, DisplayMeta.BRIGHTNESS_FULL),
        )
    }

    private fun spawnSeat(owner: Player, at: Location): Entity =
        owner.world.spawn(at, TextDisplay::class.java) { display ->
            display.isPersistent = false
            display.setGravity(false)
            display.isVisibleByDefault = false
            display.brightness = Display.Brightness(DisplayMeta.BRIGHTNESS_LEVEL, DisplayMeta.BRIGHTNESS_LEVEL)
            display.text(net.kyori.adventure.text.Component.empty())
        }

    private fun PlayerId.uuid(): UUID = UUID.fromString(uuid)
    private fun PlayerId.bukkit(): Player? = runCatching { Bukkit.getPlayer(uuid()) }.getOrNull()

    private companion object {
        const val CAMERA_BASE_Y_OFFSET = 1.5
        const val CAMERA_SEAT_Y_OFFSET = -1.5
        const val RELOCK_INTERVAL_TICKS = 5
    }
}
