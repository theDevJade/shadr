/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
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
