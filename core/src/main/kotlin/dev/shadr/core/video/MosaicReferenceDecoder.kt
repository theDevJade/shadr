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

        val base = MosaicFormat.pointer(superblock)
        val h0 = clip.data[base]
        val h1 = clip.data[base + 1]
        val block =
            ((y % MosaicFormat.SUPER) / MosaicFormat.BLOCK) * MosaicFormat.BLOCKS_PER_SUPER_EDGE +
                ((x % MosaicFormat.SUPER) / MosaicFormat.BLOCK)

        var mode = 0
        var commandSlot = 0
        var payloadOffset = 0
        var commands = 0
        for (j in 0 until MosaicFormat.BLOCKS_PER_SUPER) {
            val n = MosaicFormat.nibble(if (j < 8) h0 else h1, j)
            if (j == block) {
                mode = n
                commandSlot = commands
            }
            if (j < block) payloadOffset += MosaicFormat.payloadTexels(n)
            commands += MosaicFormat.commandTexels(n)
        }

        val commandAt = base + MosaicFormat.HEADER_TEXELS + commandSlot
        val payloadAt = base + MosaicFormat.HEADER_TEXELS + commands + payloadOffset
        val quarter = quarter(x, y)

        return when (mode) {
            MosaicFormat.BLK_SKIP -> sample(previous, clip, x, y)

            MosaicFormat.BLK_MOTION -> {
                val command = clip.data[commandAt]
                sample(
                    previous, clip,
                    x + MosaicFormat.red(command) - MosaicFormat.MV_BIAS,
                    y + MosaicFormat.green(command) - MosaicFormat.MV_BIAS,
                )
            }

            MosaicFormat.BLK_DELTA -> {
                val payload = clip.data[payloadAt + quarter]
                offsetFrom(sample(previous, clip, x, y), payload)
            }

            MosaicFormat.BLK_MOTION_DELTA -> {
                val command = clip.data[commandAt]
                val payload = clip.data[payloadAt + quarter]
                offsetFrom(
                    sample(
                        previous, clip,
                        x + MosaicFormat.red(command) - MosaicFormat.MV_BIAS,
                        y + MosaicFormat.green(command) - MosaicFormat.MV_BIAS,
                    ),
                    payload,
                )
            }

            MosaicFormat.BLK_INTRA -> bc1(
                clip.data[payloadAt + quarter * 2],
                clip.data[payloadAt + quarter * 2 + 1],
                x, y,
            )

            MosaicFormat.BLK_MOTION_RESIDUAL -> {
                val command = clip.data[commandAt]
                val residual = bc1(
                    clip.data[payloadAt + quarter * 2],
                    clip.data[payloadAt + quarter * 2 + 1],
                    x, y,
                )
                val predicted = sample(
                    previous, clip,
                    x + MosaicFormat.red(command) - MosaicFormat.MV_BIAS,
                    y + MosaicFormat.green(command) - MosaicFormat.MV_BIAS,
                )
                rgb(
                    ((predicted ushr 16) and 0xFF) +
                        ((residual ushr 16) and 0xFF) - MosaicFormat.RESIDUAL_BIAS,
                    ((predicted ushr 8) and 0xFF) +
                        ((residual ushr 8) and 0xFF) - MosaicFormat.RESIDUAL_BIAS,
                    (predicted and 0xFF) + (residual and 0xFF) - MosaicFormat.RESIDUAL_BIAS,
                )
            }

            MosaicFormat.BLK_ENDPOINT_REUSE -> {
                val command = clip.data[commandAt]
                bc1(
                    clip.data[MosaicFormat.pointer(command) + quarter * 2],
                    clip.data[payloadAt + quarter],
                    x, y,
                )
            }

            else -> {
                val payload = clip.data[payloadAt + quarter]
                val entry = MosaicFormat.red(payload) or (MosaicFormat.green(payload) shl 8)
                val selector = MosaicFormat.blue(payload) or (MosaicFormat.alpha(payload) shl 8)
                bc1(
                    clip.data[clip.codebookBase + entry],
                    clip.data[clip.codebookBase + MosaicFormat.CODEBOOK + selector],
                    x, y,
                )
            }
        }
    }

    private fun offsetFrom(base: Int, payload: Int): Int = rgb(
        ((base ushr 16) and 0xFF) + MosaicFormat.red(payload) - MosaicFormat.MV_BIAS,
        ((base ushr 8) and 0xFF) + MosaicFormat.green(payload) - MosaicFormat.MV_BIAS,
        (base and 0xFF) + MosaicFormat.blue(payload) - MosaicFormat.MV_BIAS,
    )

    private fun bc1(endpoints: Int, indices: Int, x: Int, y: Int): Int {
        val e0 = (MosaicFormat.green(endpoints) shl 8) or MosaicFormat.red(endpoints)
        val e1 = (MosaicFormat.alpha(endpoints) shl 8) or MosaicFormat.blue(endpoints)
        val palette = MosaicBc1.paletteOf(e0, e1)

        val row = when (y % 4) {
            0 -> MosaicFormat.red(indices)
            1 -> MosaicFormat.green(indices)
            2 -> MosaicFormat.blue(indices)
            else -> MosaicFormat.alpha(indices)
        }
        val at = ((row ushr ((x % 4) * 2)) and 3) * 3
        return rgb(palette[at], palette[at + 1], palette[at + 2])
    }

    fun quarter(x: Int, y: Int): Int =
        ((y % MosaicFormat.BLOCK) / 4) * 2 + ((x % MosaicFormat.BLOCK) / 4)

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
