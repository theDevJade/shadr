/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper.nms.v26_2;

import dev.shadr.paper.nms.FakeEntityKind;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

final class Types {

    private Types() {}

    static EntityType<?> of(FakeEntityKind kind) {
        return switch (kind) {
            case TEXT_DISPLAY -> EntityTypes.TEXT_DISPLAY;
            case ITEM_DISPLAY -> EntityTypes.ITEM_DISPLAY;
            case BLOCK_DISPLAY -> EntityTypes.BLOCK_DISPLAY;
            case INTERACTION -> EntityTypes.INTERACTION;
            case ITEM_FRAME -> EntityTypes.ITEM_FRAME;
            case ENDERMAN -> EntityTypes.ENDERMAN;
            case CREEPER -> EntityTypes.CREEPER;
        };
    }
}
