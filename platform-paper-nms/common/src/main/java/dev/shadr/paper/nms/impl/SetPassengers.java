/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.paper.nms.impl;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;

final class SetPassengers {

    private SetPassengers() {}

    static ClientboundSetPassengersPacket create(int vehicleId, int... passengerIds) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(vehicleId);
        buf.writeVarInt(passengerIds.length);
        for (int passenger : passengerIds) buf.writeVarInt(passenger);
        return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf);
    }
}
