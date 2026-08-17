/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.spi

import dev.shadr.core.PlayerId
import dev.shadr.core.ScreenPos
import dev.shadr.core.hud.HudDraw

interface PlatformBridge {
    fun hud(): HudSink
    fun camera(): CameraControl
    fun input(): InputSource
    fun pack(): ResourcePackService
    fun players(): PlayerRegistry

    fun world(): WorldDisplays = WorldDisplays.Unsupported
}

data class WorldAnchor(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

enum class BillboardMode { FIXED, VERTICAL, HORIZONTAL, CENTER }

data class WorldShaderSpec(
    val handle: String,
    val shader: String,
    val at: WorldAnchor,
    val scale: Double = 1.0,
    val color: dev.shadr.core.Rgb = dev.shadr.core.Rgb.WHITE,
    val billboard: BillboardMode = BillboardMode.CENTER,
    val viewRange: Float? = null,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
)

interface WorldDisplays {
    /** @return false when the platform or the world could not place it. */
    fun spawn(spec: WorldShaderSpec): Boolean

    /** @return true if something was removed. */
    fun despawn(handle: String): Boolean

    /** @return how many were removed. */
    fun despawnAll(): Int

    fun handles(): List<String>

    val isSupported: Boolean get() = true

    object Unsupported : WorldDisplays {
        override fun spawn(spec: WorldShaderSpec) = false
        override fun despawn(handle: String) = false
        override fun despawnAll() = 0
        override fun handles(): List<String> = emptyList()
        override val isSupported: Boolean get() = false
    }
}

interface HudSink {
    fun apply(player: PlayerId, draws: List<HudDraw>)

    fun clear(player: PlayerId)

    fun mount(player: PlayerId)
}

interface CameraControl {
    fun start(player: PlayerId)
    fun stop(player: PlayerId)
    fun isActive(player: PlayerId): Boolean

    fun setClickTargetsEnabled(player: PlayerId, enabled: Boolean)
}

data class InputSample(
    val player: PlayerId,
    val cursor: ScreenPos,
    val leftClick: Boolean = false,
    val rightClick: Boolean = false,
    val pingMillis: Int = 0,
)

interface InputSource {
    fun onSample(listener: (InputSample) -> Unit)

    fun onKey(key: String, listener: (PlayerId) -> Unit)
}

interface ResourcePackService {
    fun send(player: PlayerId, url: String, sha1: ByteArray, forced: Boolean = true)
}

interface PlayerRegistry {
    fun online(): Collection<PlayerId>
    fun onJoin(listener: (PlayerId) -> Unit)
    fun onQuit(listener: (PlayerId) -> Unit)
}
