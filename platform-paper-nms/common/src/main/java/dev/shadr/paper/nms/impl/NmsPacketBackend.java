/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper.nms.impl;

import dev.shadr.paper.nms.FakeEntityKind;
import dev.shadr.paper.nms.MetaValue;
import dev.shadr.paper.nms.PacketBackend;
import io.papermc.paper.adventure.PaperAdventure;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class NmsPacketBackend implements PacketBackend {

    private final ThreadLocal<List<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>>>
            collecting = new ThreadLocal<>();

    @Override
    public int nextEntityId(Player viewer) {
        return EntityIds.next(viewer);
    }

    @Override
    public void spawn(Player viewer, int entityId, FakeEntityKind kind, Location at, int data) {
        send(viewer, new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(
                entityId,
                UUID.randomUUID(),
                at.getX(),
                at.getY(),
                at.getZ(),
                at.getPitch(),
                at.getYaw(),
                Types.of(kind),
                data,
                Vec3.ZERO,
                at.getYaw()));
    }

    @Override
    public void metadata(Player viewer, int entityId, List<MetaValue> values) {
        if (values.isEmpty()) return;
        List<SynchedEntityData.DataValue<?>> packed = new ArrayList<>(values.size());
        for (MetaValue value : values) packed.add(pack(value));
        send(viewer, new ClientboundSetEntityDataPacket(entityId, packed));
    }

    @Override
    public void passengers(Player viewer, int vehicleId, int... passengerIds) {
        send(viewer, SetPassengers.create(vehicleId, passengerIds));
    }

    @Override
    public void mountOn(Player viewer, int... passengerIds) {
        passengers(viewer, viewer.getEntityId(), passengerIds);
    }

    @Override
    public void camera(Player viewer, int entityId) {
        send(viewer, SetCamera.create(entityId));
    }

    @Override
    public void resetCamera(Player viewer) {
        send(viewer, SetCamera.create(viewer.getEntityId()));
    }

    @Override
    public void teleport(Player viewer, int entityId, Location to) {
        send(viewer, Teleport.create(entityId, to));
    }

    @Override
    public void mapData(Player viewer, int mapId, int startX, int startY, int width, int height, byte[] colors) {
        if (width <= 0 || height <= 0) return;
        if (colors.length != width * height) {
            throw new IllegalArgumentException(
                    "patch is " + width + "x" + height + " but carries " + colors.length + " bytes");
        }
        send(viewer, new ClientboundMapItemDataPacket(
                new MapId(mapId),
                (byte) 0,
                false,
                null,
                new MapItemSavedData.MapPatch(startX, startY, width, height, colors)));
    }

    @Override
    public void remove(Player viewer, int... entityIds) {
        if (entityIds.length == 0) return;
        send(viewer, new ClientboundRemoveEntitiesPacket(entityIds));
    }

    @Override
    public void bundle(Player viewer, Runnable body) {
        if (collecting.get() != null) {
            body.run();
            return;
        }
        List<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> batch = new ArrayList<>();
        collecting.set(batch);
        try {
            body.run();
        } finally {
            collecting.remove();
        }
        if (batch.isEmpty()) return;
        connection(viewer).send(new ClientboundBundlePacket(batch));
    }

    private void send(Player viewer, Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> packet) {
        List<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> batch = collecting.get();
        if (batch != null) {
            batch.add(packet);
            return;
        }
        connection(viewer).send(packet);
    }

    private static net.minecraft.server.network.ServerGamePacketListenerImpl connection(Player viewer) {
        ServerPlayer handle = ((CraftPlayer) viewer).getHandle();
        return handle.connection;
    }

    private static SynchedEntityData.DataValue<?> pack(MetaValue value) {
        return switch (value) {
            case MetaValue.Bytes v -> new SynchedEntityData.DataValue<>(v.index(), EntityDataSerializers.BYTE, v.value());
            case MetaValue.Ints v -> new SynchedEntityData.DataValue<>(v.index(), EntityDataSerializers.INT, v.value());
            case MetaValue.Floats v -> new SynchedEntityData.DataValue<>(v.index(), EntityDataSerializers.FLOAT, v.value());
            case MetaValue.Booleans v -> new SynchedEntityData.DataValue<>(v.index(), EntityDataSerializers.BOOLEAN, v.value());
            case MetaValue.Text v -> new SynchedEntityData.DataValue<>(
                    v.index(), EntityDataSerializers.COMPONENT, PaperAdventure.asVanilla(v.value()));
            case MetaValue.OptionalText v -> new SynchedEntityData.DataValue<>(
                    v.index(), EntityDataSerializers.OPTIONAL_COMPONENT, Optional.of(PaperAdventure.asVanilla(v.value())));
            case MetaValue.Vector3 v -> new SynchedEntityData.DataValue<>(
                    v.index(), EntityDataSerializers.VECTOR3, new Vector3f(v.x(), v.y(), v.z()));
            case MetaValue.Quaternion v -> new SynchedEntityData.DataValue<>(
                    v.index(), EntityDataSerializers.QUATERNION, new Quaternionf(v.x(), v.y(), v.z(), v.w()));
            case MetaValue.Item v -> new SynchedEntityData.DataValue<>(
                    v.index(), EntityDataSerializers.ITEM_STACK, CraftItemStack.asNMSCopy(v.value()));
        };
    }
}
