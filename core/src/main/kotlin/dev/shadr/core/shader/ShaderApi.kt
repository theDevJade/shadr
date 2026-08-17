/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.shader

import dev.shadr.core.Rgb
import dev.shadr.core.spi.BillboardMode
import dev.shadr.core.spi.PlatformBridge
import dev.shadr.core.spi.WorldAnchor
import dev.shadr.core.spi.WorldShaderSpec

class ShaderApi(
    private val bridge: PlatformBridge,
    private val registry: () -> ShaderRegistry,
) {

    fun shaders(): List<ShaderDef> = registry().shaders

    fun exists(id: String): Boolean = registry()[id] != null

    val supportsWorld: Boolean get() = bridge.world().isSupported

    @JvmOverloads
    fun spawn(
        handle: String,
        shader: String,
        at: WorldAnchor,
        scale: Double = 1.0,
        color: Rgb = Rgb.WHITE,
        billboard: BillboardMode = BillboardMode.CENTER,
        viewRange: Float? = null,
    ): String? {
        val world = bridge.world()
        if (!world.isSupported) return "this platform cannot place world displays"
        if (!exists(shader)) {
            val known = shaders().joinToString(", ") { it.id }
            return if (known.isEmpty()) {
                "no shaders are installed; drop one in shaders/items and reload"
            } else {
                "no shader called '$shader'; installed: $known"
            }
        }
        if (scale <= 0.0) return "scale must be positive"

        world.despawn(handle)

        val ok = world.spawn(
            WorldShaderSpec(
                handle = handle,
                shader = shader,
                at = at,
                scale = scale,
                color = color,
                billboard = billboard,
                viewRange = viewRange,
            ),
        )
        return if (ok) null else "the platform refused to spawn it (is the world loaded?)"
    }

    fun despawn(handle: String): Boolean = bridge.world().despawn(handle)

    fun despawnAll(): Int = bridge.world().despawnAll()

    fun placed(): List<String> = bridge.world().handles()

    companion object {
        @JvmStatic
        val BASE_ITEM = "minecraft:leather_horse_armor"

        @JvmStatic
        fun itemModelOf(id: String): String = "minecraft:shadr/shader_$id"
    }
}
