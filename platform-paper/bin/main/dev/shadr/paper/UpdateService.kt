/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.config.UpdateConfig
import dev.shadr.core.update.UpdateChecker
import dev.shadr.core.update.UpdateInstaller
import dev.shadr.core.update.UpdateStatus
import dev.shadr.core.update.Version
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class UpdateService(
    private val plugin: Plugin,
    private val config: UpdateConfig,
    private val currentVersion: Version,
    private val pluginJar: File,
    private val updateFolder: File,
) : Listener {
    @Volatile
    var lastStatus: UpdateStatus? = null
        private set

    @Volatile
    private var stagedVersion: Version? = null

    private val running = AtomicBoolean(false)

    private val checker by lazy {
        UpdateChecker(repo = config.repo, current = currentVersion, channel = config.channel)
    }
    private val installer by lazy { UpdateInstaller(signingKey = config.signingKey.ifBlank { null }) }

    fun start() {
        if (!config.checkEnabled) {
            plugin.logger.info("shadr: update checks are off (updates.check)")
            return
        }
        plugin.server.pluginManager.registerEvents(this, plugin)

        val period = maxOf(1, config.intervalHours) * 60L * 60L * 20L
        plugin.server.scheduler.runTaskTimerAsynchronously(
            plugin,
            Runnable { check(announce = true) },
            20L * 60L,
            period,
        )
    }

    fun check(announce: Boolean): UpdateStatus {
        if (!running.compareAndSet(false, true)) {
            return lastStatus ?: UpdateStatus.Failed("a check is already running")
        }
        val status = try {
            checker.check()
        } finally {
            running.set(false)
        }
        lastStatus = status

        if (status is UpdateStatus.Available && config.download && status.asset != null) {
            stage(status)
        }
        if (announce) sync { announce(status) }
        return status
    }

    private fun stage(status: UpdateStatus.Available) {
        val asset = status.asset ?: return
        if (stagedVersion == status.version) return

        when (val result = installer.stage(asset, status.version, updateFolder, pluginJar.name)) {
            is UpdateInstaller.Result.Staged -> {
                stagedVersion = status.version
                plugin.logger.info(
                    "shadr: ${status.version} downloaded to ${result.stagedAs.path}; " +
                        "it replaces the running jar on the next server start",
                )
            }
            is UpdateInstaller.Result.Failed ->
                plugin.logger.warning("shadr: update download failed: ${result.reason}")
        }
    }

    private fun announce(status: UpdateStatus) {
        when (status) {
            is UpdateStatus.UpToDate -> Unit
            is UpdateStatus.Failed ->
                plugin.logger.info("shadr: update check did not complete: ${status.reason}")
            is UpdateStatus.Available -> {
                plugin.logger.info(summaryFor(status))
                if (config.notifyOps) {
                    plugin.server.onlinePlayers
                        .filter { it.hasPermission(PERMISSION) }
                        .forEach { notify(it, status) }
                }
            }
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!config.notifyOps) return
        val status = lastStatus as? UpdateStatus.Available ?: return
        if (!event.player.hasPermission(PERMISSION)) return
        plugin.server.scheduler.runTaskLater(plugin, Runnable { notify(event.player, status) }, 60L)
    }

    private fun notify(player: Player, status: UpdateStatus.Available) {
        val staged = stagedVersion == status.version
        player.sendMessage(
            Component.text("shadr ", NamedTextColor.GRAY)
                .append(Component.text(status.version.toString(), NamedTextColor.WHITE))
                .append(Component.text(" is available", NamedTextColor.GRAY))
                .append(Component.text(" (running $currentVersion). ", NamedTextColor.DARK_GRAY))
                .append(
                    Component.text(if (staged) "Restart to apply." else "Release notes", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.openUrl(status.releaseUrl))
                        .hoverEvent(HoverEvent.showText(Component.text(status.releaseUrl))),
                ),
        )
    }

    fun command(sender: CommandSender, sub: String?): Boolean {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("shadr: you do not have $PERMISSION")
            return true
        }
        if (!config.checkEnabled && sub != "install") {
            sender.sendMessage("shadr: update checks are disabled in config.yml (updates.check)")
            return true
        }

        when (sub) {
            null, "check" -> {
                sender.sendMessage("shadr: checking for updates...")
                async {
                    val status = check(announce = false)
                    sync { sender.sendMessage(summaryFor(status)) }
                }
            }

            "install" -> {
                val available = lastStatus as? UpdateStatus.Available
                    ?: return reply(sender, "nothing to install; run /shadr update check first")
                val asset = available.asset
                    ?: return reply(sender, "release ${available.version} published no plugin jar: ${available.releaseUrl}")

                sender.sendMessage("shadr: downloading ${asset.name} (${asset.size / 1024} KiB)...")
                async {
                    val result = installer.stage(asset, available.version, updateFolder, pluginJar.name)
                    sync {
                        when (result) {
                            is UpdateInstaller.Result.Staged -> {
                                stagedVersion = available.version
                                sender.sendMessage(
                                    "shadr: ${available.version} staged. It replaces the running jar on the " +
                                        "next server start; /shadr update cancel undoes this.",
                                )
                            }
                            is UpdateInstaller.Result.Failed ->
                                sender.sendMessage("shadr: update failed: ${result.reason}")
                        }
                    }
                }
            }

            "cancel" -> {
                val removed = installer.unstage(updateFolder, pluginJar.name)
                stagedVersion = if (removed) null else stagedVersion
                reply(
                    sender,
                    if (removed) "staged update removed; the running jar stays" else "nothing was staged",
                )
            }

            else -> reply(sender, "usage: /shadr update [check|install|cancel]")
        }
        return true
    }

    private fun summaryFor(status: UpdateStatus): String = when (status) {
        is UpdateStatus.UpToDate -> "shadr: up to date ($currentVersion)"
        is UpdateStatus.Failed -> "shadr: update check did not complete: ${status.reason}"
        is UpdateStatus.Available -> when {
            stagedVersion == status.version ->
                "shadr: ${status.version} is staged and applies on the next restart (running $currentVersion)"
            status.asset == null ->
                "shadr: ${status.version} is available, but the release has no plugin jar: ${status.releaseUrl}"
            else ->
                "shadr: ${status.version} is available (running $currentVersion). " +
                    "/shadr update install to stage it, or see ${status.releaseUrl}"
        }
    }

    private fun reply(sender: CommandSender, message: String): Boolean {
        sender.sendMessage("shadr: $message")
        return true
    }

    private fun async(block: () -> Unit) =
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable(block))

    private fun sync(block: () -> Unit) {
        if (!plugin.isEnabled) return
        runCatching { plugin.server.scheduler.runTask(plugin, Runnable(block)) }
    }

    companion object {
        const val PERMISSION = "shadr.update"
    }
}
