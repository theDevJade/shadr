/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.stream

class StreamPlayer(
    val geometry: StreamCodec.Geometry,
    val channel: StreamChannel,
    private val carrier: StreamGeometry,
    private val options: StreamCodec.Options,
) {

    init {
        require(channel.slots >= geometry.slots) {
            "the channel has ${channel.slots} slots, the codec needs ${geometry.slots}"
        }
    }

    private val reconstruction = IntArray(geometry.frameWidth * geometry.frameHeight)

    private val workspace = StreamCodec.Workspace(geometry)

    private val ungated = StreamCodec.Options(
        options.skipThreshold, options.mcThreshold, options.searchRange,
        options.refreshPerFrame, options.intraGain, options.residualBias,
        options.payloadBudget, false,
    )

    private val warmupFrames =
        if (options.refreshPerFrame > 0) geometry.cus / options.refreshPerFrame + 1 else 0

    private var frame = 0

    var lastStats: StreamCodec.Stats? = null
        private set

    fun push(pixels: IntArray, sink: StreamSink): Int {
        lastStats = StreamCodec.encode(
            channel, pixels, reconstruction, geometry,
            if (frame < warmupFrames) ungated else options, frame, workspace,
        )
        val serial = frame % StreamFormat.SERIAL_MODULUS
        for (slot in 0 until channel.slots) {
            channel.header(slot, 0, if (slot == 0) serial else 0, carrier.regionX, carrier.regionY)
        }
        var bytes = 0
        for (slot in 1 until channel.slots) bytes += channel.flushSlot(slot, sink)
        bytes += channel.flushSlot(0, sink)
        frame++
        return bytes
    }
}
