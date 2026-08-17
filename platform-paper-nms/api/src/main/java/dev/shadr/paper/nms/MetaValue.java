/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper.nms;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

public sealed interface MetaValue {

    int index();

    record Bytes(int index, byte value) implements MetaValue {}

    record Ints(int index, int value) implements MetaValue {}

    record Floats(int index, float value) implements MetaValue {}

    record Booleans(int index, boolean value) implements MetaValue {}

    record Text(int index, Component value) implements MetaValue {}

    record OptionalText(int index, Component value) implements MetaValue {}

    record Vector3(int index, float x, float y, float z) implements MetaValue {}

    record Quaternion(int index, float x, float y, float z, float w) implements MetaValue {}

    record Item(int index, ItemStack value) implements MetaValue {}

    static MetaValue of(int index, byte value) {
        return new Bytes(index, value);
    }

    static MetaValue of(int index, int value) {
        return new Ints(index, value);
    }

    static MetaValue of(int index, float value) {
        return new Floats(index, value);
    }

    static MetaValue of(int index, boolean value) {
        return new Booleans(index, value);
    }

    static MetaValue text(int index, Component value) {
        return new Text(index, value);
    }

    static MetaValue optionalText(int index, Component value) {
        return new OptionalText(index, value);
    }

    static MetaValue vector3(int index, float x, float y, float z) {
        return new Vector3(index, x, y, z);
    }

    static MetaValue quaternion(int index, float x, float y, float z, float w) {
        return new Quaternion(index, x, y, z, w);
    }

    static MetaValue item(int index, ItemStack value) {
        return new Item(index, value);
    }
}
