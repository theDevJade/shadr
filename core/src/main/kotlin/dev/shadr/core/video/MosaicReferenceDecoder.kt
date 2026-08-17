/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

object MosaicReferenceDecoder {

    fun decode(clip: MosaicClip): List<IntArray> {
        val frames = ArrayList<IntArray>(clip.frameCount)
        var previous = IntArray(clip.width * clip.height)
        for (frame in 0 until clip.frameCount) {
            val out = IntArray(clip.width * clip.height)
            decodeFrame(clip, frame, previous, out)
            frames += out
            previous = out
        }
        return frames
    }

    fun decodeFrame(clip: MosaicClip, frame: Int, previous: IntArray, out: IntArray) {
        val width = clip.width
        val height = clip.height

        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = pixel(clip, frame, previous, x, y)
            }
        }
    }

    private fun pixel(clip: MosaicClip, frame: Int, previous: IntArray, x: Int, y: Int): Int {
        val plane = MosaicFormat.superblockIndex(
            frame = frame,
            sx = x / MosaicFormat.SUPER,
            sy = y / MosaicFormat.SUPER,
            columns = clip.superColumns,
            perFrame = clip.superblocksPerFrame,
        )
        val superblock = clip.data[plane]
        if (MosaicFormat.red(superblock) == MosaicFormat.SB_SKIP) {
            return sample(previous, clip, x, y)
        }
        if (MosaicFormat.red(superblock) == MosaicFormat.SB_MOTION) {
            return sample(
                previous, clip,
                x + MosaicFormat.green(superblock) - MosaicFormat.MV_BIAS,
                y + MosaicFormat.blue(superblock) - MosaicFormat.MV_BIAS,
            )
        }

        val inSuper = ((y % MosaicFormat.SUPER) / MosaicFormat.BLOCK) * MosaicFormat.BLOCKS_PER_SUPER_EDGE +
            ((x % MosaicFormat.SUPER) / MosaicFormat.BLOCK)
        val command = clip.data[MosaicFormat.pointer(superblock) + inSuper]

        return when (MosaicFormat.red(command)) {
            MosaicFormat.BLK_SKIP -> sample(previous, clip, x, y)

            MosaicFormat.BLK_MOTION -> sample(
                previous, clip,
                x + MosaicFormat.green(command) - MosaicFormat.MV_BIAS,
                y + MosaicFormat.blue(command) - MosaicFormat.MV_BIAS,
            )

            MosaicFormat.BLK_DELTA -> {
                val payload = clip.data[MosaicFormat.pointer(command) + quarter(x, y)]
                val base = sample(previous, clip, x, y)
                rgb(
                    ((base ushr 16) and 0xFF) + MosaicFormat.red(payload) - MosaicFormat.MV_BIAS,
                    ((base ushr 8) and 0xFF) + MosaicFormat.green(payload) - MosaicFormat.MV_BIAS,
                    (base and 0xFF) + MosaicFormat.blue(payload) - MosaicFormat.MV_BIAS,
                )
            }

            else -> intra(clip, command, x, y)
        }
    }

    private fun intra(clip: MosaicClip, command: Int, x: Int, y: Int): Int {
        val at = MosaicFormat.pointer(command) + quarter(x, y) * 2
        val endpoints = clip.data[at]
        val indices = clip.data[at + 1]

        val c0 = expand565((MosaicFormat.green(endpoints) shl 8) or MosaicFormat.red(endpoints))
        val c1 = expand565((MosaicFormat.alpha(endpoints) shl 8) or MosaicFormat.blue(endpoints))

        val row = when (y % 4) {
            0 -> MosaicFormat.red(indices)
            1 -> MosaicFormat.green(indices)
            2 -> MosaicFormat.blue(indices)
            else -> MosaicFormat.alpha(indices)
        }
        return when ((row ushr ((x % 4) * 2)) and 3) {
            0 -> rgb(c0[0], c0[1], c0[2])
            1 -> rgb(c1[0], c1[1], c1[2])
            2 -> rgb(
                (2 * c0[0] + c1[0]) / 3,
                (2 * c0[1] + c1[1]) / 3,
                (2 * c0[2] + c1[2]) / 3,
            )
            else -> rgb(
                (c0[0] + 2 * c1[0]) / 3,
                (c0[1] + 2 * c1[1]) / 3,
                (c0[2] + 2 * c1[2]) / 3,
            )
        }
    }

    fun quarter(x: Int, y: Int): Int = ((y % MosaicFormat.BLOCK) / 4) * 2 + ((x % MosaicFormat.BLOCK) / 4)

    /** RGB565 to three bytes. */
    fun expand565(packed: Int): IntArray {
        val r = (packed ushr 11) and 0x1F
        val g = (packed ushr 5) and 0x3F
        val b = packed and 0x1F
        return intArrayOf(
            (r shl 3) or (r ushr 2),
            (g shl 2) or (g ushr 4),
            (b shl 3) or (b ushr 2),
        )
    }

    fun pack565(r: Int, g: Int, b: Int): Int =
        ((r.coerceIn(0, 255) shr 3) shl 11) or
            ((g.coerceIn(0, 255) shr 2) shl 5) or
            (b.coerceIn(0, 255) shr 3)

    private fun sample(previous: IntArray, clip: MosaicClip, x: Int, y: Int): Int =
        previous[y.coerceIn(0, clip.height - 1) * clip.width + x.coerceIn(0, clip.width - 1)]

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
