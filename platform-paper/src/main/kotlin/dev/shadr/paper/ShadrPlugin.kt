/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.PlayerId
import dev.shadr.core.action.ActionRunner
import dev.shadr.core.config.HostingMode
import dev.shadr.core.config.ShadrConfig
import dev.shadr.core.cursor.CursorPredictor
import dev.shadr.core.editor.EditorLauncher
import dev.shadr.core.editor.EditorServer
import dev.shadr.core.editor.FileDocumentSource
import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.page.EffectDef
import dev.shadr.core.page.Page
import dev.shadr.core.page.PageLoader
import dev.shadr.core.session.UiSession
import dev.shadr.pack.PackArchive
import dev.shadr.pack.PackBuilder
import dev.shadr.pack.PackGenerator
import dev.shadr.pack.PackHost
import dev.shadr.pack.UiImageAtlas
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class ShadrPlugin : JavaPlugin() {
    private lateinit var config: ShadrConfig
    private lateinit var bridge: PaperBridge
    private lateinit var renderer: PageRenderer
    private lateinit var actionRunner: ActionRunner

    private val sessions = mutableMapOf<String, UiSession>()
    private var pages: Map<String, Page> = emptyMap()
    private var effects: Map<String, EffectDef> = emptyMap()

    private val shaderLoader by lazy {
        dev.shadr.core.shader.ShaderLoader(File(File(dataFolder, "shaders"), "items"))
    }

    private val environment by lazy {
        dev.shadr.core.shader.EnvironmentSettings(
            File(File(dataFolder, "shaders"), "environment.properties"),
        )
    }

    private var shaderHandleCounter = 0

    val shaderApi: dev.shadr.core.shader.ShaderApi by lazy {
        dev.shadr.core.shader.ShaderApi(bridge) { shaderLoader.load() }
    }

    private val packHost = PackHost()
    private var archive: PackArchive? = null
    private var packUrl: String? = null
    private var editor: EditorServer? = null
    private var updates: UpdateService? = null

    override fun onEnable() {
        saveDefaultDirectories()
        config = ShadrConfig.load(File(dataFolder, "config.yml"))

        bridge = PaperBridge(
            this,
            frostedGlass = { environment.isEnabled(dev.shadr.core.shader.EnvironmentEffect.FROSTED_GLASS) },
        )
        server.pluginManager.registerEvents(bridge, this)

        renderer = PageRenderer(
            fixShaders = config.rendering.fixShaders,
            fixShadersLayerGap = config.rendering.fixShadersLayerGap,
        )
        actionRunner = ActionRunner(
            PaperActionHost(
                plugin = this,
                openPageHandler = { player, page, replacing -> openPage(player, page, replacing) },
                closePageHandler = ::closePage,
            ),
        )

        reload()
        wireInput()
        startEditor()
        startUpdates()

        server.scheduler.runTaskTimer(this, Runnable { tick() }, 1L, 1L)
        detectPlaceholderApi()
        logger.info("shadr enabled: ${pages.size} page(s), pack ${packUrl ?: "not hosted"}")
    }

    override fun onDisable() {
        sessions.keys.toList().forEach { closePage(PlayerId(it)) }
        bridge.cameraControl.stopAll()
        packHost.stop()
        editor?.stop()
        logger.info("shadr disabled")
    }

    private fun startEditor() {
        editor = runCatching {
            EditorLauncher.start(
                config = config.editor.web,
                dataFolder = dataFolder,
                shaders = shaderLoader,
                environment = environment,
                environmentSource = dev.shadr.core.shader.EnvironmentSource(File(dataFolder, "shaders")),
                documents = FileDocumentSource(
                    pagesDir = File(dataFolder, "pages"),
                    componentsDir = File(dataFolder, "components"),
                    effectsDir = File(dataFolder, "effects"),
                ),
                onPageChanged = ::applyEditedPage,
                onShadersChanged = ::rebuildPackForShaders,
                log = { logger.info("shadr: $it") },
            )
        }.getOrElse {
            logger.severe("shadr: editor not started: ${it.message}")
            null
        }
    }

    private fun startUpdates() {
        updates = runCatching {
            UpdateService(
                plugin = this,
                config = config.updates,
                currentVersion = dev.shadr.core.update.Version.parse(description.version)
                    ?: dev.shadr.core.update.Version.UNKNOWN,
                pluginJar = file,
                updateFolder = server.updateFolderFile,
            ).also { it.start() }
        }.getOrElse {
            logger.warning("shadr: update checker not started: ${it.message}")
            null
        }
    }

    private fun applyEditedPage(edited: Page) {
        pages = pages + (edited.name to edited)
        for ((uuid, session) in sessions) {
            if (session.currentPage.name != edited.name) continue
            session.refreshPage(edited)
            bridge.hud().apply(PlayerId(uuid), session.draws())
        }
    }

    private fun rebuildPackForShaders(): Boolean = runCatching {
        rebuildPack()
        if (server.isPrimaryThread) resendPackToAll() else server.scheduler.runTask(this, Runnable { resendPackToAll() })
        true
    }.getOrElse {
        logger.severe("shadr: pack rebuild after a shader edit failed: ${it.message}")
        false
    }

    private fun resendPackToAll() {
        server.onlinePlayers.forEach { sendPack(PlayerId(it.uniqueId.toString())) }
    }

    private val packBuildLock = Any()

    private fun rebuildPack() = synchronized(packBuildLock) {
        val packRoot = File(dataFolder, "resourcepack/pack")
        PackGenerator(
            shaderSrc = File(dataFolder, "shaders"),
            fontDir = File(dataFolder, "font"),
            soundDir = File(dataFolder, "sounds"),
            shaders = shaderLoader.load().also { _ ->
                shaderLoader.issues.forEach { logger.warning("shader: $it") }
            },
            environment = environment.all(),
        ).build(packRoot)

        val atlas = UiImageAtlas(
            contentsDir = File(dataFolder, "contents"),
            packRoot = packRoot,
            stateFile = File(dataFolder, "resourcepack/generated/uiimages_codepoints.properties"),
        ).rebuild()
        atlas.issues.forEach { logger.warning("image atlas: $it") }

        archive = PackBuilder.build(
            packRoot = packRoot,
            outFile = File(dataFolder, "resourcepack/generated/shadr-pack.zip"),
            removeVanillaHud = config.pack.removeDefaultHotbar,
            compressImages = config.pack.compressImages,
        )
        packUrl = startHosting()
    }

    fun reload() {
        rebuildPack()

        val loader = PageLoader(
            pagesDir = File(dataFolder, "pages"),
            componentsDir = File(dataFolder, "components"),
            effectsDir = File(dataFolder, "effects"),
        )
        val components = loader.loadComponents()
        effects = loader.loadEffects()
        pages = loader.loadPages(components)
        loader.issues.forEach { logger.warning(it) }
    }

    private fun startHosting(): String? {
        val built = archive ?: return null
        return when (config.pack.hosting) {
            HostingMode.EXTERNAL_PACK -> null
            HostingMode.EXTERNAL_HOST -> config.pack.externalUrl
            HostingMode.SELF_HOST -> packHost.serve(
                built, BIND_ALL, config.pack.selfHostPort, config.pack.selfHostIp,
            )
            HostingMode.DEFAULT_PACK -> packHost.serve(
                built, BIND_ALL, config.pack.selfHostPort, server.ip.ifBlank { config.pack.selfHostIp },
            )
        }
    }

    private fun wireInput() {
        bridge.players().onJoin { player ->
            if (config.pack.applyOnJoin) sendPack(player)
        }
        bridge.players().onQuit { player -> sessions.remove(player.uuid) }

        bridge.input().onSample { sample ->
            val session = sessions[sample.player.uuid] ?: return@onSample
            val mapper = (bridge.input() as PaperInput).mapperFor(sample.player)
            val changed = session.update(
                rawCursor = sample.cursor,
                deltaX = mapper?.deltaX ?: 0.0,
                deltaY = mapper?.deltaY ?: 0.0,
                pingMillis = sample.pingMillis,
            )
            if (sample.leftClick) session.click(rightClick = false)
            if (sample.rightClick) session.click(rightClick = true)
            if (changed || sample.leftClick || sample.rightClick) {
                bridge.hud().apply(sample.player, session.draws())
            }
        }
    }

    private fun tick() {
        bridge.tick()
        for (uuid in sessions.keys) {
            val player = runCatching { Bukkit.getPlayer(java.util.UUID.fromString(uuid)) }.getOrNull() ?: continue
            bridge.hudSink.ensureMounted(player)
        }
        refreshPlaceholders()
    }

    private fun refreshPlaceholders() {
        val interval = config.editor.placeholderRefreshTicks
        if (interval <= 0) return
        if (++placeholderTicks < interval) return
        placeholderTicks = 0

        for ((uuid, session) in sessions) {
            if (!session.hasPlaceholders) continue
            if (session.refreshPlaceholders()) bridge.hud().apply(PlayerId(uuid), session.draws())
        }
    }

    private var placeholderTicks = 0

    private fun placeholderResolver(player: PlayerId): dev.shadr.core.page.PlaceholderResolver {
        val builtins = builtinResolver(player)
        val papi = papiResolver ?: return builtins
        return dev.shadr.core.page.PlaceholderResolver.chain(builtins, papi)
    }

    private var papiResolver: dev.shadr.core.page.PlaceholderResolver? = null

    private fun detectPlaceholderApi() {
        papiResolver = PapiPlaceholders.resolverOrNull()
        if (papiResolver != null) {
            logger.info("shadr: PlaceholderAPI found: page text can use any expansion it provides")
        } else {
            logger.info(
                "shadr: PlaceholderAPI not installed, so only %shadr_* placeholders resolve, " +
                    "and any other %name% stays on screen as written",
            )
        }
    }

    private fun builtinResolver(player: PlayerId): dev.shadr.core.page.PlaceholderResolver =
        dev.shadr.core.page.BuiltinPlaceholders {
            val bukkit = runCatching { Bukkit.getPlayer(java.util.UUID.fromString(player.uuid)) }.getOrNull()
            val time = bukkit?.world?.time ?: 0L
            val hours = ((time / 1000L + 6L) % 24L).toInt()
            val minutes = ((time % 1000L) * 60L / 1000L).toInt()
            dev.shadr.core.page.BuiltinPlaceholders.Snapshot(
                playerName = bukkit?.name.orEmpty(),
                online = Bukkit.getOnlinePlayers().size,
                maxPlayers = Bukkit.getMaxPlayers(),
                tps = "%.1f".format(Bukkit.getTPS().firstOrNull()?.coerceAtMost(20.0) ?: 20.0),
                pingMillis = bukkit?.ping ?: 0,
                world = bukkit?.world?.name.orEmpty(),
                worldTime = "%02d:%02d".format(hours, minutes),
            )
        }

    private fun sendPack(player: PlayerId) {
        val built = archive ?: return
        val url = packUrl ?: return
        bridge.pack().send(player, url, built.sha1, forced = config.pack.kickOnDecline)
    }

    fun openPage(player: PlayerId, pageName: String, replacing: Boolean = true) {
        val page = pages[pageName] ?: run {
            logger.warning("no such page: $pageName")
            return
        }
        val existing = sessions[player.uuid]
        if (existing != null && replacing) {
            existing.openPage(page)
            bridge.hud().apply(player, existing.draws())
            return
        }

        val session = UiSession(
            player = player,
            page = page,
            renderer = renderer,
            effects = effects,
            actionRunner = actionRunner,
            predictor = if (config.experimental.mousePrediction) CursorPredictor() else null,
            placeholders = placeholderResolver(player),
        )
        sessions[player.uuid] = session

        bridge.camera().start(player)
        bridge.camera().setClickTargetsEnabled(player, true)
        bridge.hud().mount(player)
        bridge.hud().apply(player, session.draws())
    }

    fun closePage(player: PlayerId) {
        sessions.remove(player.uuid) ?: return
        bridge.hud().clear(player)
        bridge.camera().setClickTargetsEnabled(player, false)
        bridge.camera().stop(player)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                reload()
                sender.sendMessage("shadr: reloaded ${pages.size} page(s)")
            }
            "open" -> {
                val player = sender as? Player ?: return reply(sender, "players only")
                val name = args.getOrNull(1) ?: return reply(sender, "usage: /shadr open <page>")
                openPage(PlayerId(player.uniqueId.toString()), name)
            }
            "close" -> {
                val player = sender as? Player ?: return reply(sender, "players only")
                closePage(PlayerId(player.uniqueId.toString()))
            }
            "pack" -> {
                val player = sender as? Player ?: return reply(sender, "players only")
                sendPack(PlayerId(player.uniqueId.toString()))
            }
            "pages" -> sender.sendMessage("shadr pages: " + pages.keys.sorted().joinToString(", "))
            "editor" -> return editorCommand(sender, args.getOrNull(1)?.lowercase())
            "shader" -> return shaderCommand(sender, args.drop(1))
            "update" -> {
                val service = updates
                    ?: return reply(sender, "update checks are disabled in config.yml (updates.check)")
                return service.command(sender, args.getOrNull(1)?.lowercase())
            }
            else -> sender.sendMessage("usage: /shadr <open|close|reload|pack|pages|shader|editor|update>")
        }
        return true
    }

    private fun shaderCommand(sender: CommandSender, args: List<String>): Boolean {
        if (!sender.hasPermission(SHADER_PERMISSION)) {
            return reply(sender, "you do not have $SHADER_PERMISSION")
        }

        val api = shaderApi
        when (args.firstOrNull()?.lowercase()) {
            null, "list" -> {
                val installed = api.shaders()
                if (installed.isEmpty()) {
                    return reply(sender, "no shaders installed; add one to shaders/items and /shadr reload")
                }
                sender.sendMessage(
                    "shadr shaders: " + installed.joinToString(", ") { it.id } +
                        "\nplaced: " + (api.placed().takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "none"),
                )
                return true
            }
            "clear" -> {
                val handle = args.getOrNull(1)
                return if (handle == null) {
                    reply(sender, "removed ${api.despawnAll()} shader display(s)")
                } else if (api.despawn(handle)) {
                    reply(sender, "removed '$handle'")
                } else {
                    reply(sender, "nothing placed under '$handle'")
                }
            }
        }

        val player = sender as? Player
            ?: return reply(sender, "players only; the console has nowhere to put it")
        val id = args[0].lowercase()
        val scale = args.getOrNull(1)?.toDoubleOrNull() ?: 2.0
        val colour = args.getOrNull(2)?.let { dev.shadr.core.Rgb.parse(it) } ?: dev.shadr.core.Rgb.WHITE

        val eye = player.eyeLocation
        val at = eye.clone().add(eye.direction.multiply(maxOf(2.0, scale * 1.5)))

        val handle = "${player.name}_${id}_${++shaderHandleCounter}"
        val failure = api.spawn(
            handle = handle,
            shader = id,
            at = dev.shadr.core.spi.WorldAnchor(at.world.name, at.x, at.y, at.z),
            scale = scale,
            color = colour,
        )
        return if (failure != null) reply(sender, failure)
        else reply(sender, "placed '$id' as '$handle'; /shadr shader clear $handle to remove")
    }

    private fun editorCommand(sender: CommandSender, sub: String?): Boolean {
        val running = editor
            ?: return reply(sender, "editor is disabled; set editor.web.enabled in config.yml")

        if (!sender.hasPermission(EDITOR_PERMISSION)) {
            return reply(sender, "you do not have $EDITOR_PERMISSION")
        }

        when (sub) {
            "revoke" -> {
                val dropped = running.revokeIssued()
                return reply(sender, "revoked $dropped issued link(s); bookmarked operator URLs still work")
            }
            null, "link" -> Unit
            else -> return reply(sender, "usage: /shadr editor [link|revoke]")
        }

        val label = (sender as? Player)?.uniqueId?.toString() ?: "console"
        running.revokeIssued(label)

        val host = config.editor.web.publicHost.ifBlank {
            server.ip.ifBlank { config.editor.web.bind }
        }
        val url = running.mintUrl(label, LINK_TTL_MILLIS, host)
            ?: return reply(sender, "editor is running without authentication: ${running.url(host)}")

        val minutes = LINK_TTL_MILLIS / 60_000
        if (sender is Player) {
            sender.sendMessage(
                Component.text("shadr: ")
                    .append(
                        Component.text("open the editor")
                            .clickEvent(ClickEvent.openUrl(url))
                            .hoverEvent(HoverEvent.showText(Component.text(url))),
                    )
                    .append(Component.text(" (this link expires in $minutes minutes)")),
            )
        } else {
            sender.sendMessage("shadr editor: $url  (expires in $minutes minutes)")
        }
        return true
    }

    private fun reply(sender: CommandSender, message: String): Boolean {
        sender.sendMessage("shadr: $message")
        return true
    }

    private fun saveDefaultDirectories() {
        listOf("pages", "components", "effects", "contents/images", "shaders", "font", "sounds")
            .forEach { File(dataFolder, it).mkdirs() }
        if (!File(dataFolder, "config.yml").exists()) saveResource("config.yml", false)
        unpackBundledAssets()
    }

    private fun unpackBundledAssets() {
        val index = getResource(BundledAssets.INDEX)?.use { it.readBytes().decodeToString() }
        if (index == null) {
            logger.warning("shadr: ${BundledAssets.INDEX} missing from the jar; nothing was seeded")
            return
        }

        var written = 0
        for (entry in index.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            val targetDir = BundledAssets.TARGETS[entry.substringBefore('/')] ?: continue
            val relative = entry.substringAfter('/')
            val target = File(File(dataFolder, targetDir), relative)
            if (target.exists()) continue
            val stream = getResource("bundled/$entry") ?: run {
                logger.warning("shadr: bundled/$entry is in the index but not in the jar")
                null
            } ?: continue
            target.parentFile.mkdirs()
            stream.use { input -> target.outputStream().use { input.copyTo(it) } }
            written++
        }
        if (written > 0) logger.info("shadr: seeded $written bundled file(s) into ${dataFolder.name}/")
    }

    private companion object {
        const val BIND_ALL = "0.0.0.0"

        const val EDITOR_PERMISSION = "shadr.editor"

        const val SHADER_PERMISSION = "shadr.shader"

        const val LINK_TTL_MILLIS = 30L * 60 * 1000
    }
}
