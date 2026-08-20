/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.shader.EnvironmentEffect
import dev.shadr.core.shader.EnvironmentSettings
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnvironmentSettingsTest {
    private fun file() = File(createTempDirectory("shadr-env").toFile(), "environment.properties")

    @Test
    fun `everything is off until it is asked for`() {
        val settings = EnvironmentSettings(file())
        for (effect in EnvironmentEffect.entries) {
            assertTrue(!settings.isEnabled(effect), "${effect.id} was on by default")
        }
    }

    @Test
    fun `a toggle survives a reload`() {
        val f = file()
        EnvironmentSettings(f).set(EnvironmentEffect.CLOUDS, true)

        val reopened = EnvironmentSettings(f)
        assertTrue(reopened.isEnabled(EnvironmentEffect.CLOUDS))
        assertTrue(!reopened.isEnabled(EnvironmentEffect.SKY), "an unrelated effect changed")
    }

    @Test
    fun `turning one off again is persisted, not just forgotten`() {
        val f = file()
        val settings = EnvironmentSettings(f)
        settings.set(EnvironmentEffect.SKY, true)
        settings.set(EnvironmentEffect.SKY, false)
        assertTrue(!EnvironmentSettings(f).isEnabled(EnvironmentEffect.SKY))
    }

    @Test
    fun `an unknown id in the file is ignored`() {
        val f = file()
        f.parentFile.mkdirs()
        f.writeText("# comment\nsky=true\nnonsense=true\ngarbage\n")

        val settings = EnvironmentSettings(f)
        assertTrue(settings.isEnabled(EnvironmentEffect.SKY))
        assertTrue(!settings.isEnabled(EnvironmentEffect.CLOUDS))
    }

    @Test
    fun `the ids are the ones the pack and the editor agree on`() {
        assertEquals(
            listOf(
                "sky", "clouds", "celestials", "blur", "video",
                "grading", "bloom", "godrays", "ssao", "ssr", "water", "fog",
            ),
            EnvironmentEffect.entries.map { it.id },
        )
        for (effect in EnvironmentEffect.entries) {
            assertTrue(effect.programs.isNotEmpty(), "${effect.id} names no programs to remove")
            assertTrue(effect.description.isNotBlank(), "${effect.id} has nothing to show a user")
        }
    }

    @Test
    fun `a world effect hosts on the transparency chain and nothing else does`() {
        for (effect in EnvironmentEffect.entries) {
            val world = effect.host == dev.shadr.core.shader.PostChains.WORLD_HOST_PATH
            assertEquals(world, effect.isWorldEffect, "${effect.id} disagrees about its host")
            assertTrue(
                !world || effect.programs.contains(dev.shadr.core.shader.PostChains.WORLD_HOST_PATH),
                "${effect.id} rides the world chain but never names it, so it is never removed",
            )
            assertTrue(
                world == effect.params.isNotEmpty(),
                "${effect.id} should expose settings only if it is a world effect",
            )
        }
    }

    @Test
    fun `a parameter round trips through the file and clamps to its range`() {
        val f = File.createTempFile("shadr-env", ".properties").also { it.deleteOnExit() }
        val settings = EnvironmentSettings(f)
        val effect = EnvironmentEffect.GRADING
        val exposure = effect.params.first { it.key == "exposure" }

        assertTrue(settings.setParam(effect, "exposure", 1.25))
        assertTrue(!settings.setParam(effect, "nonsense", 1.0), "an unknown key was accepted")
        assertEquals(1.25, EnvironmentSettings(f).paramsOf(effect)["exposure"])

        settings.setParam(effect, "exposure", exposure.max + 1000.0)
        assertEquals(exposure.max, EnvironmentSettings(f).paramsOf(effect)["exposure"])

        assertEquals(effect.params.size, settings.paramsOf(effect).size)
    }

    @Test
    fun `a preset only writes keys the effect declares`() {
        val f = File.createTempFile("shadr-env", ".properties").also { it.deleteOnExit() }
        val settings = EnvironmentSettings(f)
        val preset = dev.shadr.core.shader.GradingPresets.BUILT_IN.getValue("noir")

        val applied = settings.applyPreset(EnvironmentEffect.GRADING, preset + ("bogus" to 1.0))
        assertEquals(preset.size, applied)
        assertEquals(0.0, settings.paramsOf(EnvironmentEffect.GRADING)["saturation"])
    }

    @Test
    fun `every shipped grading preset names real parameters`() {
        val keys = EnvironmentEffect.GRADING.params.map { it.key }.toSet()
        val presets = dev.shadr.core.shader.GradingPresets.load(File("../shaders"))
        assertTrue(presets.keys.containsAll(dev.shadr.core.shader.GradingPresets.BUILT_IN.keys))
        for ((name, values) in presets) {
            assertTrue(values.isNotEmpty(), "preset $name sets nothing")
            for (key in values.keys) {
                assertTrue(key in keys, "preset $name sets unknown parameter $key")
            }
        }
    }
}
