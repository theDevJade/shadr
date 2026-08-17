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
import dev.shadr.core.spi.CameraControl
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID

/**
 * Puts the player into "UI mode".
 *
 * The player is seated in an invisible entity and made to spectate a second one 1.5 blocks
 * above it. Riding turns their mouse into a cursor. They cannot walk, their look angles stop
 * meaning anything in the world, and the whole yaw/pitch signal is free for
 * [dev.shadr.core.cursor.LookMapper] to read as pointer motion. Spectating the upper entity
 * keeps the eye position where the HUD's projection assumes it is.
 *
 * A click has to land on some entity, because the client only sends an attack or interact
 * packet when the crosshair is over one. Hence the interaction stand-ins; without them a page
 * would receive no clicks.
 */
class PaperCamera(
    private val plugin: Plugin,
    /**
     * Spawn the spectated entity as an EnderMan so the `invert` post chain runs.
     * `Minecraft.setCameraEntity` calls `GameRenderer.checkEntityPostEffect`, which sets the
     * active post effect from the camera entity's type. Off by default, and tied to the `blur`
     * world override.
     */
    private val frostedGlass: () -> Boolean = { false },
) : CameraControl {

    private class Session(
        val eye: Entity,
        val seat: Entity,
        val origin: Location,
        val previousGameMode: GameMode,
        var clickTarget: Interaction? = null,
    )

    private val sessions = mutableMapOf<UUID, Session>()

    override fun isActive(player: PlayerId) = sessions.containsKey(player.uuid())

    override fun start(player: PlayerId) {
        val bukkit = player.bukkit() ?: return
        if (sessions.containsKey(bukkit.uniqueId)) return

        val origin = bukkit.location.clone()
        val eyeLocation = origin.clone().add(0.0, CAMERA_BASE_Y_OFFSET, 0.0)
        // Only the spectated entity decides the post effect, so only the eye changes type.
        val eye = if (frostedGlass()) spawnPostEffectCamera(bukkit, eyeLocation)
        else spawnMarker(bukkit, eyeLocation)
        val seat = spawnMarker(bukkit, eyeLocation.clone().add(0.0, CAMERA_SEAT_Y_OFFSET, 0.0))

        seat.addPassenger(bukkit)
        bukkit.spectatorTarget = null
        bukkit.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false))
        sessions[bukkit.uniqueId] = Session(eye, seat, origin, bukkit.gameMode)
    }

    override fun stop(player: PlayerId) {
        val bukkit = player.bukkit()
        val session = sessions.remove(player.uuid()) ?: return
        session.clickTarget?.remove()
        session.eye.remove()
        session.seat.remove()
        if (bukkit != null) {
            bukkit.leaveVehicle()
            bukkit.removePotionEffect(PotionEffectType.INVISIBILITY)
            bukkit.teleport(session.origin)
        }
    }

    /**
     * A single large Interaction entity parked in front of the camera. One is enough, because
     * shadr hit-tests in design space from the cursor position. The stand-in only has to make
     * sure some click packet is produced; it does not decide which element was hit.
     */
    override fun setClickTargetsEnabled(player: PlayerId, enabled: Boolean) {
        val session = sessions[player.uuid()] ?: return
        if (!enabled) {
            session.clickTarget?.remove()
            session.clickTarget = null
            return
        }
        if (session.clickTarget?.isValid == true) return
        val bukkit = player.bukkit() ?: return
        val at = session.eye.location.clone().add(session.eye.location.direction.multiply(CLICK_TARGET_DISTANCE))
        session.clickTarget = bukkit.world.spawn(at, Interaction::class.java) { interaction ->
            interaction.isPersistent = false
            interaction.interactionWidth = CLICK_TARGET_SIZE
            interaction.interactionHeight = CLICK_TARGET_SIZE
            interaction.isResponsive = true
            interaction.isVisibleByDefault = false
        }.also { bukkit.showEntity(plugin, it) }
    }

    /** Keep the click stand-in in front of the player as they look around. */
    fun follow(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        val target = session.clickTarget ?: return
        val eye = player.eyeLocation
        target.teleport(eye.clone().add(eye.direction.multiply(CLICK_TARGET_DISTANCE)))
    }

    fun isCameraEntity(entity: Entity): Boolean =
        sessions.values.any { it.eye == entity || it.seat == entity || it.clickTarget == entity }

    fun stopAll() {
        sessions.keys.toList().forEach { stop(PlayerId(it.toString())) }
    }

    /**
     * The spectate target, as an EnderMan, with everything that makes it a mob switched off.
     * None of these flags are optional. AI walks the camera away, gravity sinks it, and
     * anything that wanders past can kill it mid-session. Invulnerability also covers water and
     * daylight teleporting, which kill endermen specifically.
     */
    private fun spawnPostEffectCamera(owner: Player, at: Location): Entity =
        owner.world.spawn(at, org.bukkit.entity.Enderman::class.java) { mob ->
            mob.isPersistent = false
            mob.setGravity(false)
            mob.setAI(false)
            mob.isSilent = true
            mob.isInvulnerable = true
            mob.isCollidable = false
            mob.isVisibleByDefault = false
            mob.addPotionEffect(
                PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false),
            )
        }

    private fun spawnMarker(owner: Player, at: Location): Entity =
        owner.world.spawn(at, TextDisplay::class.java) { display ->
            display.isPersistent = false
            display.setGravity(false)
            display.isVisibleByDefault = false
            display.brightness = Display.Brightness(15, 15)
            display.text(net.kyori.adventure.text.Component.empty())
        }

    private fun PlayerId.uuid(): UUID = UUID.fromString(uuid)
    private fun PlayerId.bukkit(): Player? = runCatching { Bukkit.getPlayer(uuid()) }.getOrNull()

    private companion object {
        const val CAMERA_BASE_Y_OFFSET = 1.5
        const val CAMERA_SEAT_Y_OFFSET = -1.5
        const val CLICK_TARGET_DISTANCE = 3.0
        const val CLICK_TARGET_SIZE = 4.0f
    }
}
