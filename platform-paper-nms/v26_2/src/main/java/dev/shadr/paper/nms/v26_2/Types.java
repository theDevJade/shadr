/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
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
            case ENDERMAN -> EntityTypes.ENDERMAN;
        };
    }
}
