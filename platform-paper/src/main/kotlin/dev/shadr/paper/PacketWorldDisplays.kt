/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.Rgb
import dev.shadr.core.spi.BillboardMode
import dev.shadr.core.spi.WorldAnchor
import dev.shadr.core.spi.WorldDisplays
import dev.shadr.core.spi.WorldShaderSpec
import dev.shadr.paper.nms.FakeEntityKind
import dev.shadr.paper.nms.PacketBackend
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.util.UUID

class PacketWorldDisplays(
    private val backend: PacketBackend,
    private val stateFile: File,
    private val log: (String) -> Unit,
) : WorldDisplays {
    private val placed = LinkedHashMap<Int, WorldShaderSpec>()
    private val shown = mutableMapOf<UUID, MutableMap<Int, Int>>()
    private var nextLocal = 0
    private var refreshTicks = REFRESH_INTERVAL_TICKS

    init {
        load()
    }

    override fun spawn(spec: WorldShaderSpec): Boolean {
        if (Bukkit.getWorld(spec.at.world) == null) return false
        placed[nextLocal++] = spec
        save()
        Bukkit.getOnlinePlayers().forEach { refresh(it) }
        return true
    }

    override fun despawn(handle: String): Boolean {
        val keys = placed.filterValues { it.handle == handle }.keys.toList()
        if (keys.isEmpty()) return false
        keys.forEach { placed.remove(it) }
        save()
        Bukkit.getOnlinePlayers().forEach { refresh(it) }
        return true
    }

    override fun despawnAll(): Int {
        val count = placed.size
        if (count == 0) return 0
        placed.clear()
        save()
        Bukkit.getOnlinePlayers().forEach { refresh(it) }
        return count
    }

    override fun handles(): List<String> = placed.values.map { it.handle }.distinct().sorted()

    fun forget(player: Player) {
        shown.remove(player.uniqueId)
    }

    fun tick() {
        if (--refreshTicks > 0) return
        refreshTicks = REFRESH_INTERVAL_TICKS
        Bukkit.getOnlinePlayers().forEach { refresh(it) }
    }

    fun refresh(player: Player) {
        val visible = shown.getOrPut(player.uniqueId) { mutableMapOf() }
        val wanted = placed.filterValues { it.at.world == player.world.name }

        val gone = visible.keys.filter { it !in wanted }.toList()
        val fresh = wanted.keys.filter { it !in visible }
        if (gone.isEmpty() && fresh.isEmpty()) return

        backend.bundle(player) {
            if (gone.isNotEmpty()) {
                val ids = gone.mapNotNull { visible.remove(it) }.toIntArray()
                if (ids.isNotEmpty()) backend.remove(player, *ids)
            }
            for (local in fresh) {
                val spec = wanted.getValue(local)
                val id = backend.nextEntityId(player)
                backend.spawn(player, id, FakeEntityKind.ITEM_DISPLAY, locationOf(spec))
                backend.metadata(player, id, Displays.metaFor(spec, backend.slots()))
                visible[local] = id
            }
        }
    }

    private fun locationOf(spec: WorldShaderSpec): Location {
        val world = Bukkit.getWorld(spec.at.world)
        return Location(world, spec.at.x, spec.at.y, spec.at.z, spec.yaw, spec.pitch)
    }

    private fun save() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val rows = placed.values.map { spec ->
                linkedMapOf<String, Any?>(
                    "handle" to spec.handle,
                    "shader" to spec.shader,
                    "world" to spec.at.world,
                    "x" to spec.at.x,
                    "y" to spec.at.y,
                    "z" to spec.at.z,
                    "scale" to spec.scale,
                    "color" to spec.color.packed,
                    "billboard" to spec.billboard.name,
                    "view-range" to spec.viewRange?.toDouble(),
                    "yaw" to spec.yaw.toDouble(),
                    "pitch" to spec.pitch.toDouble(),
                )
            }
            stateFile.writeText(Yaml().dump(rows))
        }.onFailure { log("could not save placed world shaders: ${it.message}") }
    }

    private fun load() {
        if (!stateFile.isFile) return
        runCatching {
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            val rows = stateFile.inputStream().use { yaml.load<Any?>(it) } as? List<*> ?: return
            for (row in rows) {
                val map = row as? Map<*, *> ?: continue
                val world = map["world"] as? String ?: continue
                placed[nextLocal++] = WorldShaderSpec(
                    handle = map["handle"] as? String ?: continue,
                    shader = map["shader"] as? String ?: continue,
                    at = WorldAnchor(
                        world = world,
                        x = (map["x"] as? Number)?.toDouble() ?: continue,
                        y = (map["y"] as? Number)?.toDouble() ?: continue,
                        z = (map["z"] as? Number)?.toDouble() ?: continue,
                    ),
                    scale = (map["scale"] as? Number)?.toDouble() ?: 1.0,
                    color = Rgb((map["color"] as? Number)?.toInt() ?: Rgb.WHITE.packed),
                    billboard = runCatching { BillboardMode.valueOf(map["billboard"] as String) }
                        .getOrDefault(BillboardMode.CENTER),
                    viewRange = (map["view-range"] as? Number)?.toFloat(),
                    yaw = (map["yaw"] as? Number)?.toFloat() ?: 0f,
                    pitch = (map["pitch"] as? Number)?.toFloat() ?: 0f,
                )
            }
        }.onFailure { log("could not read placed world shaders: ${it.message}") }
    }

    private companion object {
        const val REFRESH_INTERVAL_TICKS = 20
    }
}
