/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.PlayerId
import dev.shadr.core.ScreenPos
import dev.shadr.core.cursor.LookMapper
import dev.shadr.core.spi.CameraControl
import dev.shadr.core.spi.HudSink
import dev.shadr.core.spi.InputSample
import dev.shadr.core.spi.InputSource
import dev.shadr.core.spi.PlatformBridge
import dev.shadr.core.spi.PlayerRegistry
import dev.shadr.core.spi.ResourcePackService
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

class PaperBridge(
    private val plugin: Plugin,
    postEffects: () -> Boolean = { false },
) : PlatformBridge, Listener {
    val hudSink = PaperHudSink(plugin)
    val cameraControl = PaperCamera(plugin, postEffects)
    private val inputSource = PaperInput(plugin, cameraControl)
    private val registry = PaperPlayerRegistry()

    private val worldDisplays = PaperWorldDisplays(plugin)

    override fun hud(): HudSink = hudSink
    override fun world(): dev.shadr.core.spi.WorldDisplays = worldDisplays
    override fun camera(): CameraControl = cameraControl
    override fun input(): InputSource = inputSource
    override fun players(): PlayerRegistry = registry

    override fun pack(): ResourcePackService = ResourcePackService { player, url, sha1, forced ->
        val bukkit = player.bukkit() ?: return@ResourcePackService
        val hex = sha1.joinToString("") { "%02x".format(it) }
        val id = UUID.nameUUIDFromBytes(sha1)
        val previous = sent.put(bukkit.uniqueId, id)
        if (previous != null && previous != id) bukkit.removeResourcePack(previous)
        bukkit.addResourcePack(id, url, sha1, null, forced)
        plugin.logger.fine("sent pack $hex to ${bukkit.name}")
    }

    private val sent = java.util.concurrent.ConcurrentHashMap<UUID, UUID>()

    fun tick() = inputSource.tick()

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) = registry.fireJoin(PlayerId(event.player.uniqueId.toString()))

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = PlayerId(event.player.uniqueId.toString())
        hudSink.clear(id)
        cameraControl.stop(id)
        inputSource.forget(id)
        sent.remove(event.player.uniqueId)
        registry.fireQuit(id)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onAttack(event: EntityDamageByEntityEvent) {
        val damager = event.damager as? org.bukkit.entity.Player ?: return
        if (!cameraControl.isCameraEntity(event.entity)) return
        event.isCancelled = true
        inputSource.queueClick(PlayerId(damager.uniqueId.toString()), rightClick = false)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteract(event: PlayerInteractEvent) {
        val id = PlayerId(event.player.uniqueId.toString())
        if (!cameraControl.isActive(id)) return
        event.isCancelled = true
        if (event.action.name.startsWith("RIGHT_CLICK")) inputSource.queueClick(id, rightClick = true)
    }

    private fun PlayerId.bukkit() = runCatching { Bukkit.getPlayer(UUID.fromString(uuid)) }.getOrNull()

    private fun interface ResourcePackSender {
        fun send(player: PlayerId, url: String, sha1: ByteArray, forced: Boolean)
    }

    private fun ResourcePackService(sender: ResourcePackSender) = object : ResourcePackService {
        override fun send(player: PlayerId, url: String, sha1: ByteArray, forced: Boolean) =
            sender.send(player, url, sha1, forced)
    }
}

class PaperInput(private val plugin: Plugin, private val camera: PaperCamera) : InputSource {
    private val listeners = mutableListOf<(InputSample) -> Unit>()
    private val keyListeners = mutableMapOf<String, MutableList<(PlayerId) -> Unit>>()
    private val mappers = mutableMapOf<UUID, LookMapper>()
    private val pendingClicks = mutableMapOf<UUID, Boolean>()

    override fun onSample(listener: (InputSample) -> Unit) {
        listeners += listener
    }

    override fun onKey(key: String, listener: (PlayerId) -> Unit) {
        keyListeners.getOrPut(key) { mutableListOf() } += listener
    }

    fun queueClick(player: PlayerId, rightClick: Boolean) {
        pendingClicks[UUID.fromString(player.uuid)] = rightClick
    }

    fun forget(player: PlayerId) {
        val uuid = UUID.fromString(player.uuid)
        mappers.remove(uuid)
        pendingClicks.remove(uuid)
    }

    fun tick() {
        for (player in Bukkit.getOnlinePlayers()) {
            val id = PlayerId(player.uniqueId.toString())
            if (!camera.isActive(id)) continue
            camera.follow(player)

            val mapper = mappers.getOrPut(player.uniqueId) {
                LookMapper().also { it.reset(yaw = player.location.yaw) }
            }
            val cursor: ScreenPos = mapper.sample(player.location.yaw, player.location.pitch)
            val click = pendingClicks.remove(player.uniqueId)

            val sample = InputSample(
                player = id,
                cursor = cursor,
                leftClick = click == false,
                rightClick = click == true,
                pingMillis = player.ping,
            )
            listeners.forEach { it(sample) }
        }
    }

    fun mapperFor(player: PlayerId): LookMapper? = mappers[UUID.fromString(player.uuid)]

    fun resetMapper(player: PlayerId, atYaw: Float) {
        mappers[UUID.fromString(player.uuid)] = LookMapper().also { it.reset(yaw = atYaw) }
    }
}

class PaperPlayerRegistry : PlayerRegistry {
    private val joinListeners = mutableListOf<(PlayerId) -> Unit>()
    private val quitListeners = mutableListOf<(PlayerId) -> Unit>()

    override fun online(): Collection<PlayerId> =
        Bukkit.getOnlinePlayers().map { PlayerId(it.uniqueId.toString()) }

    override fun onJoin(listener: (PlayerId) -> Unit) {
        joinListeners += listener
    }

    override fun onQuit(listener: (PlayerId) -> Unit) {
        quitListeners += listener
    }

    fun fireJoin(player: PlayerId) = joinListeners.forEach { it(player) }
    fun fireQuit(player: PlayerId) = quitListeners.forEach { it(player) }
}
