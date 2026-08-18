/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.minestom;

import dev.shadr.core.PlayerId;
import dev.shadr.core.stream.StreamChannel;
import dev.shadr.core.stream.StreamCodec;
import dev.shadr.core.stream.StreamGeometry;
import dev.shadr.core.stream.StreamPlayer;
import dev.shadr.core.stream.StreamPresets;
import dev.shadr.core.stream.StreamSink;
import dev.shadr.core.stream.StreamVideoSource;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.other.ItemFrameMeta;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.play.BundlePacket;
import net.minestom.server.network.packet.server.play.MapDataPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class MinestomStreamSink {

    private static final int RELOCK_INTERVAL_TICKS = 5;

    private static final double CARRIER_OFFSET = 2.0;

    private static final int CODEC_BUDGET =
            Integer.getInteger("shadr.stream.budget", StreamPresets.INSTANCE.defaultBudget());

    private static final class Session {
        final StreamGeometry geometry;
        final StreamChannel channel;
        final List<Entity> carriers = new ArrayList<>();
        final AtomicLong bytesSent = new AtomicLong();
        int relockTicks = RELOCK_INTERVAL_TICKS;
        StreamVideoSource video;
        StreamPlayer player;
        Thread worker;
        volatile boolean running;
        volatile double serverFps;
        volatile double encodeMs;
        volatile double mbit;

        Session(StreamGeometry geometry) {
            this.geometry = geometry;
            this.channel = geometry.channel();
        }
    }

    private final Map<String, Session> sessions = new HashMap<>();
    private final MinestomPlayers players;
    private final MinestomCamera camera;

    public MinestomStreamSink(MinestomPlayers players, MinestomCamera camera) {
        this.players = players;
        this.camera = camera;
    }

    public boolean isActive(PlayerId player) {
        return sessions.containsKey(player.getUuid());
    }

    public long bytesSent(PlayerId player) {
        final Session session = sessions.get(player.getUuid());
        return session == null ? 0L : session.bytesSent.get();
    }

    public String start(PlayerId player, StreamGeometry geometry) {
        if (sessions.containsKey(player.getUuid())) return "the stream is already running";

        final Player owner = players.entity(player);
        if (owner == null) return "player is not online";

        final Entity anchor = camera.cameraEntityFor(player);
        if (anchor == null || anchor.getInstance() == null) {
            return "the stream rides the camera session's post chain; open a page first";
        }

        final Session session = new Session(geometry);
        for (int slot = 0; slot < geometry.getSlots(); slot++) {
            final Entity carrier = new Entity(EntityType.ITEM_FRAME);
            carrier.setNoGravity(true);
            carrier.setAutoViewable(false);
            carrier.getEntityMeta().setInvisible(true);
            ((ItemFrameMeta) carrier.getEntityMeta()).setItem(mapItem(session.channel.mapId(slot)));
            session.carriers.add(carrier);
            carrier.setInstance(anchor.getInstance(), carrierPos(owner, anchor))
                    .thenRun(() -> carrier.addViewer(owner));
        }

        sessions.put(player.getUuid(), session);
        return null;
    }

    public void stop(PlayerId player) {
        final Session session = sessions.remove(player.getUuid());
        if (session == null) return;
        stopVideoInternal(session);
        session.carriers.forEach(Entity::remove);
        session.carriers.clear();
    }

    public String playVideo(PlayerId player, java.io.File file) {
        final Session session = sessions.get(player.getUuid());
        if (session == null) return "the stream is not running";
        if (file == null || !file.isFile()) return "no such video";
        final Player owner = players.entity(player);
        if (owner == null) return "player is not online";

        stopVideoInternal(session);
        try {
            final StreamCodec.Geometry g = StreamPresets.INSTANCE.getCODEC_1080();
            session.video = new StreamVideoSource(
                    file, g.getFrameWidth(), g.getFrameHeight(), session.geometry.getFps(), true, "ffmpeg", true);
            session.player = new StreamPlayer(
                    g, session.channel, session.geometry,
                    new StreamCodec.Options(
                            StreamCodec.CU * StreamCodec.CU * 3 * StreamPresets.INSTANCE.skipPerPx(),
                            StreamCodec.CU * StreamCodec.CU * 3 * StreamPresets.INSTANCE.mcPerPx(),
                            24,
                            Math.max(1, g.getCus() / 120),
                            1.0,
                            1.4,
                            CODEC_BUDGET,
                            true));
            session.running = true;
            session.worker = new Thread(() -> codecLoop(session, owner), "shadr-stream-codec");
            session.worker.setDaemon(true);
            session.worker.start();
        } catch (Throwable failure) {
            stopVideoInternal(session);
            return String.valueOf(failure.getMessage());
        }
        return null;
    }

    public void stopVideo(PlayerId player) {
        final Session session = sessions.get(player.getUuid());
        if (session != null) stopVideoInternal(session);
    }

    private void stopVideoInternal(Session session) {
        session.running = false;
        if (session.worker != null) {
            session.worker.interrupt();
            session.worker = null;
        }
        if (session.video != null) {
            session.video.close();
            session.video = null;
        }
        session.player = null;
    }

    private void codecLoop(Session session, Player owner) {
        final List<SendablePacket> batch = new ArrayList<>();
        final StreamSink sink = (mapId, startX, startY, width, height, colors) -> batch.add(
                new MapDataPacket(
                        mapId,
                        (byte) 0,
                        false,
                        false,
                        List.of(),
                        new MapDataPacket.ColorContent(
                                (byte) width, (byte) height, (byte) startX, (byte) startY, colors)));
        final long frameNanos = (long) (1_000_000_000L / session.geometry.getFps());
        long emaNanos = frameNanos;
        long nextSend = System.nanoTime();
        long windowStart = System.nanoTime();
        long windowFrames = 0;
        long windowBytes = 0;
        long windowEncodeNanos = 0;
        while (session.running) {
            final StreamVideoSource video = session.video;
            final StreamPlayer player = session.player;
            if (video == null || player == null) return;
            final int[] frame = video.poll();
            if (frame == null) {
                try {
                    Thread.sleep(2L);
                } catch (InterruptedException interrupted) {
                    return;
                }
                continue;
            }

            batch.clear();
            batch.add(new BundlePacket());
            final long encodeStart = System.nanoTime();
            final int pushed = player.push(frame, sink);
            final long encodeElapsed = System.nanoTime() - encodeStart;
            windowEncodeNanos += encodeElapsed;
            video.recycle(frame);
            session.bytesSent.addAndGet(pushed);
            batch.add(new BundlePacket());

            emaNanos = (emaNanos * 7 + Math.max(frameNanos, encodeElapsed)) / 8;
            final long wait = nextSend - System.nanoTime();
            if (wait > 1_000_000L) {
                try {
                    Thread.sleep(wait / 1_000_000L);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
            if (batch.size() > 2) owner.sendPackets(new ArrayList<>(batch));
            nextSend = Math.max(nextSend + emaNanos, System.nanoTime() - emaNanos / 2);

            windowFrames++;
            windowBytes += pushed;
            final long elapsed = System.nanoTime() - windowStart;
            if (elapsed >= 1_000_000_000L) {
                session.serverFps = windowFrames * 1e9 / elapsed;
                session.encodeMs = windowFrames == 0 ? 0 : windowEncodeNanos / 1e6 / windowFrames;
                session.mbit = windowBytes * 8.0 * 1e9 / elapsed / 1e6;
                windowStart = System.nanoTime();
                windowFrames = 0;
                windowBytes = 0;
                windowEncodeNanos = 0;
            }
        }
    }

    public String codecStatus(PlayerId player) {
        final Session session = sessions.get(player.getUuid());
        if (session == null || session.player == null) return null;
        return String.format(
                "server %.1f fps, encode %.1f ms, %.1f Mbit/s raw",
                session.serverFps, session.encodeMs, session.mbit);
    }

    public long framesShown(PlayerId player) {
        final Session session = sessions.get(player.getUuid());
        return session == null || session.video == null ? 0L : session.video.frames();
    }

    public String videoFailure(PlayerId player) {
        final Session session = sessions.get(player.getUuid());
        return session == null || session.video == null ? null : session.video.failure();
    }

    public void tick(PlayerId player, StreamGeometry geometry) {
        final Session session = sessions.get(player.getUuid());
        if (session == null) return;

        final Entity anchor = camera.cameraEntityFor(player);
        if (anchor != null && anchor.getInstance() != null && --session.relockTicks <= 0) {
            session.relockTicks = RELOCK_INTERVAL_TICKS;
            final net.minestom.server.coordinate.Pos at = carrierPos(players.entity(player), anchor);
            for (Entity carrier : session.carriers) {
                if (carrier.getInstance() != null) carrier.teleport(at);
            }
        }
    }

    private net.minestom.server.coordinate.Pos carrierPos(Player owner, Entity anchor) {
        final net.minestom.server.coordinate.Pos base = anchor.getPosition();
        if (owner == null) return base;
        final net.minestom.server.coordinate.Vec look = owner.getPosition().direction();
        return base.add(look.mul(CARRIER_OFFSET)).withView(owner.getPosition().yaw(), 0f);
    }

    private ItemStack mapItem(int mapId) {
        return ItemStack.builder(Material.FILLED_MAP).set(DataComponents.MAP_ID, mapId).build();
    }
}
