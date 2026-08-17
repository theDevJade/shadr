/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.shader.EnvironmentEffect
import dev.shadr.core.shader.EnvironmentSource
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnvironmentSourceTest {
    private fun root(): File {
        val dir = createTempDirectory("shadr-envsrc").toFile()
        File(dir, "overlays/mc_26_2/core").mkdirs()
        File(dir, "overlays/mc_26_2/core/sky.fsh").writeText("// shipped\n")
        return dir
    }

    @Test
    fun `reading falls back to what shadr ships`() {
        val source = EnvironmentSource(root())
        assertEquals("// shipped\n", source.read("core/sky.fsh"))
        assertTrue(!source.isCustomised("core/sky.fsh"))
    }

    @Test
    fun `an override wins, and the shipped file is untouched`() {
        val dir = root()
        val source = EnvironmentSource(dir)
        source.write("core/sky.fsh", "// mine\n")

        assertEquals("// mine\n", source.read("core/sky.fsh"))
        assertTrue(source.isCustomised("core/sky.fsh"))
        assertEquals(
            "// shipped\n",
            File(dir, "overlays/mc_26_2/core/sky.fsh").readText(),
            "editing an override modified the shipped program",
        )
    }

    @Test
    fun `an override lands in the directory the pack build reads`() {
        val dir = root()
        EnvironmentSource(dir).write("core/sky.fsh", "// mine\n")
        assertTrue(
            File(dir, "custom/all/core/sky.fsh").isFile,
            "the override is not where PackGenerator copies custom files from",
        )
    }

    @Test
    fun `reverting drops the override and reveals the shipped file again`() {
        val dir = root()
        val source = EnvironmentSource(dir)
        source.write("core/sky.fsh", "// mine\n")

        assertTrue(source.revert("core/sky.fsh"))
        assertTrue(!source.isCustomised("core/sky.fsh"))
        assertEquals("// shipped\n", source.read("core/sky.fsh"))
    }

    @Test
    fun `a program with neither an override nor a shipped file reads as absent`() {
        assertEquals(null, EnvironmentSource(root()).read("core/nonexistent.fsh"))
    }

    @Test
    fun `the offered programs are exactly the effects' own files`() {
        val offered = EnvironmentSource(root()).programs()
        assertEquals(EnvironmentEffect.entries.flatMap { it.programs }.toSet(), offered.toSet())
        assertTrue(offered.none { it.contains("..") }, "a traversable path is offered: $offered")
    }

    @Test
    fun `every declared program is shipped by the repo`() {
        val source = EnvironmentSource(File("../shaders").canonicalFile)
        for (effect in EnvironmentEffect.entries) {
            for (path in effect.programs) {
                assertNotNull(source.read(path), "${effect.id} declares $path but nothing ships it")
            }
        }
    }
}
