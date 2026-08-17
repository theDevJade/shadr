/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
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
    fun `an unknown id in the file is ignored rather than fatal`() {
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
            listOf("sky", "clouds", "celestials", "blur"),
            EnvironmentEffect.entries.map { it.id },
        )
        for (effect in EnvironmentEffect.entries) {
            assertTrue(effect.programs.isNotEmpty(), "${effect.id} names no programs to remove")
            assertTrue(effect.description.isNotBlank(), "${effect.id} has nothing to show a user")
        }
    }
}
