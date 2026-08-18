/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.video

import kotlin.math.pow


// This required too much research, and reference code
// I still barely understand how this works.
object MosaicEncoder {

    data class Options(
        val quality: Int = 24,
        val gopFrames: Int = Int.MAX_VALUE,
        val motionSearch: Int = 24,
        val targetTexelsPerFrame: Int = 0,
        val codebook: Boolean = true,
        val codebookThreshold: Long = MosaicFormat.CODEBOOK_TEXELS * 4L,
        val onProgress: ((Progress) -> Unit)? = null,
    )

    class Progress(
        val pass: Int,
        val passes: Int,
        val frame: Int,
        val frameCount: Int,
        val texels: Int,
        val width: Int,
        val height: Int,
    ) {
        val fraction: Double
            get() = ((pass - 1) + (frame + 1).toDouble() / frameCount) / passes

        val fillFraction: Double get() = texels.toDouble() / MosaicFormat.MAX_TEXELS

        val secondsPerSheet: Double
            get() = if (texels <= 0) 0.0 else (frame + 1).toDouble() / fillFraction
    }

    class Stats {
        var skip = 0
        var motion = 0
        var delta = 0
        var intra = 0
        var motionDelta = 0
        var motionResidual = 0
        var endpointReuse = 0
        var intraVq = 0
        var superSkip = 0
        var superMotion = 0
        var superCoded = 0
        var planeTexels = 0
        var commandTexels = 0
        var payloadTexels = 0
        var codebookTexels = 0
        var keepFrames = false

        val reconstructed = mutableListOf<IntArray>()

        val quality = mutableListOf<MosaicQuality.Frame>()

        class FrameBill(
            val index: Int,
            val modes: IntArray,
            val payloadTexels: Int,
            val commandTexels: Int,
        )

        val bills = mutableListOf<FrameBill>()

        val report: MosaicQuality.Report?
            get() = if (quality.isEmpty()) {
                null
            } else {
                MosaicQuality.Report(
                    psnr = quality.sumOf { it.psnr } / quality.size,
                    ssim = quality.sumOf { it.ssim } / quality.size,
                    frames = quality.toList(),
                )
            }

        val blocks: Int
            get() = skip + motion + delta + intra +
                motionDelta + motionResidual + endpointReuse + intraVq

        val texels: Int get() = planeTexels + commandTexels + payloadTexels + codebookTexels

        val fillFraction: Double get() = texels.toDouble() / MosaicFormat.MAX_TEXELS

        internal fun reset() {
            skip = 0; motion = 0; delta = 0; intra = 0
            motionDelta = 0; motionResidual = 0; endpointReuse = 0; intraVq = 0
            superSkip = 0; superMotion = 0; superCoded = 0
            planeTexels = 0; commandTexels = 0; payloadTexels = 0; codebookTexels = 0
            reconstructed.clear()
            quality.clear()
            bills.clear()
        }

        override fun toString(): String =
            "blocks=$blocks skip=$skip motion=$motion delta=$delta motionDelta=$motionDelta " +
                "residual=$motionResidual reuse=$endpointReuse intra=$intra intraVq=$intraVq " +
                "superblocks=${superSkip + superMotion + superCoded} " +
                "(skipped $superSkip, moved $superMotion) " +
                "texels: plane=$planeTexels commands=$commandTexels payload=$payloadTexels " +
                "codebook=$codebookTexels fill=${"%.1f".format(fillFraction * 100)}%"
    }

    fun encode(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        fps: Double,
        options: Options = Options(),
        stats: Stats? = null,
    ): MosaicClip? =
        encode(frames.size, width, height, fps, options, stats) { _ ->
            { index -> frames.getOrNull(index) }
        }

    fun encode(
        frameCount: Int,
        width: Int,
        height: Int,
        fps: Double,
        options: Options = Options(),
        stats: Stats? = null,
        source: (Int) -> ((Int) -> IntArray?),
    ): MosaicClip? {
        if (frameCount < 1 || width < 1 || height < 1) return null

        val columns = MosaicFormat.superColumns(width)
        val rows = MosaicFormat.superRows(height)
        val plane = frameCount.toLong() * columns * rows
        if (plane > MosaicFormat.MAX_TEXELS) return null
        val planeTexels = plane.toInt()

        val trainer = if (options.codebook) Trainer() else null
        val passes = if (options.codebook) 2 else 1
        val first = encodePass(
            frameCount, width, height, fps, options, stats, trainer, null, 1, passes, source(0),
        ) ?: return null

        if (trainer == null || trainer.quarters < options.codebookThreshold) return first

        val payload = (first.texelCount - planeTexels).coerceAtLeast(1)
        if (trainer.quarters * 2.0 < payload * CODEBOOK_MIN_SHARE) return first

        val book = MosaicCodebook.build(trainer.endpointCounts, trainer.selectorCounts)
        stats?.reset()
        return encodePass(
            frameCount, width, height, fps, options, stats, null, book, 2, passes, source(1),
        )
    }

    private fun encodePass(
        frameCount: Int,
        width: Int,
        height: Int,
        fps: Double,
        options: Options,
        stats: Stats?,
        trainer: Trainer?,
        book: MosaicCodebook?,
        pass: Int,
        passes: Int,
        next: (Int) -> IntArray?,
    ): MosaicClip? {
        val columns = MosaicFormat.superColumns(width)
        val rows = MosaicFormat.superRows(height)
        val perFrame = columns * rows
        val planeTexels = frameCount * perFrame

        val sheet = Sheet(planeTexels)
        var codebookBase = 0
        if (book != null) {
            codebookBase = sheet.allocate(MosaicFormat.CODEBOOK_TEXELS) ?: return null
            for (i in 0 until MosaicFormat.CODEBOOK) {
                val pair = book.endpoints[i]
                val e0 = MosaicCodebook.pairE0(pair)
                val e1 = MosaicCodebook.pairE1(pair)
                sheet.data[codebookBase + i] = MosaicFormat.texel(
                    e0 and 0xFF, (e0 ushr 8) and 0xFF, e1 and 0xFF, (e1 ushr 8) and 0xFF,
                )
                val pattern = MosaicCodebook.patternRows(book.selectors[i])
                sheet.data[codebookBase + MosaicFormat.CODEBOOK + i] =
                    MosaicFormat.texel(pattern[0], pattern[1], pattern[2], pattern[3])
            }
            stats?.codebookTexels = MosaicFormat.CODEBOOK_TEXELS
        }
        stats?.planeTexels = planeTexels

        val payloadStart = sheet.top
        val budget = options.targetTexelsPerFrame.toLong() * frameCount

        val blocksX = (width + MosaicFormat.BLOCK - 1) / MosaicFormat.BLOCK
        val blocksY = (height + MosaicFormat.BLOCK - 1) / MosaicFormat.BLOCK
        var prevMvX = IntArray(blocksX * blocksY)
        var prevMvY = IntArray(blocksX * blocksY)
        var curMvX = IntArray(blocksX * blocksY)
        var curMvY = IntArray(blocksX * blocksY)
        val intraAt = IntArray(blocksX * blocksY)

        var rate = options.quality.toDouble().coerceAtLeast(0.5)
        var previous = IntArray(width * height)
        var encoded = 0

        for (index in 0 until frameCount) {
            val source = next(index) ?: break
            if (source.size != width * height) return null
            encoded++
            val current = previous.copyOf()
            val keyframe = index % options.gopFrames == 0
            java.util.Arrays.fill(intraAt, -1)
            java.util.Arrays.fill(curMvX, 0)
            java.util.Arrays.fill(curMvY, 0)
            val frameStart = sheet.top

            val frame = Frame(
                source = source,
                previous = previous,
                current = current,
                width = width,
                height = height,
                keyframe = keyframe,
                rate = rate,
                book = book,
                trainer = trainer,
                prevMvX = prevMvX,
                prevMvY = prevMvY,
                curMvX = curMvX,
                curMvY = curMvY,
                intraAt = intraAt,
                blocksX = blocksX,
                sheet = sheet,
                stats = stats,
                bill = IntArray(MosaicFormat.MODES),
            )

            val planned = arrayOfNulls<SuperPlan>(perFrame)
            java.util.stream.IntStream.range(0, perFrame).parallel().forEach { at ->
                planned[at] = planSuperblock(frame, at % columns, at / columns, options)
            }

            for (at in 0 until perFrame) {
                val planeIndex = MosaicFormat.superblockIndex(
                    index, at % columns, at / columns, columns, perFrame,
                )
                sheet.data[planeIndex] =
                    commitSuperblock(frame, planned[at]!!, at % columns, at / columns) ?: return null
            }

            java.util.stream.IntStream.range(0, perFrame).parallel().forEach { at ->
                emitSuperblock(frame, planned[at]!!, at % columns, at / columns)
            }

            val spent = sheet.top - frameStart
            if (stats != null) {
                stats.bills += Stats.FrameBill(
                    index = index,
                    modes = frame.bill,
                    payloadTexels = frame.payloadSpent,
                    commandTexels = frame.commandSpent,
                )
                stats.payloadTexels += frame.payloadSpent
                stats.commandTexels += frame.commandSpent
                stats.quality += MosaicQuality.Frame(
                    index = index,
                    psnr = MosaicQuality.psnr(source, current),
                    ssim = MosaicQuality.ssim(source, current, width, height),
                )
                if (stats.keepFrames) stats.reconstructed += current.copyOf()
            }

            if (options.targetTexelsPerFrame > 0) {
                val framesLeft = frameCount - index - 1
                val budgetLeft = budget - (sheet.top - payloadStart)
                rate = if (budgetLeft <= 0L || framesLeft <= 0) {
                    (rate * MAX_COARSEN).coerceAtMost(options.quality * MAX_RATE_FACTOR)
                } else {
                    val target = budgetLeft.toDouble() / framesLeft
                    val ratio = spent.coerceAtLeast(1) / target
                    if (ratio > 1.0) {
                        (rate * ratio.pow(RATE_GAIN).coerceAtMost(MAX_COARSEN))
                            .coerceAtMost(options.quality * MAX_RATE_FACTOR)
                    } else {
                        (rate * ratio.pow(RATE_GAIN).coerceAtLeast(MAX_REFINE))
                            .coerceAtLeast(options.quality.toDouble())
                    }
                }
            }

            options.onProgress?.invoke(
                Progress(pass, passes, index, frameCount, sheet.top, width, height),
            )

            previous = current
            val mvX = prevMvX; val mvY = prevMvY
            prevMvX = curMvX; prevMvY = curMvY
            curMvX = mvX; curMvY = mvY
        }
        if (encoded == 0) return null

        return MosaicClip(width, height, encoded, fps, sheet.data, sheet.top, codebookBase)
    }

    private class Frame(
        val source: IntArray,
        val previous: IntArray,
        val current: IntArray,
        val width: Int,
        val height: Int,
        val keyframe: Boolean,
        val rate: Double,
        val book: MosaicCodebook?,
        val trainer: Trainer?,
        val prevMvX: IntArray,
        val prevMvY: IntArray,
        val curMvX: IntArray,
        val curMvY: IntArray,
        val intraAt: IntArray,
        val blocksX: Int,
        val sheet: Sheet,
        val stats: Stats?,
        val bill: IntArray,
    ) {
        var payloadSpent = 0
        var commandSpent = 0

        val price: Double get() = rate * (MosaicFormat.BLOCK * MosaicFormat.BLOCK * 3) / 4.0
    }

    private class QuarterPlan(val e0: Int, val e1: Int, val rows: IntArray)

    private class Plan(
        val mode: Int,
        val cost: Double,
        val dx: Int = 0,
        val dy: Int = 0,
        val quarters: Array<QuarterPlan>? = null,
        val offsets: IntArray? = null,
        val vq: IntArray? = null,
        val reuseGrid: Int = -1,
    )

    private const val CODEBOOK_MIN_SHARE = 0.25

    private const val RATE_GAIN = 0.35

    private const val MAX_COARSEN = 1.25

    private const val MAX_REFINE = 0.8

    private const val MAX_RATE_FACTOR = 64.0

    private class Motion(val dx: Int, val dy: Int, val sse: Double)

    private class SuperPlan(
        val mode: Int,
        val dx: Int = 0,
        val dy: Int = 0,
        val blocks: Array<Plan?>? = null,
    ) {
        /** Filled in by [commitSuperblock]. */
        var base = 0
    }

    private fun planSuperblock(
        frame: Frame,
        sx: Int,
        sy: Int,
        options: Options,
    ): SuperPlan {
        val originX = sx * MosaicFormat.SUPER
        val originY = sy * MosaicFormat.SUPER

        var sseSkip = Double.MAX_VALUE
        if (!frame.keyframe) {
            sseSkip = sse(
                frame.source, frame.previous, frame.width, frame.height,
                originX, originY, 0, 0, MosaicFormat.SUPER,
            )
            if (sseSkip == 0.0) return SuperPlan(MosaicFormat.SB_SKIP)
        }

        val plans = arrayOfNulls<Plan>(MosaicFormat.BLOCKS_PER_SUPER)
        var codedCost = MosaicFormat.HEADER_TEXELS * frame.price
        for (block in 0 until MosaicFormat.BLOCKS_PER_SUPER) {
            val bx = originX + (block % MosaicFormat.BLOCKS_PER_SUPER_EDGE) * MosaicFormat.BLOCK
            val by = originY + (block / MosaicFormat.BLOCKS_PER_SUPER_EDGE) * MosaicFormat.BLOCK
            val plan = if (bx >= frame.width || by >= frame.height) {
                Plan(MosaicFormat.BLK_SKIP, 0.0)
            } else {
                planBlock(frame, plans, block, bx, by, options)
            }
            plans[block] = plan
            codedCost += plan.cost
        }

        if (!frame.keyframe) {
            val grid = gridOf(frame, originX, originY)
            val whole = searchMotion(
                frame, originX, originY, MosaicFormat.SUPER,
                intArrayOf(frame.prevMvX[grid]), intArrayOf(frame.prevMvY[grid]),
                options,
            )
            val motionCost = if (whole.dx != 0 || whole.dy != 0) whole.sse else Double.MAX_VALUE

            if (sseSkip <= codedCost && sseSkip <= motionCost) return SuperPlan(MosaicFormat.SB_SKIP)
            if (motionCost <= codedCost) {
                return SuperPlan(MosaicFormat.SB_MOTION, whole.dx, whole.dy)
            }
        }

        return SuperPlan(MosaicFormat.SB_CODED, blocks = plans)
    }

    private fun commitSuperblock(frame: Frame, plan: SuperPlan, sx: Int, sy: Int): Int? {
        val originX = sx * MosaicFormat.SUPER
        val originY = sy * MosaicFormat.SUPER

        if (plan.mode == MosaicFormat.SB_SKIP) {
            frame.stats?.let { it.superSkip++; it.skip += MosaicFormat.BLOCKS_PER_SUPER }
            setMotion(frame, originX, originY, 0, 0)
            return MosaicFormat.texel(MosaicFormat.SB_SKIP, 0, 0, 0)
        }

        if (plan.mode == MosaicFormat.SB_MOTION) {
            frame.stats?.let { it.superMotion++; it.motion += MosaicFormat.BLOCKS_PER_SUPER }
            setMotion(frame, originX, originY, plan.dx, plan.dy)
            return MosaicFormat.texel(
                MosaicFormat.SB_MOTION,
                plan.dx + MosaicFormat.MV_BIAS,
                plan.dy + MosaicFormat.MV_BIAS,
                0,
            )
        }

        val plans = plan.blocks!!
        val modes = IntArray(MosaicFormat.BLOCKS_PER_SUPER) { plans[it]!!.mode }
        var commands = 0
        var payload = 0
        for (mode in modes) {
            commands += MosaicFormat.commandTexels(mode)
            payload += MosaicFormat.payloadTexels(mode)
        }

        val base = frame.sheet.allocate(MosaicFormat.HEADER_TEXELS + commands + payload)
            ?: return null
        frame.sheet.data[base] = MosaicFormat.headerTexel(modes, 0)
        frame.sheet.data[base + 1] = MosaicFormat.headerTexel(modes, 1)
        plan.base = base

        for (block in 0 until MosaicFormat.BLOCKS_PER_SUPER) {
            val blockPlan = plans[block]!!
            val bx = originX + (block % MosaicFormat.BLOCKS_PER_SUPER_EDGE) * MosaicFormat.BLOCK
            val by = originY + (block / MosaicFormat.BLOCKS_PER_SUPER_EDGE) * MosaicFormat.BLOCK
            countBlock(frame, blockPlan)
            if (bx < frame.width && by < frame.height) {
                val grid = gridOf(frame, bx, by)
                frame.curMvX[grid] = blockPlan.dx
                frame.curMvY[grid] = blockPlan.dy
            }
        }

        frame.stats?.superCoded++
        frame.commandSpent += MosaicFormat.HEADER_TEXELS + commands
        frame.payloadSpent += payload
        return MosaicFormat.withPointer(MosaicFormat.SB_CODED, base)
    }

    private fun countBlock(frame: Frame, plan: Plan) {
        val stats = frame.stats
        frame.bill[plan.mode]++
        when (plan.mode) {
            MosaicFormat.BLK_SKIP -> stats?.skip++
            MosaicFormat.BLK_MOTION -> stats?.motion++
            MosaicFormat.BLK_DELTA -> stats?.delta++
            MosaicFormat.BLK_MOTION_DELTA -> stats?.motionDelta++
            MosaicFormat.BLK_MOTION_RESIDUAL -> stats?.motionResidual++
            MosaicFormat.BLK_ENDPOINT_REUSE -> stats?.endpointReuse++
            MosaicFormat.BLK_INTRA -> {
                stats?.intra++
                plan.quarters?.forEach { frame.trainer?.collect(it.e0, it.e1, it.rows) }
            }
            else -> stats?.intraVq++
        }
    }

    private fun emitSuperblock(frame: Frame, plan: SuperPlan, sx: Int, sy: Int) {
        val originX = sx * MosaicFormat.SUPER
        val originY = sy * MosaicFormat.SUPER

        if (plan.mode == MosaicFormat.SB_SKIP) {
            forEachPixel(frame.width, frame.height, originX, originY, MosaicFormat.SUPER) { x, y ->
                frame.current[y * frame.width + x] = frame.previous[y * frame.width + x]
            }
            return
        }
        if (plan.mode == MosaicFormat.SB_MOTION) {
            forEachPixel(frame.width, frame.height, originX, originY, MosaicFormat.SUPER) { x, y ->
                frame.current[y * frame.width + x] =
                    sample(frame.previous, frame.width, frame.height, x + plan.dx, y + plan.dy)
            }
            return
        }

        val plans = plan.blocks!!
        val base = plan.base
        var commands = 0
        for (block in 0 until MosaicFormat.BLOCKS_PER_SUPER) {
            commands += MosaicFormat.commandTexels(plans[block]!!.mode)
        }

        var commandAt = base + MosaicFormat.HEADER_TEXELS
        var payloadAt = base + MosaicFormat.HEADER_TEXELS + commands
        val payloadOf = IntArray(MosaicFormat.BLOCKS_PER_SUPER)

        for (block in 0 until MosaicFormat.BLOCKS_PER_SUPER) {
            val blockPlan = plans[block]!!
            val bx = originX + (block % MosaicFormat.BLOCKS_PER_SUPER_EDGE) * MosaicFormat.BLOCK
            val by = originY + (block / MosaicFormat.BLOCKS_PER_SUPER_EDGE) * MosaicFormat.BLOCK
            payloadOf[block] = payloadAt
            writeBlock(frame, blockPlan, bx, by, commandAt, payloadAt, payloadOf)
            commandAt += MosaicFormat.commandTexels(blockPlan.mode)
            payloadAt += MosaicFormat.payloadTexels(blockPlan.mode)
        }
    }

    private fun writeBlock(
        frame: Frame,
        plan: Plan,
        bx: Int,
        by: Int,
        commandAt: Int,
        payloadAt: Int,
        payloadOf: IntArray,
    ) {
        when (plan.mode) {
            MosaicFormat.BLK_SKIP -> Unit

            MosaicFormat.BLK_MOTION -> {
                frame.sheet.data[commandAt] = MosaicFormat.texel(
                    plan.dx + MosaicFormat.MV_BIAS, plan.dy + MosaicFormat.MV_BIAS, 0, 0,
                )
                forEachPixel(frame.width, frame.height, bx, by) { x, y ->
                    frame.current[y * frame.width + x] =
                        sample(frame.previous, frame.width, frame.height, x + plan.dx, y + plan.dy)
                }
            }

            MosaicFormat.BLK_DELTA, MosaicFormat.BLK_MOTION_DELTA -> {
                if (plan.mode != MosaicFormat.BLK_DELTA) {
                    frame.sheet.data[commandAt] = MosaicFormat.texel(
                        plan.dx + MosaicFormat.MV_BIAS, plan.dy + MosaicFormat.MV_BIAS, 0, 0,
                    )
                }
                val offsets = plan.offsets!!
                for (q in 0 until 4) {
                    frame.sheet.data[payloadAt + q] = MosaicFormat.texel(
                        offsets[q * 3] + MosaicFormat.MV_BIAS,
                        offsets[q * 3 + 1] + MosaicFormat.MV_BIAS,
                        offsets[q * 3 + 2] + MosaicFormat.MV_BIAS,
                        0,
                    )
                }
                forEachPixel(frame.width, frame.height, bx, by) { x, y ->
                    val q = MosaicReferenceDecoder.quarter(x, y)
                    val have =
                        sample(frame.previous, frame.width, frame.height, x + plan.dx, y + plan.dy)
                    frame.current[y * frame.width + x] = rgb(
                        ((have ushr 16) and 0xFF) + offsets[q * 3],
                        ((have ushr 8) and 0xFF) + offsets[q * 3 + 1],
                        (have and 0xFF) + offsets[q * 3 + 2],
                    )
                }
            }

            MosaicFormat.BLK_INTRA -> {
                val quarters = plan.quarters!!
                for (q in 0 until 4) {
                    val quarter = quarters[q]
                    frame.sheet.data[payloadAt + q * 2] = MosaicFormat.texel(
                        quarter.e0 and 0xFF, (quarter.e0 ushr 8) and 0xFF,
                        quarter.e1 and 0xFF, (quarter.e1 ushr 8) and 0xFF,
                    )
                    frame.sheet.data[payloadAt + q * 2 + 1] = MosaicFormat.texel(
                        quarter.rows[0], quarter.rows[1], quarter.rows[2], quarter.rows[3],
                    )
                }
                reconstructPalette(frame, bx, by, quarters)
            }

            MosaicFormat.BLK_MOTION_RESIDUAL -> {
                frame.sheet.data[commandAt] = MosaicFormat.texel(
                    plan.dx + MosaicFormat.MV_BIAS, plan.dy + MosaicFormat.MV_BIAS, 0, 0,
                )
                val quarters = plan.quarters!!
                for (q in 0 until 4) {
                    val quarter = quarters[q]
                    frame.sheet.data[payloadAt + q * 2] = MosaicFormat.texel(
                        quarter.e0 and 0xFF, (quarter.e0 ushr 8) and 0xFF,
                        quarter.e1 and 0xFF, (quarter.e1 ushr 8) and 0xFF,
                    )
                    frame.sheet.data[payloadAt + q * 2 + 1] = MosaicFormat.texel(
                        quarter.rows[0], quarter.rows[1], quarter.rows[2], quarter.rows[3],
                    )
                }
                val palettes = Array(4) { MosaicBc1.paletteOf(quarters[it].e0, quarters[it].e1) }
                forEachPixel(frame.width, frame.height, bx, by) { x, y ->
                    val q = MosaicReferenceDecoder.quarter(x, y)
                    val at = pickOf(quarters[q].rows, x, y) * 3
                    val palette = palettes[q]
                    val have =
                        sample(frame.previous, frame.width, frame.height, x + plan.dx, y + plan.dy)
                    frame.current[y * frame.width + x] = rgb(
                        ((have ushr 16) and 0xFF) + palette[at] - MosaicFormat.RESIDUAL_BIAS,
                        ((have ushr 8) and 0xFF) + palette[at + 1] - MosaicFormat.RESIDUAL_BIAS,
                        (have and 0xFF) + palette[at + 2] - MosaicFormat.RESIDUAL_BIAS,
                    )
                }
            }

            MosaicFormat.BLK_ENDPOINT_REUSE -> {
                frame.sheet.data[commandAt] =
                    MosaicFormat.withPointer(0, payloadOf[plan.reuseGrid])
                val quarters = plan.quarters!!
                for (q in 0 until 4) {
                    frame.sheet.data[payloadAt + q] = MosaicFormat.texel(
                        quarters[q].rows[0], quarters[q].rows[1],
                        quarters[q].rows[2], quarters[q].rows[3],
                    )
                }
                reconstructPalette(frame, bx, by, quarters)
            }

            else -> {
                val vq = plan.vq!!
                val quarters = Array(4) { q ->
                    val entry = vq[q * 2]
                    val selector = vq[q * 2 + 1]
                    frame.sheet.data[payloadAt + q] = MosaicFormat.texel(
                        entry and 0xFF, (entry ushr 8) and 0xFF,
                        selector and 0xFF, (selector ushr 8) and 0xFF,
                    )
                    val pair = frame.book!!.endpoints[entry]
                    QuarterPlan(
                        MosaicCodebook.pairE0(pair),
                        MosaicCodebook.pairE1(pair),
                        frame.book.rows[selector],
                    )
                }
                reconstructPalette(frame, bx, by, quarters)
            }
        }
    }

    private fun reconstructPalette(
        frame: Frame,
        bx: Int,
        by: Int,
        quarters: Array<QuarterPlan>,
    ) {
        val palettes = Array(4) { MosaicBc1.paletteOf(quarters[it].e0, quarters[it].e1) }
        forEachPixel(frame.width, frame.height, bx, by) { x, y ->
            val q = MosaicReferenceDecoder.quarter(x, y)
            val palette = palettes[q]
            val at = pickOf(quarters[q].rows, x, y) * 3
            frame.current[y * frame.width + x] = rgb(palette[at], palette[at + 1], palette[at + 2])
        }
    }

    private fun pickOf(rows: IntArray, x: Int, y: Int): Int =
        (rows[y % 4] ushr ((x % 4) * 2)) and 3

    private fun planBlock(
        frame: Frame,
        plans: Array<Plan?>,
        block: Int,
        bx: Int,
        by: Int,
        options: Options,
    ): Plan {
        val samples = countPixels(frame.width, frame.height, bx, by)
        if (samples == 0) return Plan(MosaicFormat.BLK_SKIP, 0.0)

        val price = frame.price
        var best: Plan? = null

        fun consider(plan: Plan) {
            val current = best
            if (current == null || plan.cost < current.cost) best = plan
        }

        if (!frame.keyframe) {
            val sseSkip = sse(
                frame.source, frame.previous, frame.width, frame.height, bx, by, 0, 0,
            )
            consider(Plan(MosaicFormat.BLK_SKIP, sseSkip))

            val seedsX = IntArray(5)
            val seedsY = IntArray(5)
            var seeds = 0
            val gx = bx / MosaicFormat.BLOCK
            val gy = by / MosaicFormat.BLOCK
            val grid = gy * frame.blocksX + gx
            seedsX[seeds] = frame.prevMvX[grid]; seedsY[seeds] = frame.prevMvY[grid]; seeds++
            var leftX = 0; var leftY = 0; var topX = 0; var topY = 0
            var rightX = 0; var rightY = 0
            if (gx > 0) {
                leftX = frame.prevMvX[grid - 1]; leftY = frame.prevMvY[grid - 1]
                seedsX[seeds] = leftX; seedsY[seeds] = leftY; seeds++
            }
            if (gy > 0) {
                topX = frame.prevMvX[grid - frame.blocksX]
                topY = frame.prevMvY[grid - frame.blocksX]
                seedsX[seeds] = topX; seedsY[seeds] = topY; seeds++
                if (gx + 1 < frame.blocksX) {
                    rightX = frame.prevMvX[grid - frame.blocksX + 1]
                    rightY = frame.prevMvY[grid - frame.blocksX + 1]
                    seedsX[seeds] = rightX; seedsY[seeds] = rightY; seeds++
                }
            }
            seedsX[seeds] = median(leftX, topX, rightX)
            seedsY[seeds] = median(leftY, topY, rightY)
            seeds++

            val motion = searchMotion(
                frame, bx, by, MosaicFormat.BLOCK,
                seedsX.copyOf(seeds), seedsY.copyOf(seeds), options,
            )

            if (motion.dx != 0 || motion.dy != 0) {
                consider(Plan(MosaicFormat.BLK_MOTION, motion.sse + price, motion.dx, motion.dy))
            }

            val (skipOffsets, skipDeltaError) = deltaPlan(frame, bx, by, 0, 0)
            consider(
                Plan(
                    MosaicFormat.BLK_DELTA,
                    skipDeltaError + MosaicFormat.DELTA_TEXELS * price,
                    offsets = skipOffsets,
                ),
            )

            if (motion.dx != 0 || motion.dy != 0) {
                val (offsets, deltaError) = deltaPlan(frame, bx, by, motion.dx, motion.dy)
                consider(
                    Plan(
                        MosaicFormat.BLK_MOTION_DELTA,
                        deltaError + (MosaicFormat.DELTA_TEXELS + 1) * price,
                        motion.dx, motion.dy,
                        offsets = offsets,
                    ),
                )
            }

            if ((best?.cost ?: Double.MAX_VALUE) >
                (MosaicFormat.RESIDUAL_TEXELS + 1) * price
            ) {
                var residualError = 0.0
                val residualQuarters = Array(4) { q ->
                    val (quarter, error) = residualQuarter(frame, bx, by, q, motion.dx, motion.dy)
                    residualError += error
                    quarter
                }
                consider(
                    Plan(
                        MosaicFormat.BLK_MOTION_RESIDUAL,
                        residualError + (MosaicFormat.RESIDUAL_TEXELS + 1) * price,
                        motion.dx, motion.dy,
                        quarters = residualQuarters,
                    ),
                )
            }
        }

        val cheapest = best?.cost ?: Double.MAX_VALUE
        val intraFloor = if (frame.book != null) {
            MosaicFormat.VQ_TEXELS * price
        } else {
            minOf(MosaicFormat.INTRA_TEXELS, MosaicFormat.REUSE_TEXELS + 1) * price
        }
        if (cheapest <= intraFloor) return best!!

        val scratch = BLOCK_SCRATCH.get()
        val intraQuarters = Array(4) { q ->
            val pixels = gatherQuarter(
                frame.source, frame.width, frame.height,
                bx + (q % 2) * 4, by + (q / 2) * 4, scratch.quarters[q],
            )
            val fit = MosaicBc1.fit(pixels)
            val rows = IntArray(4)
            val error = MosaicBc1.code(pixels, fit.palette, rows)
            Triple(QuarterPlan(fit.e0, fit.e1, rows), error, pixels)
        }
        val intraError = intraQuarters.sumOf { it.second }

        if (frame.book != null) {
            val vq = IntArray(8)
            var vqError = 0.0
            val idealRows = IntArray(4)
            for (q in 0 until 4) {
                val (literal, _, pixels) = intraQuarters[q]
                val entry = frame.book.endpointEntry(
                    MosaicCodebook.pairOf(literal.e0, literal.e1),
                )
                val palette = frame.book.palettes[entry]
                MosaicBc1.code(pixels, palette, idealRows)
                val selector = frame.book.selectorEntry(MosaicCodebook.patternOf(idealRows))
                vq[q * 2] = entry
                vq[q * 2 + 1] = selector
                vqError += rowsError(pixels, palette, frame.book.rows[selector])
            }
            consider(
                Plan(
                    MosaicFormat.BLK_INTRA_VQ,
                    vqError + MosaicFormat.VQ_TEXELS * price,
                    vq = vq,
                ),
            )
        }

        if (cheapest > (MosaicFormat.REUSE_TEXELS + 1) * price) {
            considerReuse(frame, plans, block, bx, by, intraQuarters, price, ::consider)
        }

        consider(
            Plan(
                MosaicFormat.BLK_INTRA,
                intraError + MosaicFormat.INTRA_TEXELS * price,
                quarters = Array(4) { intraQuarters[it].first },
            ),
        )

        return best!!
    }

    private fun considerReuse(
        frame: Frame,
        plans: Array<Plan?>,
        block: Int,
        bx: Int,
        by: Int,
        intraQuarters: Array<Triple<QuarterPlan, Double, IntArray>>,
        price: Double,
        consider: (Plan) -> Unit,
    ) {
        fun candidate(neighbour: Plan?, at: Int) {
            val sourceQuarters = neighbour?.takeIf { it.mode == MosaicFormat.BLK_INTRA }?.quarters
                ?: return
            var error = 0.0
            val quarters = Array(4) { q ->
                val pixels = intraQuarters[q].third
                val palette = MosaicBc1.paletteOf(sourceQuarters[q].e0, sourceQuarters[q].e1)
                val rows = IntArray(4)
                error += MosaicBc1.code(pixels, palette, rows)
                QuarterPlan(sourceQuarters[q].e0, sourceQuarters[q].e1, rows)
            }
            consider(
                Plan(
                    MosaicFormat.BLK_ENDPOINT_REUSE,
                    error + (MosaicFormat.REUSE_TEXELS + 1) * price,
                    quarters = quarters,
                    reuseGrid = at,
                ),
            )
        }

        if (block % MosaicFormat.BLOCKS_PER_SUPER_EDGE > 0) {
            candidate(plans[block - 1], block - 1)
        }
        if (block >= MosaicFormat.BLOCKS_PER_SUPER_EDGE) {
            candidate(
                plans[block - MosaicFormat.BLOCKS_PER_SUPER_EDGE],
                block - MosaicFormat.BLOCKS_PER_SUPER_EDGE,
            )
        }
    }

    private fun rowsError(pixels: IntArray, palette: IntArray, rows: IntArray): Double {
        var total = 0.0
        for (i in 0 until 16) {
            val pick = (rows[i / 4] ushr ((i % 4) * 2)) and 3
            val at = i * 3
            total += MosaicBc1.distance(
                pixels[at], pixels[at + 1], pixels[at + 2], palette, pick,
            )
        }
        return total
    }

    private fun deltaPlan(
        frame: Frame,
        bx: Int,
        by: Int,
        dx: Int,
        dy: Int,
    ): Pair<IntArray, Double> {
        val offsets = IntArray(12)
        var error = 0.0
        for (q in 0 until 4) {
            val qx = bx + (q % 2) * 4
            val qy = by + (q / 2) * 4

            var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
            forEachPixel(frame.width, frame.height, qx, qy, 4) { x, y ->
                val want = frame.source[y * frame.width + x]
                val have = sample(frame.previous, frame.width, frame.height, x + dx, y + dy)
                sumR += ((want ushr 16) and 0xFF) - ((have ushr 16) and 0xFF)
                sumG += ((want ushr 8) and 0xFF) - ((have ushr 8) and 0xFF)
                sumB += (want and 0xFF) - (have and 0xFF)
                count++
            }
            if (count == 0) continue

            val offR = (sumR / count).coerceIn(-MosaicFormat.MV_BIAS, MosaicFormat.MV_MAX)
            val offG = (sumG / count).coerceIn(-MosaicFormat.MV_BIAS, MosaicFormat.MV_MAX)
            val offB = (sumB / count).coerceIn(-MosaicFormat.MV_BIAS, MosaicFormat.MV_MAX)
            offsets[q * 3] = offR
            offsets[q * 3 + 1] = offG
            offsets[q * 3 + 2] = offB

            forEachPixel(frame.width, frame.height, qx, qy, 4) { x, y ->
                val want = frame.source[y * frame.width + x]
                val have = sample(frame.previous, frame.width, frame.height, x + dx, y + dy)
                val dr = ((want ushr 16) and 0xFF) -
                    (((have ushr 16) and 0xFF) + offR).coerceIn(0, 255)
                val dg = ((want ushr 8) and 0xFF) -
                    (((have ushr 8) and 0xFF) + offG).coerceIn(0, 255)
                val db = (want and 0xFF) - ((have and 0xFF) + offB).coerceIn(0, 255)
                error += MosaicBc1.WEIGHT_R * dr * dr +
                    MosaicBc1.WEIGHT_G * dg * dg +
                    MosaicBc1.WEIGHT_B * db * db
            }
        }
        return offsets to error
    }

    private fun residualQuarter(
        frame: Frame,
        bx: Int,
        by: Int,
        q: Int,
        dx: Int,
        dy: Int,
    ): Pair<QuarterPlan, Double> {
        val qx = bx + (q % 2) * 4
        val qy = by + (q / 2) * 4

        val scratch = BLOCK_SCRATCH.get()
        val prediction = scratch.prediction
        val residual = scratch.residual
        val wanted = scratch.wanted
        var at = 0
        for (py in 0 until 4) {
            for (px in 0 until 4) {
                val want = sample(frame.source, frame.width, frame.height, qx + px, qy + py)
                val have =
                    sample(frame.previous, frame.width, frame.height, qx + px + dx, qy + py + dy)
                for (shift in CHANNEL_SHIFTS) {
                    val w = (want ushr shift) and 0xFF
                    val h = (have ushr shift) and 0xFF
                    wanted[at] = w
                    prediction[at] = h
                    residual[at] = (w - h + MosaicFormat.RESIDUAL_BIAS).coerceIn(0, 255)
                    at++
                }
            }
        }

        val fit = MosaicBc1.fit(residual, cluster = false)
        val rows = IntArray(4)
        var error = 0.0
        for (i in 0 until 16) {
            val base = i * 3
            var bestPick = 0
            var bestError = Double.MAX_VALUE
            for (candidate in 0 until 4) {
                var d = 0.0
                for (c in 0 until 3) {
                    val recon =
                        (prediction[base + c] + fit.palette[candidate * 3 + c] -
                            MosaicFormat.RESIDUAL_BIAS).coerceIn(0, 255)
                    val diff = (wanted[base + c] - recon).toDouble()
                    d += CHANNEL_WEIGHTS[c] * diff * diff
                }
                if (d < bestError) {
                    bestError = d
                    bestPick = candidate
                }
            }
            rows[i / 4] = rows[i / 4] or (bestPick shl ((i % 4) * 2))
            error += bestError
        }
        return QuarterPlan(fit.e0, fit.e1, rows) to error
    }

    private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)

    private val CHANNEL_WEIGHTS =
        doubleArrayOf(MosaicBc1.WEIGHT_R, MosaicBc1.WEIGHT_G, MosaicBc1.WEIGHT_B)

    private fun searchMotion(
        frame: Frame,
        bx: Int,
        by: Int,
        size: Int,
        seedsX: IntArray,
        seedsY: IntArray,
        options: Options,
    ): Motion {
        var bestX = 0
        var bestY = 0
        var best = sse(frame.source, frame.previous, frame.width, frame.height, bx, by, 0, 0, size)

        for (i in seedsX.indices) {
            val dx = seedsX[i].coerceIn(-MosaicFormat.MV_MAX, MosaicFormat.MV_MAX)
            val dy = seedsY[i].coerceIn(-MosaicFormat.MV_MAX, MosaicFormat.MV_MAX)
            if (dx == 0 && dy == 0) continue
            if (dx == bestX && dy == bestY) continue
            val error =
                sse(frame.source, frame.previous, frame.width, frame.height, bx, by, dx, dy, size)
            if (error < best) {
                best = error
                bestX = dx
                bestY = dy
            }
        }

        var step = options.motionSearch.coerceIn(1, MosaicFormat.MV_MAX)
        while (step >= 1) {
            var movedX = bestX
            var movedY = bestY
            for (oy in -1..1) {
                for (ox in -1..1) {
                    if (ox == 0 && oy == 0) continue
                    val dx = bestX + ox * step
                    val dy = bestY + oy * step
                    if (dx !in -MosaicFormat.MV_MAX..MosaicFormat.MV_MAX) continue
                    if (dy !in -MosaicFormat.MV_MAX..MosaicFormat.MV_MAX) continue
                    val error = sse(
                        frame.source, frame.previous, frame.width, frame.height, bx, by, dx, dy, size,
                    )
                    if (error < best) {
                        best = error
                        movedX = dx
                        movedY = dy
                    }
                }
            }
            bestX = movedX
            bestY = movedY
            step /= 2
        }

        return Motion(bestX, bestY, best)
    }

    private fun median(a: Int, b: Int, c: Int): Int = maxOf(minOf(a, b), minOf(maxOf(a, b), c))

    private fun gridOf(frame: Frame, bx: Int, by: Int): Int =
        (by / MosaicFormat.BLOCK) * frame.blocksX + (bx / MosaicFormat.BLOCK)

    private fun zeroMotion(frame: Frame, originX: Int, originY: Int) {
        setMotion(frame, originX, originY, 0, 0)
    }

    private fun setMotion(frame: Frame, originX: Int, originY: Int, dx: Int, dy: Int) {
        for (block in 0 until MosaicFormat.BLOCKS_PER_SUPER) {
            val bx = originX + (block % MosaicFormat.BLOCKS_PER_SUPER_EDGE) * MosaicFormat.BLOCK
            val by = originY + (block / MosaicFormat.BLOCKS_PER_SUPER_EDGE) * MosaicFormat.BLOCK
            if (bx >= frame.width || by >= frame.height) continue
            val grid = gridOf(frame, bx, by)
            frame.curMvX[grid] = dx
            frame.curMvY[grid] = dy
        }
    }

    private class BlockScratch {
        val quarters = Array(4) { IntArray(48) }
        val prediction = IntArray(48)
        val residual = IntArray(48)
        val wanted = IntArray(48)
        val rows = IntArray(4)
    }

    private val BLOCK_SCRATCH = ThreadLocal.withInitial { BlockScratch() }

    private fun gatherQuarter(
        source: IntArray,
        width: Int,
        height: Int,
        qx: Int,
        qy: Int,
        pixels: IntArray,
    ): IntArray {
        var at = 0
        for (dy in 0 until 4) {
            for (dx in 0 until 4) {
                val c = sample(source, width, height, qx + dx, qy + dy)
                pixels[at++] = (c ushr 16) and 0xFF
                pixels[at++] = (c ushr 8) and 0xFF
                pixels[at++] = c and 0xFF
            }
        }
        return pixels
    }

    private fun sse(
        source: IntArray,
        reference: IntArray,
        width: Int,
        height: Int,
        bx: Int,
        by: Int,
        dx: Int,
        dy: Int,
        size: Int = MosaicFormat.BLOCK,
    ): Double {
        var total = 0.0
        forEachPixel(width, height, bx, by, size) { x, y ->
            val want = source[y * width + x]
            val have = sample(reference, width, height, x + dx, y + dy)
            val dr = (((want ushr 16) and 0xFF) - ((have ushr 16) and 0xFF)).toDouble()
            val dg = (((want ushr 8) and 0xFF) - ((have ushr 8) and 0xFF)).toDouble()
            val db = ((want and 0xFF) - (have and 0xFF)).toDouble()
            total += MosaicBc1.WEIGHT_R * dr * dr +
                MosaicBc1.WEIGHT_G * dg * dg +
                MosaicBc1.WEIGHT_B * db * db
        }
        return total
    }

    private fun countPixels(width: Int, height: Int, bx: Int, by: Int): Int {
        var count = 0
        forEachPixel(width, height, bx, by) { _, _ -> count++ }
        return count
    }

    private inline fun forEachPixel(
        width: Int,
        height: Int,
        bx: Int,
        by: Int,
        size: Int = MosaicFormat.BLOCK,
        body: (Int, Int) -> Unit,
    ) {
        for (dy in 0 until size) {
            val y = by + dy
            if (y >= height) break
            for (dx in 0 until size) {
                val x = bx + dx
                if (x >= width) break
                body(x, y)
            }
        }
    }

    private fun sample(pixels: IntArray, width: Int, height: Int, x: Int, y: Int): Int =
        pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private class Trainer {
        val endpointCounts = HashMap<Int, Long>()
        val selectorCounts = HashMap<Int, Long>()
        var quarters = 0L

        fun collect(e0: Int, e1: Int, rows: IntArray) {
            quarters++
            endpointCounts.merge(MosaicCodebook.pairOf(e0, e1), 1L, Long::plus)
            selectorCounts.merge(MosaicCodebook.patternOf(rows), 1L, Long::plus)
        }
    }

    private class Sheet(planeTexels: Int) {
        var data = IntArray(minOf(MosaicFormat.MAX_TEXELS, maxOf(planeTexels * 2, 1 shl 16)))
        var top = planeTexels

        fun allocate(count: Int): Int? {
            if (top + count > MosaicFormat.MAX_TEXELS) return null
            if (top + count > data.size) {
                val grown = minOf(MosaicFormat.MAX_TEXELS, maxOf(data.size * 2, top + count))
                data = data.copyOf(grown)
            }
            val at = top
            top += count
            return at
        }
    }
}
