/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.minestom;

import dev.shadr.core.PlayerId;
import dev.shadr.core.spi.PlayerRegistry;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public final class MinestomPlayers implements PlayerRegistry {
    private final List<Consumer<PlayerId>> joinListeners = new ArrayList<>();
    private final List<Consumer<PlayerId>> quitListeners = new ArrayList<>();

    void install(GlobalEventHandler events) {
        events.addListener(PlayerSpawnEvent.class, event -> {
            if (!event.isFirstSpawn()) return;
            fire(joinListeners, idOf(event.getPlayer()));
        });
        events.addListener(PlayerDisconnectEvent.class, event ->
                fire(quitListeners, idOf(event.getPlayer())));
    }

    @Override
    public Collection<PlayerId> online() {
        return MinecraftServer.getConnectionManager().getOnlinePlayers().stream()
                .map(MinestomPlayers::idOf)
                .map(id -> (PlayerId) id)
                .toList();
    }

    @Override
    public void onJoin(kotlin.jvm.functions.Function1<? super PlayerId, kotlin.Unit> listener) {
        joinListeners.add(listener::invoke);
    }

    @Override
    public void onQuit(kotlin.jvm.functions.Function1<? super PlayerId, kotlin.Unit> listener) {
        quitListeners.add(listener::invoke);
    }

    public Player entity(PlayerId player) {
        final UUID uuid;
        try {
            uuid = UUID.fromString(player.getUuid());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        return MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid);
    }

    public static PlayerId idOf(Player player) {
        return new PlayerId(player.getUuid().toString());
    }

    private static void fire(List<Consumer<PlayerId>> listeners, PlayerId player) {
        for (Consumer<PlayerId> listener : List.copyOf(listeners)) listener.accept(player);
    }
}
