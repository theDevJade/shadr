/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.minestom;

import dev.shadr.core.PlayerId;
import dev.shadr.core.TextAlignment;
import dev.shadr.core.hud.DisplayMeta;
import dev.shadr.core.hud.HudDiff;
import dev.shadr.core.hud.HudDraw;
import dev.shadr.core.spi.HudSink;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minestom.server.component.DataComponents;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta;
import net.minestom.server.entity.metadata.display.ItemDisplayMeta;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.CustomModelData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MinestomHudSink implements HudSink {
    private static final float[] HUD_FACING = {0f, 1f, 0f, 0f};

    private static final float HUD_VIEW_RANGE = 1000f;

    private static final class PlayerHud {
        final Entity carrier;
        final Map<String, Entity> parts = new HashMap<>();
        Map<String, HudDraw> lastDraws = new HashMap<>();

        PlayerHud(Entity carrier) {
            this.carrier = carrier;
        }
    }

    private final Map<String, PlayerHud> huds = new HashMap<>();
    private final MinestomPlayers players;

    private final MinestomCamera camera;

    public MinestomHudSink(MinestomPlayers players, MinestomCamera camera) {
        this.players = players;
        this.camera = camera;
    }

    @Override
    public void mount(PlayerId player) {
        if (huds.containsKey(player.getUuid())) return;
        final Entity carrier = camera.cameraEntityFor(player);
        if (carrier == null) return;
        huds.put(player.getUuid(), new PlayerHud(carrier));
    }

    @Override
    public void clear(PlayerId player) {
        final PlayerHud hud = huds.remove(player.getUuid());
        if (hud == null) return;
        hud.parts.values().forEach(Entity::remove);
        hud.parts.clear();
    }

    @Override
    public void apply(PlayerId player, List<HudDraw> draws) {
        final PlayerHud hud = huds.get(player.getUuid());
        if (hud == null) return;
        final Player owner = players.entity(player);
        if (owner == null) return;

        final HudDiff diff = HudDiff.Companion.between(hud.lastDraws, draws, hud.parts::containsKey);

        for (String key : diff.getRemoved()) {
            final Entity gone = hud.parts.remove(key);
            if (gone != null) gone.remove();
        }

        for (HudDraw draw : diff.getSpawned()) {
            final Entity previous = hud.parts.remove(draw.getKey());
            if (previous != null) previous.remove();

            final Entity created = new Entity(draw.getKind() == HudDraw.Kind.ITEM
                    ? EntityType.ITEM_DISPLAY
                    : EntityType.TEXT_DISPLAY);
            created.setNoGravity(true);
            created.setAutoViewable(false);
            hud.parts.put(draw.getKey(), created);

            created.setInstance(hud.carrier.getInstance(), hud.carrier.getPosition())
                    .thenRun(() -> {
                        created.addViewer(owner);
                        hud.carrier.addPassenger(created);
                        apply(created, draw);
                    });
        }

        for (HudDraw draw : diff.getUpdated()) {
            final Entity entity = hud.parts.get(draw.getKey());
            if (entity != null && entity.getInstance() != null) apply(entity, draw);
        }

        hud.lastDraws = new HashMap<>();
        for (HudDraw draw : draws) hud.lastDraws.put(draw.getKey(), draw);
    }

    public boolean isMounted(PlayerId player) {
        return huds.containsKey(player.getUuid());
    }

    private static void apply(Entity entity, HudDraw draw) {
        if (draw.getKind() == HudDraw.Kind.ITEM) {
            applyItem(entity, draw);
            return;
        }
        final TextDisplayMeta meta = (TextDisplayMeta) entity.getEntityMeta();
        meta.setNotifyAboutChanges(false);
        meta.setText(MiniMessage.miniMessage().deserialize(draw.getDisplayText()));
        meta.setLineWidth(draw.getLineWidth());

        meta.setUseDefaultBackground(false);
        meta.setBackgroundColor(DisplayMeta.TEXT_BACKGROUND_TRANSPARENT);

        applyCommon(meta, draw);

        meta.setTextOpacity((byte) Math.max(1, Math.min(255, draw.getOpacity())));

        meta.setAlignLeft(draw.getTextAlignment() == TextAlignment.LEFT);
        meta.setAlignRight(draw.getTextAlignment() == TextAlignment.RIGHT);
        meta.setNotifyAboutChanges(true);
    }

    private static void applyItem(Entity entity, HudDraw draw) {
        final ItemDisplayMeta meta = (ItemDisplayMeta) entity.getEntityMeta();
        meta.setNotifyAboutChanges(false);

        final String id = draw.getItem();
        final Material material = id == null ? null : Material.fromKey(id);
        if (material != null) {
            ItemStack.Builder stack = ItemStack.builder(material);
            final String model = draw.getItemModel();
            if (model != null) {
                stack = stack.set(DataComponents.ITEM_MODEL, model);
            }
            final Integer bucket = draw.getItemCustomModelData();
            if (bucket != null) {
                stack = stack.set(DataComponents.CUSTOM_MODEL_DATA,
                        new CustomModelData(List.of((float) (int) bucket), List.of(), List.of(), List.of()));
            }
            final dev.shadr.core.Rgb tint = draw.getTint();
            if (tint != null) {
                stack = stack.set(DataComponents.DYED_COLOR,
                        net.kyori.adventure.text.format.TextColor.color(
                                tint.getR(), tint.getG(), tint.getB()));
            }
            meta.setItemStack(stack.build());
        }

        applyCommon(meta, draw);
        meta.setNotifyAboutChanges(true);
    }

    private static void applyCommon(AbstractDisplayMeta meta, HudDraw draw) {
        meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.FIXED);
        meta.setBrightness(DisplayMeta.BRIGHTNESS_LEVEL, DisplayMeta.BRIGHTNESS_LEVEL);
        meta.setViewRange(HUD_VIEW_RANGE);

        meta.setTransformationInterpolationStartDelta(draw.getInterpolationDelay());
        meta.setTransformationInterpolationDuration(draw.getInterpolationDuration());

        meta.setTranslation(new Vec(
                draw.getTranslation().getX(),
                draw.getTranslation().getY(),
                draw.getTranslation().getZ()));
        meta.setScale(new Vec(
                draw.getScale().getX(),
                draw.getScale().getY(),
                draw.getScale().getZ()));

        meta.setLeftRotation(HUD_FACING);
        meta.setRightRotation(rotationFor(draw));
    }

    private static float[] rotationFor(HudDraw draw) {
        if (draw.getRotationDeg() == 0.0) return new float[]{0f, 0f, 0f, 1f};
        final double half = Math.toRadians(draw.getRotationDeg()) / 2.0;
        return new float[]{0f, 0f, (float) Math.sin(half), (float) Math.cos(half)};
    }
}
