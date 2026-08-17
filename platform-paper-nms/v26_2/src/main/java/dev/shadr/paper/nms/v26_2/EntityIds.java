/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
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
