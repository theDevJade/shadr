/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
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
