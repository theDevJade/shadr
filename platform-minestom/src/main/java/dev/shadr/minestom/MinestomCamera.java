/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.minestom;

import dev.shadr.core.PlayerId;
import dev.shadr.core.spi.CameraControl;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public final class MinestomCamera implements CameraControl {
    private static final int RELOCK_INTERVAL_TICKS = 5;

    private static final class Seat {
        Entity camera;
        Entity mount;
        int relockTicks = RELOCK_INTERVAL_TICKS;
        boolean clickTargets;
    }

    private final Map<String, Seat> seats = new HashMap<>();
    private final MinestomPlayers players;

    private final BooleanSupplier postEffects;

    public MinestomCamera(MinestomPlayers players) {
        this(players, () -> false);
    }

    public MinestomCamera(MinestomPlayers players, BooleanSupplier postEffects) {
        this.players = players;
        this.postEffects = postEffects;
    }

    @Override
    public boolean isActive(PlayerId player) {
        final Seat seat = seats.get(player.getUuid());
        return seat != null && seat.camera != null;
    }

    public Entity cameraEntityFor(PlayerId player) {
        final Seat seat = seats.get(player.getUuid());
        return seat == null ? null : seat.camera;
    }

    @Override
    public void start(PlayerId player) {
        start(player, null);
    }

    public void start(PlayerId player, Runnable whenReady) {
        final Player entity = players.entity(player);
        if (entity == null || isActive(player)) return;

        final Seat seat = new Seat();
        seats.put(player.getUuid(), seat);

        final Pos base = entity.getPosition().withView(0f, 0f);
        final Pos eye = base.add(0, entity.getEyeHeight(), 0);

        final Entity camera = new Entity(postEffects.getAsBoolean() ? EntityType.CREEPER : EntityType.TEXT_DISPLAY);
        camera.setNoGravity(true);
        camera.setInvisible(true);
        camera.setAutoViewable(false);
        final Entity mount = new Entity(EntityType.TEXT_DISPLAY);
        mount.setNoGravity(true);
        mount.setAutoViewable(false);

        CompletableFuture.allOf(
                camera.setInstance(entity.getInstance(), eye),
                mount.setInstance(entity.getInstance(), base)
        ).thenRun(() -> {
            camera.addViewer(entity);
            mount.addViewer(entity);
            seat.camera = camera;
            seat.mount = mount;
            mount.addPassenger(entity);
            entity.setInvisible(true);
            entity.spectate(camera);
            if (whenReady != null) whenReady.run();
        }).exceptionally(error -> {
            System.err.println("[shadr] failed to seat " + player.getUuid() + ": " + error);
            seats.remove(player.getUuid());
            camera.remove();
            mount.remove();
            return null;
        });
    }

    @Override
    public void stop(PlayerId player) {
        final Seat seat = seats.remove(player.getUuid());
        if (seat == null) return;
        final Player entity = players.entity(player);
        if (entity != null) {
            entity.stopSpectating();
            entity.setInvisible(false);
            if (seat.mount != null) seat.mount.removePassenger(entity);
        }
        if (seat.mount != null) seat.mount.remove();
        if (seat.camera != null) seat.camera.remove();
    }

    @Override
    public void setClickTargetsEnabled(PlayerId player, boolean enabled) {
        final Seat seat = seats.get(player.getUuid());
        if (seat != null) seat.clickTargets = enabled;
    }

    public boolean clickTargetsEnabled(PlayerId player) {
        final Seat seat = seats.get(player.getUuid());
        return seat != null && seat.clickTargets;
    }

    public void tick() {
        for (Map.Entry<String, Seat> entry : seats.entrySet()) {
            final Seat seat = entry.getValue();
            if (seat.camera == null) continue;
            if (--seat.relockTicks > 0) continue;
            seat.relockTicks = RELOCK_INTERVAL_TICKS;

            final Player entity = players.entity(new PlayerId(entry.getKey()));
            if (entity == null) continue;
            if (entity.getVehicle() == null && seat.mount != null) seat.mount.addPassenger(entity);
            entity.spectate(seat.camera);
        }
    }

    public void stopAll() {
        for (String uuid : Map.copyOf(seats).keySet()) stop(new PlayerId(uuid));
    }
}
