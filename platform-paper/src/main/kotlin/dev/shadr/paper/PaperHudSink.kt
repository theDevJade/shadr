/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.PlayerId
import dev.shadr.core.TextAlignment
import dev.shadr.core.hud.DisplayMeta
import dev.shadr.core.hud.HudDiff
import dev.shadr.core.hud.HudDraw
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

class PaperHudSink(private val plugin: Plugin) : ShadrHudSink {
    private class PlayerHud(
        val mount: Entity,
        val parts: MutableMap<String, Display> = mutableMapOf(),
        var lastDraws: Map<String, HudDraw> = emptyMap(),
    )

    private val huds = mutableMapOf<UUID, PlayerHud>()

    override fun apply(player: PlayerId, draws: List<HudDraw>) {
        val bukkit = player.bukkit() ?: return
        val hud = huds[bukkit.uniqueId] ?: return

        val diff = HudDiff.between(hud.lastDraws, draws) { key ->
            hud.parts[key]?.isValid == true
        }

        for (key in diff.removed) hud.parts.remove(key)?.remove()

        for (draw in diff.spawned) {
            hud.parts.remove(draw.key)?.remove()
            val display = spawn(bukkit, hud, draw)
            hud.parts[draw.key] = display
            display.applyDraw(draw)
        }

        for (draw in diff.updated) hud.parts[draw.key]?.applyDraw(draw)

        hud.lastDraws = draws.associateBy { it.key }
    }

    override fun clear(player: PlayerId) {
        val bukkit = player.bukkit() ?: return
        val hud = huds.remove(bukkit.uniqueId) ?: return
        hud.parts.values.forEach { it.remove() }
        hud.mount.remove()
    }

    override fun mount(player: PlayerId) {
        val bukkit = player.bukkit() ?: return
        huds[bukkit.uniqueId]?.let { return }
        val mount = bukkit.world.spawn(hudLocation(bukkit.location), TextDisplay::class.java) { display ->
            display.isPersistent = false
            display.text(net.kyori.adventure.text.Component.empty())
            display.setGravity(false)
            display.isVisibleByDefault = false
            display.brightness = Display.Brightness(DisplayMeta.BRIGHTNESS_LEVEL, DisplayMeta.BRIGHTNESS_LEVEL)
        }
        bukkit.addPassenger(mount)
        huds[bukkit.uniqueId] = PlayerHud(mount)
    }

    override fun ensureMounted(player: Player) {
        val hud = huds[player.uniqueId] ?: return
        if (!hud.mount.isValid) {
            huds.remove(player.uniqueId)
            mount(PlayerId(player.uniqueId.toString()))
            return
        }
        if (!player.passengers.contains(hud.mount)) player.addPassenger(hud.mount)
    }

    private fun spawn(owner: Player, hud: PlayerHud, draw: HudDraw): Display {
        val location: Location = hudLocation(hud.mount.location)
        val display: Display = when (draw.kind) {
            HudDraw.Kind.ITEM -> owner.world.spawn(location, ItemDisplay::class.java, ::prepare)
            HudDraw.Kind.TEXT -> owner.world.spawn(location, TextDisplay::class.java, ::prepare)
        }
        display.isVisibleByDefault = false
        owner.showEntity(plugin, display)
        hud.mount.addPassenger(display)
        return display
    }

    private fun prepare(display: Display) {
        display.isPersistent = false
        display.setGravity(false)
        display.billboard = Display.Billboard.FIXED
        display.brightness = Display.Brightness(DisplayMeta.BRIGHTNESS_LEVEL, DisplayMeta.BRIGHTNESS_LEVEL)
        display.viewRange = HUD_VIEW_RANGE
        display.interpolationDelay = 0
        display.interpolationDuration = 0
        if (display is TextDisplay) {
            display.isSeeThrough = false
            display.backgroundColor = org.bukkit.Color.fromARGB(DisplayMeta.TEXT_BACKGROUND_TRANSPARENT)
        }
    }

    private fun Display.applyDraw(draw: HudDraw) {
        interpolationDelay = draw.interpolationDelay
        interpolationDuration = draw.interpolationDuration

        transformation = Transformation(
            Vector3f(draw.translation.x.toFloat(), draw.translation.y.toFloat(), draw.translation.z.toFloat()),
            HUD_FACING,
            Vector3f(draw.scale.x.toFloat(), draw.scale.y.toFloat(), draw.scale.z.toFloat()),
            rotationFor(draw),
        )

        when (this) {
            is TextDisplay -> {
                text(MiniMessageText.parse(draw.displayText))
                lineWidth = draw.lineWidth
                textOpacity = draw.opacity.coerceIn(1, 255).toByte()
                isDefaultBackground = false
                backgroundColor = org.bukkit.Color.fromARGB(DisplayMeta.TEXT_BACKGROUND_TRANSPARENT)
                alignment = when (draw.textAlignment) {
                    TextAlignment.LEFT -> TextDisplay.TextAlignment.LEFT
                    TextAlignment.RIGHT -> TextDisplay.TextAlignment.RIGHT
                    TextAlignment.CENTER -> TextDisplay.TextAlignment.CENTER
                }
            }
            is ItemDisplay -> setItemStack(Displays.hudItem(draw))
            else -> Unit
        }
    }

    private fun rotationFor(draw: HudDraw): Quaternionf {
        if (draw.rotationDeg == 0.0) return Quaternionf()
        return Quaternionf(AxisAngle4f(Math.toRadians(draw.rotationDeg).toFloat(), 0f, 0f, 1f))
    }

    private fun Display.kindMatches(draw: HudDraw): Boolean = when (draw.kind) {
        HudDraw.Kind.TEXT -> this is TextDisplay
        HudDraw.Kind.ITEM -> this is ItemDisplay
    }

    private fun hudLocation(location: Location): Location = location.also {
        it.yaw = 0f
        it.pitch = 0f
    }

    private fun PlayerId.bukkit(): Player? = runCatching { Bukkit.getPlayer(UUID.fromString(uuid)) }.getOrNull()

    private companion object {
        const val HUD_VIEW_RANGE = 64f

        val HUD_FACING: Quaternionf = Quaternionf().rotationXYZ(0f, Math.toRadians(180.0).toFloat(), 0f)
    }
}
