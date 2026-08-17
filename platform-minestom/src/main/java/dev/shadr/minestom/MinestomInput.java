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
import dev.shadr.core.ScreenPos;
import dev.shadr.core.cursor.LookMapper;
import dev.shadr.core.spi.InputSample;
import dev.shadr.core.spi.InputSource;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerHandAnimationEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Each seated player keeps their own {@link LookMapper} so yaw accumulation survives across
 * ticks. The mapper must be reset when a camera starts, or the first sample is the whole
 * difference between wherever they were looking and the screen centre.
 */
public final class MinestomInput implements InputSource {

    private final List<Consumer<InputSample>> listeners = new ArrayList<>();
    private final Map<String, List<Consumer<PlayerId>>> keyListeners = new HashMap<>();
    private final Map<String, LookMapper> mappers = new HashMap<>();
    /** Null = no click this tick; FALSE = left; TRUE = right. */
    private final Map<String, Boolean> pendingClicks = new HashMap<>();

    private final MinestomPlayers players;
    private final MinestomCamera camera;

    private double screenWidth = 1920.0;
    private double screenHeight = 1080.0;
    private double cursorSpeed = 1.0;

    public MinestomInput(MinestomPlayers players, MinestomCamera camera) {
        this.players = players;
        this.camera = camera;
    }

    void install(GlobalEventHandler events) {
        // The client sends the hand-animation packet whether or not the swing hit anything.
        // That suits a HUD click, where there is nothing in the world to hit. Paper has to
        // spawn interaction stand-ins to provoke the same packet.
        events.addListener(PlayerHandAnimationEvent.class,
                event -> queueClick(MinestomPlayers.idOf(event.getPlayer()), false));
        events.addListener(PlayerUseItemEvent.class,
                event -> queueClick(MinestomPlayers.idOf(event.getPlayer()), true));
    }

    /** Called by the host when a page is opened, before {@link #resetMapper}. */
    public void useScreen(double width, double height, double speed) {
        this.screenWidth = width;
        this.screenHeight = height;
        this.cursorSpeed = speed;
    }

    @Override
    public void onSample(kotlin.jvm.functions.Function1<? super InputSample, kotlin.Unit> listener) {
        listeners.add(listener::invoke);
    }

    @Override
    public void onKey(String key, kotlin.jvm.functions.Function1<? super PlayerId, kotlin.Unit> listener) {
        keyListeners.computeIfAbsent(key, k -> new ArrayList<>()).add(listener::invoke);
    }

    public void queueClick(PlayerId player, boolean rightClick) {
        if (!camera.isActive(player)) return;
        pendingClicks.put(player.getUuid(), rightClick);
    }

    public void fireKey(String key, PlayerId player) {
        for (Consumer<PlayerId> listener : keyListeners.getOrDefault(key, List.of())) {
            listener.accept(player);
        }
    }

    public void resetMapper(PlayerId player) {
        final Player entity = players.entity(player);
        final LookMapper mapper = new LookMapper(screenWidth, screenHeight, cursorSpeed);
        mapper.reset(screenWidth / 2, screenHeight / 2, entity == null ? 0f : entity.getPosition().yaw());
        mappers.put(player.getUuid(), mapper);
    }

    public LookMapper mapperFor(PlayerId player) {
        return mappers.get(player.getUuid());
    }

    public void forget(PlayerId player) {
        mappers.remove(player.getUuid());
        pendingClicks.remove(player.getUuid());
    }

    /** Called once per tick from the host. */
    public void tick() {
        for (PlayerId id : players.online()) {
            if (!camera.isActive(id)) continue;
            final Player entity = players.entity(id);
            if (entity == null) continue;

            LookMapper mapper = mappers.get(id.getUuid());
            if (mapper == null) {
                resetMapper(id);
                mapper = mappers.get(id.getUuid());
            }

            final ScreenPos cursor = mapper.sample(
                    entity.getPosition().yaw(), entity.getPosition().pitch(), 1.0);
            final Boolean click = pendingClicks.remove(id.getUuid());

            final InputSample sample = new InputSample(
                    id,
                    cursor,
                    Boolean.FALSE.equals(click),
                    Boolean.TRUE.equals(click),
                    entity.getLatency());
            for (Consumer<InputSample> listener : List.copyOf(listeners)) listener.accept(sample);
        }
    }
}
