/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.shader.EnvironmentEffect
import dev.shadr.core.stream.StreamGeometry
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamChainTest {

    private fun repo() = File("..").canonicalFile

    private fun build(stream: StreamGeometry?, video: Boolean = false): Pair<File, PackGenerator> {
        val out = createTempDirectory("shadr-stream-pack").toFile()
        val generator = PackGenerator(
            shaderSrc = File(repo(), "shaders"),
            fontDir = File(repo(), "assets/font"),
            environment = EnvironmentEffect.entries.associateWith {
                it == EnvironmentEffect.VIDEO || (video && it == EnvironmentEffect.FROSTED_GLASS)
            },
            stream = stream,
        )
        generator.build(out)
        return out to generator
    }

    private fun chain(out: File): String =
        File(out, "shadr_26_2/assets/minecraft/post_effect/creeper.json").readText()

    @Test
    fun `the probe pass and its target reach the generated chain`() {
        val (out, generator) = build(StreamGeometry.DEFAULT.copy(probe = true, regionX = 320, regionY = 180))
        val json = chain(out)

        assertTrue(json.contains(PostChainBuilder.TARGET_STREAM_OUT), "the probe output target is missing")
        assertTrue(json.contains(PostChainBuilder.PROBE), "the probe pass is missing")
        assertTrue(json.contains("ShadrStreamConfig"), "the probe has no config block")
        assertTrue(json.contains("320.0") && json.contains("180.0"), "the configured region did not reach the chain")
        assertTrue(
            json.lastIndexOf(PostChainBuilder.PROBE) < json.lastIndexOf("minecraft:main"),
            "the probe result is never blitted back to the screen",
        )
        assertTrue(generator.gaps.none { it.feature.contains("probe") }, "${generator.gaps}")
    }

    @Test
    fun `the probe is absent unless it is asked for`() {
        val (plain, _) = build(StreamGeometry.DEFAULT.copy(probe = false))
        val file = File(plain, "shadr_26_2/assets/minecraft/post_effect/creeper.json")
        if (file.isFile) {
            assertFalse(file.readText().contains(PostChainBuilder.PROBE), "the probe leaked into a plain pack")
        }

        val (none, _) = build(null)
        val without = File(none, "shadr_26_2/assets/minecraft/post_effect/creeper.json")
        if (without.isFile) {
            assertFalse(without.readText().contains(PostChainBuilder.PROBE), "the probe leaked with no stream config")
        }
    }

    @Test
    fun `the probe survives alongside the frosted glass chain`() {
        val (out, _) = build(StreamGeometry.DEFAULT.copy(probe = true), video = true)
        val json = chain(out)

        assertTrue(json.contains("shadr_blur_mask"), "the authored blur passes were dropped")
        assertTrue(json.contains(PostChainBuilder.PROBE), "the probe pass was dropped")
        assertTrue(
            json.indexOf("shadr_blur_blit") < json.indexOf(PostChainBuilder.PROBE),
            "the probe must read the screen after the blur has composited",
        )
    }

    @Test
    fun `overlays without the stream include report a gap instead of emitting a broken chain`() {
        val (out, generator) = build(StreamGeometry.DEFAULT.copy(probe = true))
        for (overlay in PackOverlay.entries) {
            val ships = File(repo(), "shaders/overlays/${overlay.sourceDirectory}/include/shadr_stream.glsl").isFile
            val file = File(out, "${overlay.directory}/assets/minecraft/post_effect/creeper.json")
            if (ships) {
                assertTrue(file.isFile, "${overlay.label} ships the include but got no chain")
                assertTrue(file.readText().contains(PostChainBuilder.PROBE), "${overlay.label} chain lacks the probe")
            } else if (file.isFile) {
                assertFalse(
                    file.readText().contains(PostChainBuilder.PROBE),
                    "${overlay.label} cannot run the probe but the chain references it",
                )
            }
        }
        assertTrue(generator.gaps.none { it.missing.any { m -> m.contains("shadr_stream_probe") } }, "${generator.gaps}")
    }

    @Test
    fun `the probe geometry agrees with what the server will write into the slot headers`() {
        val geometry = StreamGeometry.DEFAULT.copy(probe = true, regionX = 640, regionY = 360, slots = 9)
        val (out, _) = build(geometry)
        val json = chain(out)

        assertTrue(json.contains("${geometry.columns}.0"), "columns did not reach the chain")
        assertTrue(json.contains("${geometry.slots}.0"), "slots did not reach the chain")

        val channel = geometry.channel()
        geometry.apply(channel, stream = 0, serial = 1)
        for (slot in 0 until geometry.slots) {
            val words = channel.slot(slot)
            assertEquals(geometry.columns, words[dev.shadr.core.stream.StreamFormat.W_SLOT_COLUMNS])
            assertEquals(geometry.rows, words[dev.shadr.core.stream.StreamFormat.W_SLOT_ROWS])
            assertEquals(geometry.regionX, dev.shadr.core.stream.StreamFormat.readRegionX(words))
            assertEquals(geometry.regionY, dev.shadr.core.stream.StreamFormat.readRegionY(words))
            assertEquals(slot, words[dev.shadr.core.stream.StreamFormat.W_SLOT])
        }
    }

    @Test
    fun `the codec chain decodes into the marker composite`() {
        val (out, generator) = build(dev.shadr.core.stream.StreamPresets.carrier())
        val json = chain(out)

        assertTrue(json.contains(PostChainBuilder.SCODEC_STATE), "the state pass is missing")
        assertTrue(json.contains(PostChainBuilder.SCODEC_DECODE), "the decode pass is missing")
        assertTrue(json.contains(PostChainBuilder.COMPOSITE), "the composite pass is missing")
        assertTrue(json.contains(PostChainBuilder.TARGET_SCODEC_PREV), "no persistent reconstruction target")
        assertTrue(json.contains(PostChainBuilder.TARGET_SCODEC_OUT), "the composite output target is missing")
        assertFalse(json.contains(PostChainBuilder.PROBE), "the probe must not ride along with the codec")
        assertTrue(
            json.indexOf(PostChainBuilder.SCODEC_DECODE) < json.lastIndexOf(PostChainBuilder.COMPOSITE),
            "the composite must run after decode",
        )
        assertTrue(
            json.lastIndexOf(PostChainBuilder.COMPOSITE) < json.lastIndexOf("minecraft:main"),
            "the composite result is never blitted back to the screen",
        )
        assertTrue(generator.gaps.none { it.feature.contains("decode") }, "${generator.gaps}")

        val include = File(out, "shadr_26_2/assets/minecraft/shaders/include/shadr_map.glsl").readText()
        assertTrue(include.contains("#define SHADR_CU 16"), "the codec defines are not in the generated include")
        assertTrue(include.contains("shadr_cu_arena"), "the arena helper is not in the generated include")
        val preset = dev.shadr.core.stream.StreamPresets.CODEC_1080
        assertTrue(include.contains("#define SHADR_FRAME_WIDTH ${preset.frameWidth}"))
        assertTrue(include.contains("#define SHADR_CU_POOL_BASE ${preset.poolBase}"))
    }
}
