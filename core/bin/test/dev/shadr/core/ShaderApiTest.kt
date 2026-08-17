/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.shader.ShaderApi
import dev.shadr.core.shader.ShaderDef
import dev.shadr.core.shader.ShaderRegistry
import dev.shadr.core.spi.BillboardMode
import dev.shadr.core.spi.CameraControl
import dev.shadr.core.spi.HudSink
import dev.shadr.core.spi.InputSource
import dev.shadr.core.spi.PlatformBridge
import dev.shadr.core.spi.PlayerRegistry
import dev.shadr.core.spi.ResourcePackService
import dev.shadr.core.spi.WorldAnchor
import dev.shadr.core.spi.WorldDisplays
import dev.shadr.core.spi.WorldShaderSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShaderApiTest {
    private class FakeWorld : WorldDisplays {
        val spawned = mutableMapOf<String, WorldShaderSpec>()
        var spawnCalls = 0
        var despawnCalls = 0

        override fun spawn(spec: WorldShaderSpec): Boolean {
            spawnCalls++
            spawned[spec.handle] = spec
            return true
        }

        override fun despawn(handle: String): Boolean {
            despawnCalls++
            return spawned.remove(handle) != null
        }

        override fun despawnAll(): Int {
            val count = spawned.size
            spawned.clear()
            return count
        }

        override fun handles() = spawned.keys.sorted()
    }

    private class Bridge(private val world: WorldDisplays) : PlatformBridge {
        override fun hud(): HudSink = error("unused")
        override fun camera(): CameraControl = error("unused")
        override fun input(): InputSource = error("unused")
        override fun pack(): ResourcePackService = error("unused")
        override fun players(): PlayerRegistry = error("unused")
        override fun world(): WorldDisplays = world
    }

    private val valid = "vec4 shadr_main(vec2 uv, float t, vec4 c) { return vec4(uv, 0.0, 1.0); }"

    private fun api(world: WorldDisplays, vararg ids: String) = ShaderApi(Bridge(world)) {
        ShaderRegistry(ids.map { ShaderDef(it, valid) })
    }

    private val here = WorldAnchor("world", 1.0, 64.0, 2.0)

    @Test
    fun `spawning a known shader succeeds and is listed`() {
        val world = FakeWorld()
        val api = api(world, "planet", "aurora")

        assertNull(api.spawn("a", "planet", here, scale = 3.0, color = Rgb.parse("ff8800")!!))
        assertEquals(listOf("a"), api.placed())

        val spec = world.spawned.getValue("a")
        assertEquals("planet", spec.shader)
        assertEquals(3.0, spec.scale)
        assertEquals(0xFF8800, spec.color.packed)
        assertEquals(BillboardMode.CENTER, spec.billboard)
    }

    @Test
    fun `an unknown id lists what is actually installed`() {
        val message = api(FakeWorld(), "planet", "aurora").spawn("a", "plant", here)
        assertNotNull(message)
        assertTrue(message.contains("plant"), message)
        assertTrue(message.contains("planet") && message.contains("aurora"), message)
    }

    @Test
    fun `with nothing installed the message says how to install one`() {
        val message = api(FakeWorld()).spawn("a", "planet", here)
        assertNotNull(message)
        assertTrue(message.contains("shaders/items"), message)
    }

    @Test
    fun `reusing a handle replaces`() {
        val world = FakeWorld()
        val api = api(world, "planet")

        repeat(5) { api.spawn("same", "planet", here) }

        assertEquals(1, world.spawned.size)
        assertEquals(listOf("same"), api.placed())
        assertEquals(5, world.despawnCalls, "each spawn must clear the previous one first")
    }

    @Test
    fun `a platform without world support says so`() {
        val api = ShaderApi(Bridge(WorldDisplays.Unsupported)) {
            ShaderRegistry(listOf(ShaderDef("planet", valid)))
        }
        assertTrue(!api.supportsWorld)
        val message = api.spawn("a", "planet", here)
        assertNotNull(message)
        assertTrue(message.contains("cannot place world displays"), message)
    }

    @Test
    fun `a non-positive scale is refused`() {
        val world = FakeWorld()
        assertNotNull(api(world, "planet").spawn("a", "planet", here, scale = 0.0))
        assertEquals(0, world.spawnCalls)
    }

    @Test
    fun `despawn and despawnAll report what they removed`() {
        val world = FakeWorld()
        val api = api(world, "planet")
        api.spawn("a", "planet", here)
        api.spawn("b", "planet", here)

        assertTrue(api.despawn("a"))
        assertTrue(!api.despawn("a"), "removing twice should report nothing to remove")
        assertEquals(1, api.despawnAll())
        assertEquals(emptyList(), api.placed())
    }

    @Test
    fun `the item identity matches what the pack generates`() {
        assertEquals("minecraft:leather_horse_armor", ShaderApi.BASE_ITEM)
        assertEquals("minecraft:shadr/shader_planet", ShaderApi.itemModelOf("planet"))
        assertEquals(ShaderDef("planet", valid).itemModel, ShaderApi.itemModelOf("planet"))
    }

    @Test
    fun `the registry is re-read on every call`() {
        var ids = listOf("planet")
        val api = ShaderApi(Bridge(FakeWorld())) { ShaderRegistry(ids.map { ShaderDef(it, valid) }) }

        assertTrue(!api.exists("aurora"))
        ids = listOf("planet", "aurora")
        assertTrue(api.exists("aurora"), "a reload should be visible without rebuilding the API")
    }
}
