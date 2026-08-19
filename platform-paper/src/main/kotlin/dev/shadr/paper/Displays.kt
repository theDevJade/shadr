/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.PlayerId
import dev.shadr.core.hud.DisplayMeta
import dev.shadr.core.hud.HudDraw
import dev.shadr.core.shader.ShaderApi
import dev.shadr.core.shader.ShaderTint
import dev.shadr.core.spi.BillboardMode
import dev.shadr.core.spi.CameraControl
import dev.shadr.core.spi.HudSink
import dev.shadr.core.spi.WorldShaderSpec
import dev.shadr.paper.nms.EntitySlots
import dev.shadr.paper.nms.FakeEntityKind
import dev.shadr.paper.nms.MetaValue
import dev.shadr.paper.nms.PacketBackend
import dev.shadr.paper.nms.PacketBackends
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import io.papermc.paper.datacomponent.item.DyedItemColor
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta

interface ShadrHudSink : HudSink {
    fun ensureMounted(player: Player)
}

interface ShadrCamera : CameraControl {
    fun follow(player: Player)

    fun stopAll()

    fun isCameraEntity(entity: Entity): Boolean

    fun clickTargetsEnabled(player: PlayerId): Boolean
}

object Displays {
    const val HUD_VIEW_RANGE = 64f

    fun backendOrNull(log: (String) -> Unit): PacketBackend? = runCatching { PacketBackends.load() }
        .onFailure { log("no packet backend for ${PacketBackends.serverVersion()}: ${it.message}") }
        .getOrNull()

    fun kindOf(draw: HudDraw): FakeEntityKind = when (draw.kind) {
        HudDraw.Kind.ITEM -> FakeEntityKind.ITEM_DISPLAY
        HudDraw.Kind.TEXT -> FakeEntityKind.TEXT_DISPLAY
    }

    fun billboardOf(mode: BillboardMode): Byte = when (mode) {
        BillboardMode.FIXED -> 0
        BillboardMode.VERTICAL -> 1
        BillboardMode.HORIZONTAL -> 2
        BillboardMode.CENTER -> 3
    }

    fun metaFor(draw: HudDraw, slots: EntitySlots): List<MetaValue> {
        val values = mutableListOf(
            MetaValue.of(slots.interpolationDelay(), draw.interpolationDelay),
            MetaValue.of(slots.interpolationDuration(), draw.interpolationDuration),
            MetaValue.vector3(
                slots.translation(),
                draw.translation.x.toFloat(),
                draw.translation.y.toFloat(),
                draw.translation.z.toFloat(),
            ),
            MetaValue.vector3(
                slots.scale(),
                draw.scale.x.toFloat(),
                draw.scale.y.toFloat(),
                draw.scale.z.toFloat(),
            ),
            MetaValue.quaternion(slots.leftRotation(), 0f, 1f, 0f, 0f),
            rightRotation(draw.rotationDeg, slots),
            MetaValue.of(slots.billboard(), slots.billboardFixed()),
            MetaValue.of(slots.brightness(), slots.brightnessFull()),
            MetaValue.of(slots.viewRange(), HUD_VIEW_RANGE),
        )

        when (draw.kind) {
            HudDraw.Kind.TEXT -> {
                values += MetaValue.text(slots.text(), MiniMessageText.parse(draw.displayText))
                values += MetaValue.of(slots.lineWidth(), draw.lineWidth)
                values += MetaValue.of(slots.backgroundColor(), DisplayMeta.TEXT_BACKGROUND_TRANSPARENT)
                values += MetaValue.of(slots.textOpacity(), draw.opacity.coerceIn(1, 255).toByte())
                values += MetaValue.of(slots.textFlags(), draw.textFlags)
            }
            HudDraw.Kind.ITEM -> hudItem(draw)?.let { values += MetaValue.item(slots.item(), it) }
        }
        return values
    }

    fun metaFor(spec: WorldShaderSpec, slots: EntitySlots): List<MetaValue> {
        val scale = spec.scale.toFloat()
        return listOf(
            MetaValue.of(slots.interpolationDelay(), 0),
            MetaValue.of(slots.interpolationDuration(), 0),
            MetaValue.vector3(slots.translation(), 0f, 0f, 0f),
            MetaValue.vector3(slots.scale(), scale, scale, scale),
            MetaValue.quaternion(slots.leftRotation(), 0f, 0f, 0f, 1f),
            MetaValue.quaternion(slots.rightRotation(), 0f, 0f, 0f, 1f),
            MetaValue.of(slots.billboard(), billboardOf(spec.billboard)),
            MetaValue.of(slots.brightness(), slots.brightnessFull()),
            MetaValue.of(slots.viewRange(), spec.viewRange ?: 1f),
            MetaValue.item(slots.item(), shaderItem(spec)),
        )
    }

    fun rightRotation(degrees: Double, slots: EntitySlots): MetaValue {
        if (degrees == 0.0) return MetaValue.quaternion(slots.rightRotation(), 0f, 0f, 0f, 1f)
        val half = Math.toRadians(degrees) / 2.0
        return MetaValue.quaternion(
            slots.rightRotation(),
            0f,
            0f,
            Math.sin(half).toFloat(),
            Math.cos(half).toFloat(),
        )
    }

    fun hudItem(draw: HudDraw): ItemStack? {
        val id = draw.item ?: return null
        val key = NamespacedKey.fromString(id.lowercase()) ?: return null
        val material = Registry.MATERIAL.get(key) ?: return null
        val stack = ItemStack(material)

        draw.itemModel?.let { model ->
            NamespacedKey.fromString(model)?.let { stack.setData(DataComponentTypes.ITEM_MODEL, it) }
        }
        draw.itemCustomModelData?.let { bucket ->
            stack.setData(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addFloat(bucket.toFloat()),
            )
        }
        draw.tint?.let { tint ->
            stack.setData(
                DataComponentTypes.DYED_COLOR,
                DyedItemColor.dyedItemColor(Color.fromRGB(tint.r, tint.g, tint.b)),
            )
        }
        return stack
    }

    fun shaderItem(spec: WorldShaderSpec): ItemStack {
        val material = Material.matchMaterial(ShaderApi.BASE_ITEM) ?: Material.LEATHER_HORSE_ARMOR
        val stack = ItemStack(material)

        stack.editMeta { meta ->
            meta.itemModel = NamespacedKey.fromString(ShaderApi.itemModelOf(spec.shader))
            (meta as? LeatherArmorMeta)?.setColor(
                Color.fromRGB(ShaderTint.encode(spec.color, spec.scale)),
            )
        }
        return stack
    }
}
