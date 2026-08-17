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
import dev.shadr.core.spi.HudSink;
import dev.shadr.core.spi.InputSource;
import dev.shadr.core.spi.PlatformBridge;
import dev.shadr.core.spi.PlayerRegistry;
import dev.shadr.core.spi.ResourcePackService;
import dev.shadr.core.spi.WorldDisplays;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.instance.Instance;
import net.minestom.server.timer.TaskSchedule;

import java.net.URI;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * This module has its own Gradle build and depends on {@code core} as a jar. Minestom ships
 * Java 25 class files, and the root build targets Java 21 on a Kotlin version that cannot read
 * them. See {@code settings.gradle.kts}.
 */
public final class MinestomBridge implements PlatformBridge {

    private final MinestomPlayers players = new MinestomPlayers();
    private final MinestomCamera camera;
    private final MinestomHudSink hud;
    private final MinestomInput input;
    private final MinestomWorldDisplays world;

    public MinestomBridge(Function<String, Instance> instances) {
        this(instances, () -> false);
    }

    /**
     * @param frostedGlass must match whether the pack ships the {@code blur} world override.
     *   With no chain in the pack, spectating the mob gives the player vanilla's colour
     *   inversion instead. See {@link MinestomCamera}.
     */
    public MinestomBridge(Function<String, Instance> instances, BooleanSupplier frostedGlass) {
        this.world = new MinestomWorldDisplays(instances);
        this.camera = new MinestomCamera(players, frostedGlass);
        this.hud = new MinestomHudSink(players, camera);
        this.input = new MinestomInput(players, camera);
    }

    @Override public HudSink hud() { return hud; }
    @Override public CameraControl camera() { return camera; }
    @Override public InputSource input() { return input; }
    @Override public PlayerRegistry players() { return players; }
    @Override public WorldDisplays world() { return world; }

    public MinestomHudSink hudSink() { return hud; }
    public MinestomCamera cameraControl() { return camera; }
    public MinestomInput inputSource() { return input; }
    public MinestomPlayers playerRegistry() { return players; }

    private final Map<String, UUID> sentPacks = new ConcurrentHashMap<>();

    @Override
    public ResourcePackService pack() {
        return (player, url, sha1, forced) -> {
            final Player target = players.entity(player);
            if (target == null) return;
            // The id is the hash, so a client that already holds this build skips the
            // download.
            final UUID id = UUID.nameUUIDFromBytes(sha1);
            final UUID previous = sentPacks.put(player.getUuid(), id);
            if (previous != null && !previous.equals(id)) target.removeResourcePacks(previous);
            target.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                    .packs(ResourcePackInfo.resourcePackInfo(
                            id,
                            URI.create(url),
                            HexFormat.of().formatHex(sha1)))
                    .required(forced)
                    .prompt(Component.text("shadr"))
                    .build());
        };
    }

    /** Call once, after {@code MinecraftServer.init()}. */
    public void install() {
        final GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();
        players.install(events);
        input.install(events);

        players.onQuit(id -> {
            forget(id);
            return kotlin.Unit.INSTANCE;
        });

        MinecraftServer.getSchedulerManager()
                .buildTask(this::tick)
                .repeat(TaskSchedule.tick(1))
                .schedule();
    }

    /**
     * Reassert the seat, then sample input, in that order. If the mount was dropped this tick,
     * the yaw we read is one the player moved with their body, not with their mouse.
     */
    private void tick() {
        camera.tick();
        input.tick();
    }

    public void forget(PlayerId player) {
        hud.clear(player);
        camera.stop(player);
        input.forget(player);
        sentPacks.remove(player.getUuid());
    }
}
