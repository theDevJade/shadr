/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper.nms.impl;

import dev.shadr.paper.nms.FakeEntityKind;
import net.minecraft.world.entity.EntityType;

final class Types {

    private Types() {}

    static EntityType<?> of(FakeEntityKind kind) {
        return switch (kind) {
            case TEXT_DISPLAY -> EntityType.TEXT_DISPLAY;
            case ITEM_DISPLAY -> EntityType.ITEM_DISPLAY;
            case BLOCK_DISPLAY -> EntityType.BLOCK_DISPLAY;
            case INTERACTION -> EntityType.INTERACTION;
            case ENDERMAN -> EntityType.ENDERMAN;
        };
    }
}
