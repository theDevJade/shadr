/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
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
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public final class MinestomBridge implements PlatformBridge {
    private final MinestomPlayers players = new MinestomPlayers();
    private final MinestomCamera camera;
    private final MinestomHudSink hud;
    private final MinestomInput input;
    private final MinestomWorldDisplays world;

    public MinestomBridge(Function<String, Instance> instances) {
        this(instances, () -> false);
    }

    public MinestomBridge(Function<String, Instance> instances, BooleanSupplier postEffects) {
        this(instances, postEffects, Path.of("world-shaders.yml"));
    }

    public MinestomBridge(
            Function<String, Instance> instances,
            BooleanSupplier postEffects,
            Path worldShaderState) {
        this.world = new MinestomWorldDisplays(instances, worldShaderState);
        this.camera = new MinestomCamera(players, postEffects);
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
