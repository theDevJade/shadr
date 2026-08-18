/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.config.ShadrConfig
import dev.shadr.core.config.StreamProfile
import dev.shadr.core.stream.StreamGeometry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamConfigTest {

    private fun load(yaml: String): ShadrConfig {
        val file = File.createTempFile("shadr-stream-config", ".yml")
        file.writeText(yaml)
        return ShadrConfig.load(file).also { file.delete() }
    }

    @Test
    fun `the shipped config exposes every stream key the loader reads`() {
        val shipped = File("../platform-paper/src/main/resources/config.yml")
        check(shipped.isFile) { "the shipped config.yml is missing" }
        val config = ShadrConfig.load(shipped)

        assertFalse(config.stream.enabled, "streaming must ship off")
        assertEquals(StreamProfile.QUALITY, config.stream.profile)
        assertEquals(32_000, config.stream.mapIdBase, "map-id-base did not parse")
        assertEquals(0, config.stream.slots)
        assertEquals(0, config.stream.regionX)
        assertEquals(0, config.stream.regionY)
        assertEquals(0.0, config.stream.fps)
        assertFalse(config.stream.probe, "the probe must ship off")
    }

    @Test
    fun `defaults fall through to the profile`() {
        val config = load("stream:\n  enabled: true\n")
        val geometry = config.stream.geometry()
        assertEquals(StreamGeometry.DEFAULT.slots, geometry.slots)
        assertEquals(StreamGeometry.DEFAULT.fps, geometry.fps)
    }

    @Test
    fun `the broadcast profile trades slots for reach`() {
        val config = load("stream:\n  enabled: true\n  profile: broadcast\n")
        val geometry = config.stream.geometry()
        assertEquals(StreamGeometry.BROADCAST.slots, geometry.slots)
        assertTrue(geometry.slots < StreamGeometry.DEFAULT.slots)
        assertTrue(geometry.payloadBytesPerFrame < StreamGeometry.DEFAULT.payloadBytesPerFrame)
    }

    @Test
    fun `explicit values win over the profile`() {
        val config = load(
            """
            stream:
              enabled: true
              profile: broadcast
              slots: 9
              region-x: 480
              region-y: 270
              map-id-base: 40000
              fps: 30
              probe: true
            """.trimIndent(),
        )
        val geometry = config.stream.geometry()
        assertEquals(9, geometry.slots)
        assertEquals(3, geometry.columns)
        assertEquals(480, geometry.regionX)
        assertEquals(270, geometry.regionY)
        assertEquals(40_000, geometry.mapIdBase)
        assertEquals(30.0, geometry.fps)
        assertTrue(geometry.probe)
    }

    @Test
    fun `an unknown profile falls back instead of failing to boot`() {
        val config = load("stream:\n  enabled: true\n  profile: nonsense\n")
        assertEquals(StreamProfile.QUALITY, config.stream.profile)
    }

    @Test
    fun `a missing stream block leaves streaming off`() {
        val config = load("rendering:\n  packet-entities: true\n")
        assertFalse(config.stream.enabled)
    }
}
