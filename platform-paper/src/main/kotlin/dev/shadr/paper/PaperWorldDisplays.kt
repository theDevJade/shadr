/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.paper

import dev.shadr.core.shader.ShaderApi
import dev.shadr.core.spi.BillboardMode
import dev.shadr.core.spi.WorldDisplays
import dev.shadr.core.spi.WorldShaderSpec
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Vector3f

/**
 * World-space shader displays, as ItemDisplay entities.
 *
 * The handle lives in the entity's persistent data and lookups scan for it. An in-memory map
 * would be simpler, but it does not survive a restart and the entities do, which leaves every
 * shader ever placed with no way to remove it.
 */
class PaperWorldDisplays(private val plugin: Plugin) : WorldDisplays {

    private val handleKey = NamespacedKey(plugin, "shader_handle")

    override fun spawn(spec: WorldShaderSpec): Boolean {
        val world = Bukkit.getWorld(spec.at.world) ?: return false
        val location = Location(world, spec.at.x, spec.at.y, spec.at.z, spec.yaw, spec.pitch)

        return runCatching {
            world.spawn(location, ItemDisplay::class.java) { display ->
                display.setItemStack(itemFor(spec))
                display.billboard = when (spec.billboard) {
                    BillboardMode.FIXED -> Display.Billboard.FIXED
                    BillboardMode.VERTICAL -> Display.Billboard.VERTICAL
                    BillboardMode.HORIZONTAL -> Display.Billboard.HORIZONTAL
                    BillboardMode.CENTER -> Display.Billboard.CENTER
                }
                // The transformation is applied against the interpolation window, so these
                // two have to be set first.
                display.interpolationDelay = 0
                display.interpolationDuration = 0
                display.transformation = Transformation(
                    Vector3f(0f, 0f, 0f),
                    display.transformation.leftRotation,
                    Vector3f(spec.scale.toFloat(), spec.scale.toFloat(), spec.scale.toFloat()),
                    display.transformation.rightRotation,
                )
                // A shader computes its own light, so world light must not dim it.
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

    /**
     * A dyeable base item, an `item_model` selecting the shader's definition, and `dyed_color`
     * carrying the tint. It goes through the item meta so this compiles against the plain Paper
     * API, without the component types.
     */
    private fun itemFor(spec: WorldShaderSpec): ItemStack {
        val material = org.bukkit.Material.matchMaterial(ShaderApi.BASE_ITEM)
            ?: org.bukkit.Material.LEATHER_HORSE_ARMOR
        val stack = ItemStack(material)

        stack.editMeta { meta ->
            meta.itemModel = NamespacedKey.fromString(ShaderApi.itemModelOf(spec.shader))
            // Colour and scale share the tint, the only per-instance channel a shader has.
            (meta as? org.bukkit.inventory.meta.LeatherArmorMeta)?.setColor(
                org.bukkit.Color.fromRGB(
                    dev.shadr.core.shader.ShaderTint.encode(spec.color, spec.scale),
                ),
            )
        }
        return stack
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

    /**
     * Every ItemDisplay in a loaded chunk. Unloaded ones are out of reach; no API can remove
     * them, and a cache claiming otherwise would only hide the gap.
     */
    private fun all(): List<ItemDisplay> =
        Bukkit.getWorlds().flatMap { it.getEntitiesByClass(ItemDisplay::class.java) }
}
