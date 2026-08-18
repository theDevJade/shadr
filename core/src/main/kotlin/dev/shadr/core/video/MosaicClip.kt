/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

class MosaicClip(
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val fps: Double,
    val data: IntArray,
    val texelCount: Int,
    val codebookBase: Int = 0,
) {
    val superColumns: Int = MosaicFormat.superColumns(width)

    val superRows: Int = MosaicFormat.superRows(height)

    val superblocksPerFrame: Int = superColumns * superRows

    val bytes: Int get() = texelCount * 4

    val bytesPerFrame: Double get() = bytes.toDouble() / frameCount

    val compression: Double
        get() = (width.toDouble() * height * 3 * frameCount) / bytes
}
