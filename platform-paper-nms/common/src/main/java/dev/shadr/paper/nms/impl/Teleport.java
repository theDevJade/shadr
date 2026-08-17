/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.paper.nms.impl;

import java.util.Set;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;

final class Teleport {

    private Teleport() {}

    static ClientboundTeleportEntityPacket create(int entityId, Location to) {
        PositionMoveRotation move = new PositionMoveRotation(
                new Vec3(to.getX(), to.getY(), to.getZ()),
                Vec3.ZERO,
                to.getYaw(),
                to.getPitch());
        return ClientboundTeleportEntityPacket.teleport(entityId, move, Set.of(), false);
    }
}
