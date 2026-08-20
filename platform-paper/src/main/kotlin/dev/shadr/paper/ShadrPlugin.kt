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
    private lateinit var lang: dev.shadr.core.config.Lang
    private lateinit var bridge: PaperBridge
    private lateinit var renderer: PageRenderer

    private var metrics: dev.shadr.core.text.MetricsTable = dev.shadr.core.text.MetricsTable.EMPTY
    private lateinit var actionRunner: ActionRunner
    private var textCapture: AnvilTextCapture? = null

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
    private var editorFailure: String? = null
    private var updates: UpdateService? = null

    override fun onEnable() {
        saveDefaultDirectories()
        config = loadConfig()
        lang = loadLang()

        val backend = if (config.rendering.packetEntities) {
            Displays.backendOrNull { logger.warning("shadr: $it") }
        } else {
            null
        }

        textCapture = AnvilTextCapture(
            onValue = { player, elementId, value ->
                sessions[player.uuid]?.let { session ->
                    if (session.setInputValue(elementId, value)) {
                        applyHud(player, session.draws())
                    }
                }
            },
            log = { message -> logger.info("shadr: $message") },
        )

        bridge = PaperBridge(
            this,
            backend = backend,
            worldShaderState = File(dataFolder, "world-shaders.yml"),
            postEffects = {
                environment.isEnabled(dev.shadr.core.shader.EnvironmentEffect.FROSTED_GLASS) ||
                    environment.isEnabled(dev.shadr.core.shader.EnvironmentEffect.VIDEO)
            },
        )
        server.pluginManager.registerEvents(bridge, this)
        textCapture?.let { server.pluginManager.registerEvents(AnvilTextListener(it), this) }

        renderer = buildRenderer()
        actionRunner = ActionRunner(
            PaperActionHost(
                plugin = this,
                openPageHandler = { player, page, replacing -> openPage(player, page, replacing) },
                closePageHandler = ::closePage,
                placeholders = ::placeholderResolver,
            ),
        )

        reload()
        wireInput()
        startEditor()
        startUpdates()

        server.servicesManager.register(
            dev.shadr.core.api.ShadrApi::class.java,
            PaperShadrApi(this),
            this,
            org.bukkit.plugin.ServicePriority.Normal,
        )

        server.scheduler.runTaskTimer(this, Runnable { tick() }, 1L, 1L)
        detectPlaceholderApi()
        logger.info(
            "shadr enabled: ${pages.size} page(s), pack ${packUrl ?: "not hosted"}, " +
                if (bridge.packetBacked) "packet-only entities" else "server display entities",
        )
    }

    override fun onDisable() {
        sessions.keys.toList().forEach { closePage(PlayerId(it)) }
        textCapture?.releaseAll()
        bridge.cameraControl.stopAll()
        packHost.stop()
        editor?.stop()
        logger.info("shadr disabled")
    }

    private fun restartEditor() {
        editor?.stop()
        editor = null
        startEditor()
    }

    private fun startEditor() {
        editorFailure = null
        if (!config.editor.web.enabled) {
            logger.info(
                "shadr: the web editor is off; editor.web.enabled is not true in ${configFile().absolutePath}",
            )
            return
        }

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
                images = dev.shadr.pack.AtlasImageSource(File(dataFolder, "contents")) { lastAtlas },
                videos = dev.shadr.pack.LibraryVideoSource(File(dataFolder, "contents")),
                log = { logger.info("shadr: $it") },
                onFailure = { editorFailure = it },
            )
        }.getOrElse {
            editorFailure = it.message ?: it::class.simpleName
            logger.severe("shadr: editor not started: $editorFailure")
            null
        }
        editor?.metrics = metrics
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
            applyHud(PlayerId(uuid), session.draws())
        }
    }

    private fun rebuildPackForShaders(): Boolean = runCatching {
        rebuildPack()
        if (server.isPrimaryThread) refreshHeaderEmitters() else {
            server.scheduler.runTask(this, Runnable { refreshHeaderEmitters() })
        }
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

    @Volatile
    private var lastAtlas = UiImageAtlas.BuildResult(emptyMap(), emptyList())

    private var videoCacheKey: String? = null

    @Volatile
    private var videoCache: List<dev.shadr.pack.VideoAssets.Source> = emptyList()

    private fun videoSources(): List<dev.shadr.pack.VideoAssets.Source> {
        val dir = File(File(dataFolder, "contents"), dev.shadr.pack.VideoLibrary.FOLDER)
        val key = dir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.path }
            .joinToString(";") { "${it.path}:${it.length()}:${it.lastModified()}" }

        if (key == videoCacheKey) return videoCache

        val result = dev.shadr.pack.VideoLibrary(File(dataFolder, "contents")).load()
        result.issues.forEach { logger.warning("video: $it") }
        videoCacheKey = key
        videoCache = result.sources
        return result.sources
    }

    private fun buildRenderer() = PageRenderer(
        fixShaders = config.rendering.fixShaders,
        fixShadersLayerGap = config.rendering.fixShadersLayerGap,
        metrics = metrics,
    )

    private fun rebuildPack() = synchronized(packBuildLock) {
        val packRoot = File(dataFolder, "resourcepack/pack")
        val generator = PackGenerator(
            shaderSrc = File(dataFolder, "shaders"),
            fontDir = File(dataFolder, "font"),
            soundDir = File(dataFolder, "sounds"),
            shaders = shaderLoader.load().also { _ ->
                shaderLoader.issues.forEach { logger.warning("shader: $it") }
            },
            environment = environment.all(),
            environmentParams = environment.allParams(),
            videos = videoSources(),
            stream = config.stream.takeIf { it.enabled }?.geometry(),
            hideAnvilScreen = config.pack.hideAnvilScreen,
        )
        generator.build(packRoot)
        metrics = generator.metrics
        if (::renderer.isInitialized) renderer = buildRenderer()
        editor?.metrics = metrics

        val atlas = UiImageAtlas(
            contentsDir = File(dataFolder, "contents"),
            packRoot = packRoot,
            stateFile = File(dataFolder, "resourcepack/generated/uiimages_codepoints.properties"),
        ).rebuild()
        atlas.issues.forEach { logger.warning("image atlas: $it") }
        lastAtlas = atlas

        archive = PackBuilder.build(
            packRoot = packRoot,
            outFile = File(dataFolder, "resourcepack/generated/shadr-pack.zip"),
            removeVanillaHud = config.pack.removeDefaultHotbar,
            compressImages = config.pack.compressImages,
        )
        packUrl = startHosting()

        config.pack.mergeInto?.takeIf { it.isNotBlank() }?.let { destination ->
            val target = File(destination).let { if (it.isAbsolute) it else File(dataFolder, destination) }
            val written = PackMerge.copy(packRoot, target)
            logger.info("shadr: merged $written pack file(s) into ${target.path}")
        }
        packListeners.forEach { runCatching { it(packRoot) } }
    }

    private val packListeners = java.util.concurrent.CopyOnWriteArrayList<(File) -> Unit>()

    fun onPackBuilt(listener: (File) -> Unit) {
        packListeners += listener
    }

    fun packRoot(): File = File(dataFolder, "resourcepack/pack")

    fun packArchive(): PackArchive? = archive

    fun packUrlOrNull(): String? = packUrl

    fun packSendsToPlayers(): Boolean = config.pack.hosting.sends

    fun pageNames(): Set<String> = pages.keys.toSet()

    fun hasPageOpen(player: PlayerId): Boolean = sessions.containsKey(player.uuid)

    fun openPageIfPresent(player: PlayerId, page: String, replacing: Boolean): Boolean {
        if (!pages.containsKey(page)) return false
        openPage(player, page, replacing)
        return true
    }

    fun registerAction(verb: String, handler: dev.shadr.core.api.ActionHandler): Boolean =
        actionRunner.register(verb, handler)

    fun reload() {
        val previousEditor = config.editor.web
        config = loadConfig()
        lang = loadLang()
        if (config.editor.web != previousEditor) restartEditor()

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
        refreshOpenPages()
    }

    private fun refreshOpenPages() {
        for ((uuid, session) in sessions) {
            val fresh = pages[session.currentPage.name] ?: continue
            session.refreshPage(fresh)
            applyHud(PlayerId(uuid), session.draws())
        }
    }

    private fun startHosting(): String? {
        val built = archive ?: return null
        return when (config.pack.hosting) {
            HostingMode.MERGE_ONLY, HostingMode.EXTERNAL_PACK -> null
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
        (bridge.input() as? PaperInput)?.screenFor = { player ->
            sessions[player.uuid]?.currentPage?.screen
        }
        bridge.players().onJoin { player ->
            if (config.pack.applyOnJoin) sendPack(player)
            if (worldEffectsActive()) {
                bridge.hud().mount(player)
                applyHud(player, emptyList())
            }
        }
        bridge.players().onQuit { player ->
            sessions.remove(player.uuid)
            player.bukkitPlayer()?.let { textCapture?.release(it) }
        }

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
            if (sample.leftClick || sample.rightClick) openFocusedInput(sample.player, session)
            if (changed || sample.leftClick || sample.rightClick) {
                applyHud(sample.player, session.draws())
            }
        }
    }

    private fun openFocusedInput(player: PlayerId, session: dev.shadr.core.session.UiSession) {
        val capture = textCapture ?: return
        val bukkit = player.bukkitPlayer() ?: return
        val focused = session.focusedInput
        if (focused == null) {
            capture.release(bukkit)
            return
        }
        if (capture.focusedElement(bukkit) == focused) return
        val element = session.currentPage.elements.firstOrNull { it.id == focused } ?: return
        val input = element.input ?: return
        capture.focus(
            player = bukkit,
            elementId = focused,
            current = session.inputValue(focused) ?: input.value,
            maxLength = input.maxLength,
        )
    }

    private fun PlayerId.bukkitPlayer() =
        runCatching { Bukkit.getPlayer(java.util.UUID.fromString(uuid)) }.getOrNull()

    private fun tick() {
        bridge.tick()
        for (uuid in sessions.keys) {
            val player = runCatching { Bukkit.getPlayer(java.util.UUID.fromString(uuid)) }.getOrNull() ?: continue
            bridge.hudSink.ensureMounted(player)
        }
        tickStreams()
        refreshPlaceholders()
    }

    private fun tickStreams() {
        val sink = bridge.streamSink ?: return
        if (!config.stream.enabled) return
        val geometry = config.stream.geometry()
        val period = maxOf(1, (20.0 / geometry.fps).toInt())
        streamTicks += 1
        for (player in server.onlinePlayers) {
            if (!sink.isActive(player)) continue
            sink.tick(player)
            if (streamTicks % period != 0) continue
            val channel = sink.channel(player) ?: continue
            geometry.apply(channel, stream = 0, serial = sink.nextSerial(player))
            sink.push(player)
        }
    }

    private var streamTicks = 0

    private fun refreshPlaceholders() {
        val interval = config.editor.placeholderRefreshTicks
        if (interval <= 0) return
        if (++placeholderTicks < interval) return
        placeholderTicks = 0

        for ((uuid, session) in sessions) {
            if (!session.hasPlaceholders) continue
            if (session.refreshPlaceholders()) applyHud(PlayerId(uuid), session.draws())
        }
    }

    private var placeholderTicks = 0

    private fun placeholderResolver(player: PlayerId): dev.shadr.core.page.PlaceholderResolver {
        val inputs = inputResolver()
        val builtins = builtinResolver(player)
        val papi = papiResolver
            ?: return dev.shadr.core.page.PlaceholderResolver.chain(inputs, builtins)
        return dev.shadr.core.page.PlaceholderResolver.chain(inputs, builtins, papi)
    }

    private fun inputResolver() = dev.shadr.core.page.InputPlaceholders { player, id ->
        val session = sessions[player.uuid] ?: return@InputPlaceholders null
        session.inputs().entries.firstOrNull { it.key.equals(id, ignoreCase = true) }?.value
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
            applyCameraFor(player, page)
            applyHud(player, existing.draws())
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

        applyCameraFor(player, page)
        bridge.hud().mount(player)
        applyHud(player, session.draws())
    }

    private fun applyCameraFor(player: PlayerId, page: dev.shadr.core.page.Page) {
        val wanted = page.screen.locksCamera
        val active = bridge.cameraControl.isActive(player)
        if (wanted == active) return
        if (wanted) {
            bridge.camera().start(player)
            bridge.camera().setClickTargetsEnabled(player, true)
        } else {
            bridge.camera().setClickTargetsEnabled(player, false)
            bridge.camera().stop(player)
        }
    }

    fun closePage(player: PlayerId) {
        sessions.remove(player.uuid) ?: return
        val steps = listOf<Pair<String, () -> Unit>>(
            "hud clear" to { bridge.hud().clear(player) },
            "stream stop" to {
                runCatching { Bukkit.getPlayer(java.util.UUID.fromString(player.uuid)) }.getOrNull()
                    ?.let {
                        bridge.streamSink?.stop(it)
                        textCapture?.release(it)
                    }
            },
            "hud remount" to {
                if (worldEffectsActive()) {
                    bridge.hud().mount(player)
                    applyHud(player, emptyList())
                }
            },
            "camera stop" to {
                bridge.camera().setClickTargetsEnabled(player, false)
                bridge.camera().stop(player)
            },
        )
        for ((label, step) in steps) {
            runCatching(step).onFailure {
                logger.severe("shadr: close step '$label' failed for ${player.uuid}: ${it.message}")
            }
        }
    }

    private fun worldEffectsActive(): Boolean = environment.activeWorldEffects().isNotEmpty()

    private fun applyHud(player: PlayerId, draws: List<dev.shadr.core.hud.HudDraw>) {
        bridge.hud().apply(player, draws + dev.shadr.core.hud.HeaderEmitter.draws(worldEffectsActive()))
    }

    private fun refreshHeaderEmitters() {
        for (online in server.onlinePlayers) {
            val id = PlayerId(online.uniqueId.toString())
            val session = sessions[id.uuid]
            if (session == null) {
                bridge.hud().mount(id)
                applyHud(id, emptyList())
            } else {
                applyHud(id, session.draws())
            }
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                reload()
                say(sender, "reloaded", "pages" to pages.size)
            }
            "open" -> {
                val player = sender as? Player ?: return say(sender, "players-only")
                val name = args.getOrNull(1) ?: return reply(sender, "usage: /shadr open <page>")
                openPage(PlayerId(player.uniqueId.toString()), name)
            }
            "close" -> {
                val player = sender as? Player ?: return say(sender, "players-only")
                closePage(PlayerId(player.uniqueId.toString()))
            }
            "pack" -> {
                val player = sender as? Player ?: return say(sender, "players-only")
                if (!config.pack.hosting.sends) {
                    return say(sender, "pack-not-sent", "mode" to config.pack.hosting.name.lowercase())
                }
                sendPack(PlayerId(player.uniqueId.toString()))
                say(sender, "pack-sent")
            }
            "pages" -> say(sender, "pages", "pages" to pages.keys.sorted().joinToString(", "))
            "effects" -> return effectsCommand(sender, args.drop(1))
            "editor" -> return editorCommand(sender, args.getOrNull(1)?.lowercase())
            "shader" -> return shaderCommand(sender, args.drop(1))
            "stream" -> return streamCommand(sender, args.drop(1))
            "update" -> {
                val service = updates
                    ?: return say(sender, "update-disabled")
                return service.command(sender, args.getOrNull(1)?.lowercase())
            }
            else -> say(sender, "usage")
        }
        return true
    }

    private fun streamCommand(sender: CommandSender, args: List<String>): Boolean {
        val player = sender as? Player ?: return say(sender, "players-only")
        val sink = bridge.streamSink
            ?: return reply(sender, "the map stream needs packet entities; set rendering.packet-entities: true")
        if (!config.stream.enabled) {
            return reply(sender, "the map stream is off; set stream.enabled: true in config.yml and /shadr reload")
        }

        val geometry = config.stream.geometry()
        when (args.firstOrNull()?.lowercase()) {
            "stop" -> {
                sink.stop(player)
                return reply(sender, "stream stopped")
            }
            "status" -> {
                return reply(
                    sender,
                    if (!sink.isActive(player)) {
                        "stream inactive"
                    } else {
                        "stream active: ${geometry.slots} slot(s), " +
                            "${geometry.regionWidth}x${geometry.regionHeight} ingest, " +
                            "${sink.bytesSent(player)} byte(s) sent"
                    },
                )
            }
            null, "start", "test" -> {
                if (!bridge.cameraControl.isActive(PlayerId(player.uniqueId.toString()))) {
                    return reply(sender, "open a page first; the ingest passes ride the camera session's post chain")
                }
                sink.start(player, geometry.slots, geometry.mapIdBase)
                val channel = sink.channel(player)
                    ?: return reply(sender, "stream failed to start")
                for (slot in 0 until geometry.slots) channel.ramp(slot)
                geometry.apply(channel, stream = 0, serial = sink.nextSerial(player))
                sink.push(player)
                return reply(
                    sender,
                    "stream started: ${geometry.slots} slot(s), ${sink.bytesSent(player)} byte(s) sent" +
                        if (geometry.probe) "; solid green means every word survived" else "",
                )
            }
        }
        return reply(sender, "usage: /shadr stream <start|stop|status>")
    }

    private fun shaderCommand(sender: CommandSender, args: List<String>): Boolean {
        if (!sender.hasPermission(SHADER_PERMISSION)) {
            return say(sender, "no-permission", "permission" to SHADER_PERMISSION)
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
        val running = editor ?: return if (config.editor.web.enabled) {
            say(sender, "editor-not-started", "reason" to (editorFailure ?: "see the server console"))
        } else {
            say(sender, "editor-disabled")
        }

        if (!sender.hasPermission(EDITOR_PERMISSION)) {
            return say(sender, "no-permission", "permission" to EDITOR_PERMISSION)
        }

        when (sub) {
            "revoke" -> {
                val dropped = running.revokeIssued()
                return say(sender, "editor-revoked", "count" to dropped)
            }
            null, "link" -> Unit
            else -> return say(sender, "editor-usage")
        }

        val label = (sender as? Player)?.uniqueId?.toString() ?: "console"
        running.revokeIssued(label)

        val host = config.editor.web.publicHost.ifBlank {
            server.ip.ifBlank { config.editor.web.bind }
        }
        val url = running.mintUrl(label, LINK_TTL_MILLIS, host)
            ?: return say(sender, "editor-unauthenticated", "url" to running.url(host))

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

    private fun effectsCommand(sender: CommandSender, args: List<String>): Boolean {
        val name = args.firstOrNull()?.lowercase()
        if (name == null) {
            for (effect in dev.shadr.core.shader.EnvironmentEffect.entries) {
                val state = if (environment.isEnabled(effect)) "on" else "off"
                val host = if (effect.isWorldEffect) " (needs Fabulous graphics)" else ""
                reply(sender, "${effect.id}: $state$host  ${effect.title}")
            }
            return true
        }

        val effect = dev.shadr.core.shader.EnvironmentEffect.parse(name)
            ?: return reply(sender, "no such effect: $name")

        val key = args.getOrNull(1)
        if (key == null) {
            reply(sender, "${effect.id}: ${if (environment.isEnabled(effect)) "on" else "off"}")
            for ((setting, value) in environment.paramsOf(effect)) {
                reply(sender, "  $setting = $value")
            }
            return true
        }

        val raw = args.getOrNull(2)
            ?: return reply(sender, "usage: /shadr effects ${effect.id} <setting> <value>")

        if (key.equals("enabled", true)) {
            val on = raw.toBooleanStrictOrNull()
                ?: return reply(sender, "expected true or false, got $raw")
            environment.set(effect, on)
            rebuildPackForShaders()
            return reply(sender, "${effect.id} is now ${if (on) "on" else "off"}")
        }

        if (key.equals("preset", true)) {
            val presets = dev.shadr.core.shader.GradingPresets.load(File(dataFolder, "shaders"))
            val preset = presets[raw]
                ?: return reply(sender, "no such preset: $raw (${presets.keys.joinToString(", ")})")
            val applied = environment.applyPreset(effect, preset)
            rebuildPackForShaders()
            return reply(sender, "applied $raw to ${effect.id} ($applied settings)")
        }

        val value = raw.toDoubleOrNull()
            ?: dev.shadr.core.Rgb.parse(raw)?.packed?.toDouble()
            ?: return reply(sender, "expected a number or a colour, got $raw")

        if (!environment.setParam(effect, key, value)) {
            val known = effect.params.joinToString(", ") { it.key }
            return reply(sender, "${effect.id} has no setting $key (${known.ifEmpty { "none" }})")
        }
        rebuildPackForShaders()
        return reply(sender, "${effect.id}.$key = ${environment.paramsOf(effect)[key]}")
    }

    private fun reply(sender: CommandSender, message: String): Boolean {
        sender.sendMessage(lang["prefix"] + message)
        return true
    }

    private fun say(sender: CommandSender, key: String, vararg placeholders: Pair<String, Any?>) =
        reply(sender, lang.get(key, *placeholders))

    private fun configFile() = File(dataFolder, "config.yml")

    private fun loadConfig(): ShadrConfig = runCatching { ShadrConfig.load(configFile()) }.getOrElse {
        val kept = if (::config.isInitialized) "keeping the settings already loaded" else "falling back to defaults"
        logger.severe("shadr: ${configFile().absolutePath} could not be read ($it), $kept")
        if (::config.isInitialized) config else ShadrConfig()
    }

    private fun loadLang(): dev.shadr.core.config.Lang {
        val file = File(dataFolder, "lang.yml")
        if (!file.isFile) {
            runCatching {
                dataFolder.mkdirs()
                file.writeText(dev.shadr.core.config.Lang.defaultsYaml())
            }.onFailure { logger.warning("shadr: could not write lang.yml (${it.message})") }
        }
        return dev.shadr.core.config.Lang.load(file)
    }

    private fun saveDefaultDirectories() {
        listOf("pages", "components", "effects", "contents/images", "shaders", "font", "sounds", "editor-web")
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

        val entries = index.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val shipped = entries.mapTo(mutableSetOf()) { it.substringBefore('/') }
        val stale = BundledAssets.GENERATED.filterTo(mutableSetOf()) { it in shipped && !isStamped(it) }
        val refreshed = BundledAssets.REFRESHED
            .filterKeys { it in shipped && !isStamped(it) }
            .values.flatten()

        var written = 0
        for (entry in entries) {
            val group = entry.substringBefore('/')
            val targetDir = BundledAssets.TARGETS[group] ?: continue
            val relative = entry.substringAfter('/')
            val target = File(File(dataFolder, targetDir), relative)
            val refresh = refreshed.any { entry.startsWith(it) }
            if (target.exists() && group !in stale && !refresh) continue
            val stream = getResource("bundled/$entry") ?: run {
                logger.warning("shadr: bundled/$entry is in the index but not in the jar")
                null
            } ?: continue
            target.parentFile.mkdirs()
            stream.use { input -> target.outputStream().use { input.copyTo(it) } }
            written++
        }
        stale.forEach { stamp(it) }
        BundledAssets.REFRESHED.keys.filter { it in shipped }.forEach { stamp(it) }
        if (written > 0) logger.info("shadr: seeded $written bundled file(s) into ${dataFolder.name}/")
    }

    private fun stampFile(group: String): File? =
        BundledAssets.TARGETS[group]?.let { File(File(dataFolder, it), BundledAssets.STAMP) }

    private fun isStamped(group: String): Boolean {
        val file = stampFile(group) ?: return true
        val seeded = runCatching { file.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        return seeded == buildFingerprint()
    }

    private fun buildFingerprint(): String = runCatching {
        "${description.version}:${file.length()}:${file.lastModified()}"
    }.getOrDefault(description.version)

    private fun stamp(group: String) {
        val file = stampFile(group) ?: return
        runCatching {
            file.parentFile.mkdirs()
            file.writeText(buildFingerprint() + "\n")
        }.onFailure { logger.warning("shadr: could not stamp ${file.parentFile.name} (${it.message})") }
    }

    private companion object {
        const val BIND_ALL = "0.0.0.0"

        const val EDITOR_PERMISSION = "shadr.editor"

        const val SHADER_PERMISSION = "shadr.shader"

        const val LINK_TTL_MILLIS = 30L * 60 * 1000
    }
}
