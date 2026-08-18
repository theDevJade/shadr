/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.stream

data class StreamGeometry(
    val slots: Int,
    val regionX: Int,
    val regionY: Int,
    val mapIdBase: Int,
    val fps: Double,
    val probe: Boolean,
    val probeMode: Int = 0,
    val gridColumns: Int = 0,
    val codec: Boolean = false,
) {
    init {
        require(slots in 1..StreamFormat.MAX_SLOTS) { "slots must be 1..${StreamFormat.MAX_SLOTS}, got $slots" }
        require(regionX in 0 until StreamFormat.DESIGN_WIDTH) { "regionX $regionX is outside the design space" }
        require(regionY in 0 until StreamFormat.DESIGN_HEIGHT) { "regionY $regionY is outside the design space" }
        require(mapIdBase >= 0) { "mapIdBase $mapIdBase is negative" }
        require(mapIdBase + slots <= Int.MAX_VALUE) { "map id range overflows" }
        require(fps > 0.0 && fps <= 60.0) { "fps $fps is out of range" }
        require(probeMode in 0..5) { "probeMode $probeMode is not a known probe display" }
    }

    val columns: Int get() = if (gridColumns > 0) gridColumns else StreamFormat.slotColumns(slots)

    val rows: Int get() = (slots + columns - 1) / columns

    val regionWidth: Int get() = columns * MapPalette.MAP_EDGE

    val regionHeight: Int get() = rows * MapPalette.MAP_EDGE

    val words: Int get() = slots * MapPalette.MAP_WORDS

    val payloadBytesPerFrame: Int get() = words

    fun channel(): StreamChannel = StreamChannel(slots, mapIdBase, columns)

    fun apply(channel: StreamChannel, stream: Int, serial: Int) {
        require(channel.slots == slots) { "channel has ${channel.slots} slots, geometry wants $slots" }
        for (slot in 0 until slots) channel.header(slot, stream, serial, regionX, regionY)
    }

    companion object {
        val DEFAULT = StreamGeometry(
            slots = 16,
            regionX = 0,
            regionY = 0,
            mapIdBase = 32_000,
            fps = 20.0,
            probe = false,
        )

        val BROADCAST = DEFAULT.copy(slots = 4, fps = 10.0)
    }
}
