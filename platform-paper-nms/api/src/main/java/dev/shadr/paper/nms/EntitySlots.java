/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper.nms;

public interface EntitySlots {

    EntitySlots DEFAULT = new EntitySlots() {};

    default int sharedFlags() {
        return 0;
    }

    default byte invisibleFlag() {
        return 0x20;
    }

    default int interpolationDelay() {
        return 8;
    }

    default int interpolationDuration() {
        return 9;
    }

    default int translation() {
        return 11;
    }

    default int scale() {
        return 12;
    }

    default int leftRotation() {
        return 13;
    }

    default int rightRotation() {
        return 14;
    }

    default int billboard() {
        return 15;
    }

    default byte billboardFixed() {
        return 0;
    }

    default int brightness() {
        return 16;
    }

    default int brightnessFull() {
        return (15 << 20) | (15 << 4);
    }

    default int viewRange() {
        return 17;
    }

    default int item() {
        return 23;
    }

    default int text() {
        return 23;
    }

    default int lineWidth() {
        return 24;
    }

    default int backgroundColor() {
        return 25;
    }

    default int textOpacity() {
        return 26;
    }

    default int textFlags() {
        return 27;
    }

    default int frameItem() {
        return 9;
    }

    default int frameRotation() {
        return 10;
    }

    default int frameFacing() {
        return 2;
    }
}
