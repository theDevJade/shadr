/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.config

import dev.shadr.core.hud.HudPositionCalculator
import dev.shadr.core.page.Node
import dev.shadr.core.page.stringKeyed
import dev.shadr.core.update.UpdateChannel
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File

data class ShadrConfig(
    val pack: PackConfig = PackConfig(),
    val rendering: RenderingConfig = RenderingConfig(),
    val editor: EditorConfig = EditorConfig(),
    val updates: UpdateConfig = UpdateConfig(),
    val experimental: ExperimentalConfig = ExperimentalConfig(),
) {
    companion object {
        fun load(file: File): ShadrConfig {
            if (!file.isFile) return ShadrConfig()
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            val map = file.inputStream().use { yaml.load<Any?>(it) } as? Map<*, *> ?: return ShadrConfig()
            return from(Node(map.stringKeyed()))
        }

        fun from(node: Node) = ShadrConfig(
            pack = PackConfig(
                applyOnJoin = node.bool("resource-pack.apply-on-join", fallback = true),
                kickOnDecline = node.bool("resource-pack.kick-on-decline"),
                kickOnFail = node.bool("resource-pack.kick-on-fail"),
                compressImages = node.bool("resource-pack.compress-images", fallback = true),
                removeDefaultHotbar = node.bool("resource-pack.remove-default-hotbar"),
                hosting = HostingMode.from(node),
                selfHostIp = node.string("resource-pack.hosting.self-host.server-ip") ?: "127.0.0.1",
                selfHostPort = node.int("resource-pack.hosting.self-host.pack-port", fallback = 8123),
                externalUrl = node.string("resource-pack.hosting.external-host.url"),
                joinChat = node.bool("resource-pack.messages.chat.enabled"),
                joinTitle = node.bool("resource-pack.messages.title.enabled", fallback = true),
                joinSound = node.bool("resource-pack.messages.sound.enabled", fallback = true),
                joinSoundVolume = node.number("resource-pack.messages.sound.volume", fallback = 1.0),
            ),
            rendering = RenderingConfig(
                fixShaders = node.bool("rendering.fix-shaders", "editor.rendering.fix-shaders"),
                fixShadersYOffset = node.number(
                    "rendering.fix-shaders-y-offset", "editor.rendering.fix-shaders-y-offset", fallback = 2.1006,
                ),
                fixShadersForwardOffset = node.number(
                    "rendering.fix-shaders-forward-offset",
                    "editor.rendering.fix-shaders-forward-offset",
                    fallback = 0.3781,
                ),
                fixShadersLayerGap = node.number(
                    "rendering.fix-shaders-layer-gap",
                    "editor.rendering.fix-shaders-layer-gap",
                    fallback = HudPositionCalculator.DEFAULT_FIX_SHADERS_LAYER_GAP,
                ),
                hudScaleDivisor = node.number(
                    "rendering.hud-scale-divisor", "editor.rendering.hud-scale-divisor", fallback = 1000.0,
                ),
            ),
            editor = EditorConfig(
                placeholderRefreshTicks = node.int("editor.placeholders.text-refresh", fallback = 20),
                autosave = node.bool("editor.autosave.enabled", fallback = true),
                autosaveIntervalSeconds = node.int("editor.autosave.interval-seconds", fallback = 600),
                undoLimit = node.int("editor.history.undo-limit", fallback = 50),
                maxPageElements = node.int("editor.max-page-elements", fallback = 500),
                soundsEnabled = node.bool("editor.sounds.enabled", fallback = true),
                soundVolume = node.number("editor.sounds.volume", fallback = 1.0),
                web = EditorWebConfig(
                    enabled = node.bool("editor.web.enabled"),
                    port = node.int("editor.web.port", fallback = 8124),
                    bind = node.string("editor.web.bind") ?: "127.0.0.1",
                    token = node.string("editor.web.token")?.trim().orEmpty(),
                    allowInsecure = node.bool("editor.web.allow-insecure"),
                    publicHost = node.string("editor.web.public-host") ?: "",
                    uiDir = node.string("editor.web.ui-dir") ?: "editor-web",
                    tlsKeystore = node.string("editor.web.tls-keystore")?.trim().orEmpty(),
                    tlsPassword = node.string("editor.web.tls-password").orEmpty(),
                    tlsKeyPassword = node.string("editor.web.tls-key-password").orEmpty(),
                ),
            ),
            updates = UpdateConfig(
                checkEnabled = node.bool("updates.check", fallback = true),
                download = node.bool("updates.download"),
                notifyOps = node.bool("updates.notify-ops", fallback = true),
                intervalHours = node.int("updates.interval-hours", fallback = 6),
                channel = UpdateChannel.entries.firstOrNull {
                    it.name.equals(node.string("updates.channel")?.trim()?.replace('-', '_'), ignoreCase = true)
                } ?: UpdateChannel.AUTO,
                repo = node.string("updates.repository")?.trim().orEmpty().ifBlank { UpdateConfig.DEFAULT_REPO },
                signingKey = node.string("updates.signing-key")?.trim().orEmpty(),
            ),
            experimental = ExperimentalConfig(
                mousePrediction = node.bool("experimental.mouse-prediction", fallback = true),
            ),
        )
    }
}

data class PackConfig(
    val applyOnJoin: Boolean = true,
    val kickOnDecline: Boolean = false,
    val kickOnFail: Boolean = false,
    val compressImages: Boolean = true,
    val removeDefaultHotbar: Boolean = false,
    val hosting: HostingMode = HostingMode.DEFAULT_PACK,
    val selfHostIp: String = "127.0.0.1",
    val selfHostPort: Int = 8123,
    val externalUrl: String? = null,
    val joinChat: Boolean = false,
    val joinTitle: Boolean = true,
    val joinSound: Boolean = true,
    val joinSoundVolume: Double = 1.0,
)

enum class HostingMode {
    DEFAULT_PACK,

    SELF_HOST,

    EXTERNAL_HOST,

    EXTERNAL_PACK;

    companion object {
        fun from(node: Node): HostingMode = when {
            node.bool("resource-pack.hosting.external-pack.enabled") -> EXTERNAL_PACK
            node.bool("resource-pack.hosting.external-host.enabled") -> EXTERNAL_HOST
            node.bool("resource-pack.hosting.self-host.enabled") -> SELF_HOST
            else -> DEFAULT_PACK
        }
    }
}

data class RenderingConfig(
    val fixShaders: Boolean = false,
    val fixShadersYOffset: Double = 2.1006,
    val fixShadersForwardOffset: Double = 0.3781,
    val fixShadersLayerGap: Double = HudPositionCalculator.DEFAULT_FIX_SHADERS_LAYER_GAP,
    val hudScaleDivisor: Double = 1000.0,
)

data class EditorConfig(
    val placeholderRefreshTicks: Int = 20,
    val autosave: Boolean = true,
    val autosaveIntervalSeconds: Int = 600,
    val undoLimit: Int = 50,
    val maxPageElements: Int = 500,
    val soundsEnabled: Boolean = true,
    val soundVolume: Double = 1.0,
    val web: EditorWebConfig = EditorWebConfig(),
)

data class EditorWebConfig(
    val enabled: Boolean = false,
    val port: Int = 8124,
    val bind: String = "127.0.0.1",
    val token: String = "",
    val allowInsecure: Boolean = false,
    val publicHost: String = "",
    val uiDir: String = "editor-web",
    val tlsKeystore: String = "",
    val tlsPassword: String = "",
    val tlsKeyPassword: String = "",
)

data class UpdateConfig(
    val checkEnabled: Boolean = true,
    val download: Boolean = false,
    val notifyOps: Boolean = true,
    val intervalHours: Int = 6,
    val channel: UpdateChannel = UpdateChannel.AUTO,
    val repo: String = DEFAULT_REPO,
    val signingKey: String = "",
) {
    companion object {
        const val DEFAULT_REPO = "theDevJade/shadr"
    }
}

data class ExperimentalConfig(val mousePrediction: Boolean = true)
