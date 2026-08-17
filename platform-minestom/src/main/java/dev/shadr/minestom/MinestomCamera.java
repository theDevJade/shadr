/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
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

/**
 * The player rides an invisible mount, which locks their position, and spectates a second
 * entity at eye height, which locks their view. Their mouse still turns their own entity, and
 * the server reads that rotation back as cursor movement.
 *
 * It takes two entities because the locks are separate. A passenger's view still follows its
 * own yaw, and a spectated entity does not stop the player walking.
 */
public final class MinestomCamera implements CameraControl {

    /** Re-apply the mount and the spectate every few ticks; both can drop client-side. */
    private static final int RELOCK_INTERVAL_TICKS = 5;

    private static final class Seat {
        /** Fixed-orientation anchor the player spectates, and what HUD parts ride. */
        Entity camera;
        /** Invisible vehicle the player rides, which pins them in place. */
        Entity mount;
        int relockTicks = RELOCK_INTERVAL_TICKS;
        boolean clickTargets;
    }

    private final Map<String, Seat> seats = new HashMap<>();
    private final MinestomPlayers players;

    /**
     * Spawn the spectated entity as an EnderMan so the {@code invert} post chain runs.
     * {@code Minecraft.setCameraEntity} calls {@code GameRenderer.checkEntityPostEffect}, which
     * picks the active post effect from the camera entity's type. Off by default, and tied to
     * the {@code blur} world override.
     */
    private final BooleanSupplier frostedGlass;

    public MinestomCamera(MinestomPlayers players) {
        this(players, () -> false);
    }

    public MinestomCamera(MinestomPlayers players, BooleanSupplier frostedGlass) {
        this.players = players;
        this.frostedGlass = frostedGlass;
    }

    @Override
    public boolean isActive(PlayerId player) {
        final Seat seat = seats.get(player.getUuid());
        return seat != null && seat.camera != null;
    }

    /** The entity HUD parts must ride, or null when no camera is up yet. */
    public Entity cameraEntityFor(PlayerId player) {
        final Seat seat = seats.get(player.getUuid());
        return seat == null ? null : seat.camera;
    }

    @Override
    public void start(PlayerId player) {
        start(player, null);
    }

    /**
     * @param whenReady run once the seat is in place, on the server thread.
     *   {@code setInstance} is asynchronous. Mounting or spectating before it completes throws
     *   inside a Minestom event listener, which swallows the exception, so chain anything that
     *   depends on the seat off this callback.
     */
    public void start(PlayerId player, Runnable whenReady) {
        final Player entity = players.entity(player);
        if (entity == null || isActive(player)) return;

        final Seat seat = new Seat();
        seats.put(player.getUuid(), seat);

        final Pos base = entity.getPosition().withView(0f, 0f);
        final Pos eye = base.add(0, entity.getEyeHeight(), 0);

        // Only the spectated entity decides the post effect, so only the camera changes type.
        // A bare `Entity`, not a LivingEntity subclass. Minestom only runs AI for entities that
        // are given it, so an EnderMan built this way just stands there, and none of the flags
        // the Paper adapter switches off one at a time are needed here.
        final Entity camera = new Entity(frostedGlass.getAsBoolean() ? EntityType.ENDERMAN : EntityType.TEXT_DISPLAY);
        camera.setNoGravity(true);
        camera.setInvisible(true);
        final Entity mount = new Entity(EntityType.TEXT_DISPLAY);
        mount.setNoGravity(true);

        CompletableFuture.allOf(
                camera.setInstance(entity.getInstance(), eye),
                mount.setInstance(entity.getInstance(), base)
        ).thenRun(() -> {
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

    /**
     * Minestom drives clicks off the hand-animation packet, so unlike Paper there is no
     * interaction stand-in to spawn. This only stores a flag, so a caller can still turn
     * clicks off.
     */
    @Override
    public void setClickTargetsEnabled(PlayerId player, boolean enabled) {
        final Seat seat = seats.get(player.getUuid());
        if (seat != null) seat.clickTargets = enabled;
    }

    public boolean clickTargetsEnabled(PlayerId player) {
        final Seat seat = seats.get(player.getUuid());
        return seat != null && seat.clickTargets;
    }

    /**
     * Re-seats anything the client dropped. Called once per tick from the host.
     *
     * Both locks are advisory on the client, and losing either one fires no server-side event.
     * A timer is the only way to find out.
     */
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
