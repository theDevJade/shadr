/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper.nms;

import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface PacketBackend {

    int nextEntityId(Player viewer);

    void spawn(Player viewer, int entityId, FakeEntityKind kind, Location at);

    void metadata(Player viewer, int entityId, List<MetaValue> values);

    void passengers(Player viewer, int vehicleId, int... passengerIds);

    void mountOn(Player viewer, int... passengerIds);

    void camera(Player viewer, int entityId);

    void resetCamera(Player viewer);

    void teleport(Player viewer, int entityId, Location to);

    void remove(Player viewer, int... entityIds);

    void bundle(Player viewer, Runnable body);
}
