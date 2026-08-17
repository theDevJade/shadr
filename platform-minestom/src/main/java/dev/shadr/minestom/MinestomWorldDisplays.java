/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.minestom;

import dev.shadr.core.hud.DisplayMeta;
import dev.shadr.core.shader.ShaderApi;
import dev.shadr.core.shader.ShaderTint;
import dev.shadr.core.spi.BillboardMode;
import dev.shadr.core.spi.WorldDisplays;
import dev.shadr.core.spi.WorldShaderSpec;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class MinestomWorldDisplays implements WorldDisplays {
    private final Map<String, Entity> placed = new LinkedHashMap<>();

    private final Function<String, Instance> instances;

    public MinestomWorldDisplays(Function<String, Instance> instances) {
        this.instances = instances;
    }

    @Override
    public boolean spawn(WorldShaderSpec spec) {
        final Instance instance = instances.apply(spec.getAt().getWorld());
        if (instance == null) return false;

        final Pos at = new Pos(
                spec.getAt().getX(), spec.getAt().getY(), spec.getAt().getZ(),
                spec.getYaw(), spec.getPitch());

        final Entity display = new Entity(EntityType.ITEM_DISPLAY);
        display.setNoGravity(true);

        display.setInstance(instance, at).thenRun(() -> {
            final ItemDisplayMeta meta = (ItemDisplayMeta) display.getEntityMeta();
            meta.setNotifyAboutChanges(false);
            meta.setItemStack(ItemStack.builder(Material.LEATHER_HORSE_ARMOR)
                    .set(DataComponents.ITEM_MODEL, ShaderApi.itemModelOf(spec.getShader()))
                    .set(DataComponents.DYED_COLOR,
                            net.kyori.adventure.text.format.TextColor.color(
                                    ShaderTint.INSTANCE.encode(spec.getColor(), spec.getScale())))
                    .build());
            meta.setBillboardRenderConstraints(billboard(spec.getBillboard()));
            meta.setBrightness(DisplayMeta.BRIGHTNESS_LEVEL, DisplayMeta.BRIGHTNESS_LEVEL);
            if (spec.getViewRange() != null) meta.setViewRange(spec.getViewRange());
            meta.setTransformationInterpolationStartDelta(0);
            meta.setTransformationInterpolationDuration(0);
            final float scale = (float) spec.getScale();
            meta.setScale(new Vec(scale, scale, scale));
            meta.setNotifyAboutChanges(true);
        });

        final Entity previous = placed.put(spec.getHandle(), display);
        if (previous != null) previous.remove();
        return true;
    }

    @Override
    public boolean despawn(String handle) {
        final Entity gone = placed.remove(handle);
        if (gone == null) return false;
        gone.remove();
        return true;
    }

    @Override
    public int despawnAll() {
        final int count = placed.size();
        placed.values().forEach(Entity::remove);
        placed.clear();
        return count;
    }

    @Override
    public List<String> handles() {
        return new ArrayList<>(placed.keySet());
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    private static AbstractDisplayMeta.BillboardConstraints billboard(BillboardMode mode) {
        return switch (mode) {
            case FIXED -> AbstractDisplayMeta.BillboardConstraints.FIXED;
            case VERTICAL -> AbstractDisplayMeta.BillboardConstraints.VERTICAL;
            case HORIZONTAL -> AbstractDisplayMeta.BillboardConstraints.HORIZONTAL;
            case CENTER -> AbstractDisplayMeta.BillboardConstraints.CENTER;
        };
    }
}
