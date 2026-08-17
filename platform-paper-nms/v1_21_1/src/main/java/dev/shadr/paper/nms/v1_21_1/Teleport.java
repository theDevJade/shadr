/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper.nms.v1_21_1;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.bukkit.Location;

final class Teleport {

    private Teleport() {}

    static ClientboundTeleportEntityPacket create(int entityId, Location to) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(entityId);
        buf.writeDouble(to.getX());
        buf.writeDouble(to.getY());
        buf.writeDouble(to.getZ());
        buf.writeByte((byte) (int) (to.getYaw() * 256.0F / 360.0F));
        buf.writeByte((byte) (int) (to.getPitch() * 256.0F / 360.0F));
        buf.writeBoolean(false);
        return ClientboundTeleportEntityPacket.STREAM_CODEC.decode(buf);
    }
}
