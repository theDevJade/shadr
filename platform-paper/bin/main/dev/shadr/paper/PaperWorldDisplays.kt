/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.spi.BillboardMode
import dev.shadr.core.spi.WorldDisplays
import dev.shadr.core.spi.WorldShaderSpec
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Vector3f

class PaperWorldDisplays(private val plugin: Plugin) : WorldDisplays {
    private val handleKey = NamespacedKey(plugin, "shader_handle")

    override fun spawn(spec: WorldShaderSpec): Boolean {
        val world = Bukkit.getWorld(spec.at.world) ?: return false
        val location = Location(world, spec.at.x, spec.at.y, spec.at.z, spec.yaw, spec.pitch)

        return runCatching {
            world.spawn(location, ItemDisplay::class.java) { display ->
                display.setItemStack(Displays.shaderItem(spec))
                display.billboard = when (spec.billboard) {
                    BillboardMode.FIXED -> Display.Billboard.FIXED
                    BillboardMode.VERTICAL -> Display.Billboard.VERTICAL
                    BillboardMode.HORIZONTAL -> Display.Billboard.HORIZONTAL
                    BillboardMode.CENTER -> Display.Billboard.CENTER
                }
                display.interpolationDelay = 0
                display.interpolationDuration = 0
                display.transformation = Transformation(
                    Vector3f(0f, 0f, 0f),
                    display.transformation.leftRotation,
                    Vector3f(spec.scale.toFloat(), spec.scale.toFloat(), spec.scale.toFloat()),
                    display.transformation.rightRotation,
                )
                display.setBrightness(Display.Brightness(15, 15))
                display.setGravity(false)
                display.isPersistent = true
                spec.viewRange?.let { display.viewRange = it }
                display.persistentDataContainer.set(
                    handleKey, PersistentDataType.STRING, spec.handle,
                )
            }
            true
        }.getOrElse { false }
    }

    override fun despawn(handle: String): Boolean {
        var removed = false
        for (display in all()) {
            if (display.persistentDataContainer.get(handleKey, PersistentDataType.STRING) == handle) {
                display.remove()
                removed = true
            }
        }
        return removed
    }

    override fun despawnAll(): Int {
        var count = 0
        for (display in all()) {
            if (display.persistentDataContainer.has(handleKey, PersistentDataType.STRING)) {
                display.remove()
                count++
            }
        }
        return count
    }

    override fun handles(): List<String> = all()
        .mapNotNull { it.persistentDataContainer.get(handleKey, PersistentDataType.STRING) }
        .distinct()
        .sorted()

    private fun all(): List<ItemDisplay> =
        Bukkit.getWorlds().flatMap { it.getEntitiesByClass(ItemDisplay::class.java) }
}
