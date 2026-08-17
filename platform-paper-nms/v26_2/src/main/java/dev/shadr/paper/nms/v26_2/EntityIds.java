/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper.nms.v26_2;

import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

final class EntityIds {

    private EntityIds() {}

    static int next(Player viewer) {
        return ((CraftWorld) viewer.getWorld()).getHandle().getNextEntityId();
    }
}
