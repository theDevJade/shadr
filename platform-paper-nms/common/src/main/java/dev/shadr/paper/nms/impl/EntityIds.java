/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.paper.nms.impl;

import net.minecraft.world.entity.Entity;
import org.bukkit.entity.Player;

final class EntityIds {

    private EntityIds() {}

    static int next(Player viewer) {
        return Entity.nextEntityId();
    }
}
