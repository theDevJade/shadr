/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.minestom;

import dev.shadr.core.Rgb;
import dev.shadr.core.hud.DisplayMeta;
import dev.shadr.core.shader.ShaderApi;
import dev.shadr.core.shader.ShaderTint;
import dev.shadr.core.spi.BillboardMode;
import dev.shadr.core.spi.WorldAnchor;
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
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class MinestomWorldDisplays implements WorldDisplays {
    private record Placement(WorldShaderSpec spec, Entity entity) {}

    private final Map<String, Placement> placed = new LinkedHashMap<>();

    private final Function<String, Instance> instances;

    private final Path state;

    public MinestomWorldDisplays(Function<String, Instance> instances) {
        this(instances, null);
    }

    public MinestomWorldDisplays(Function<String, Instance> instances, Path state) {
        this.instances = instances;
        this.state = state;
        load();
    }

    @Override
    public boolean spawn(WorldShaderSpec spec) {
        if (!place(spec)) return false;
        save();
        return true;
    }

    private boolean place(WorldShaderSpec spec) {
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

        final Placement previous = placed.put(spec.getHandle(), new Placement(spec, display));
        if (previous != null) previous.entity().remove();
        return true;
    }

    @Override
    public boolean despawn(String handle) {
        final Placement gone = placed.remove(handle);
        if (gone == null) return false;
        gone.entity().remove();
        save();
        return true;
    }

    @Override
    public int despawnAll() {
        final int count = placed.size();
        if (count == 0) return 0;
        placed.values().forEach(entry -> entry.entity().remove());
        placed.clear();
        save();
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

    private void save() {
        if (state == null) return;
        try {
            final List<Map<String, Object>> rows = new ArrayList<>(placed.size());
            for (Placement entry : placed.values()) {
                final WorldShaderSpec spec = entry.spec();
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("handle", spec.getHandle());
                row.put("shader", spec.getShader());
                row.put("world", spec.getAt().getWorld());
                row.put("x", spec.getAt().getX());
                row.put("y", spec.getAt().getY());
                row.put("z", spec.getAt().getZ());
                row.put("scale", spec.getScale());
                row.put("color", spec.getColor().getPacked());
                row.put("billboard", spec.getBillboard().name());
                row.put("view-range", spec.getViewRange() == null ? null : spec.getViewRange().doubleValue());
                row.put("yaw", (double) spec.getYaw());
                row.put("pitch", (double) spec.getPitch());
                rows.add(row);
            }
            if (state.getParent() != null) Files.createDirectories(state.getParent());
            Files.writeString(state, new Yaml().dump(rows));
        } catch (Exception failure) {
            System.err.println("[shadr] could not save placed world shaders: " + failure);
        }
    }

    private void load() {
        if (state == null || !Files.isRegularFile(state)) return;
        try (InputStream in = Files.newInputStream(state)) {
            final Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
            if (!(parsed instanceof List<?> rows)) return;
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> map)) continue;
                final WorldShaderSpec spec = specOf(map);
                if (spec != null) place(spec);
            }
        } catch (Exception failure) {
            System.err.println("[shadr] could not read placed world shaders: " + failure);
        }
    }

    private static WorldShaderSpec specOf(Map<?, ?> map) {
        if (!(map.get("handle") instanceof String handle)) return null;
        if (!(map.get("shader") instanceof String shader)) return null;
        if (!(map.get("world") instanceof String world)) return null;
        if (!(map.get("x") instanceof Number x)) return null;
        if (!(map.get("y") instanceof Number y)) return null;
        if (!(map.get("z") instanceof Number z)) return null;

        BillboardMode billboard = BillboardMode.CENTER;
        if (map.get("billboard") instanceof String name) {
            try {
                billboard = BillboardMode.valueOf(name);
            } catch (IllegalArgumentException unknown) {
                billboard = BillboardMode.CENTER;
            }
        }

        return new WorldShaderSpec(
                handle,
                shader,
                new WorldAnchor(world, x.doubleValue(), y.doubleValue(), z.doubleValue()),
                map.get("scale") instanceof Number scale ? scale.doubleValue() : 1.0,
                new Rgb(map.get("color") instanceof Number packed
                        ? packed.intValue()
                        : Rgb.Companion.getWHITE().getPacked()),
                billboard,
                map.get("view-range") instanceof Number range ? range.floatValue() : null,
                map.get("yaw") instanceof Number yaw ? yaw.floatValue() : 0f,
                map.get("pitch") instanceof Number pitch ? pitch.floatValue() : 0f);
    }
}
