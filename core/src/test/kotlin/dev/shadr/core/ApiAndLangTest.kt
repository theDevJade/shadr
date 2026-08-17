/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.action.Action
import dev.shadr.core.action.ActionHost
import dev.shadr.core.action.ActionRunner
import dev.shadr.core.config.HostingMode
import dev.shadr.core.config.Lang
import dev.shadr.core.config.ShadrConfig
import dev.shadr.core.page.ActionSpec
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiAndLangTest {
    private class Host : ActionHost {
        val messages = mutableListOf<String>()
        override fun runAsPlayer(player: PlayerId, command: String) = Unit
        override fun runAsConsole(command: String) = Unit
        override fun message(player: PlayerId, text: String) { messages += text }
        override fun playSound(player: PlayerId, sound: String, volume: Double) = Unit
        override fun closePage(player: PlayerId) = Unit
        override fun openPage(player: PlayerId, page: String, replacing: Boolean) = Unit
        override fun teleport(player: PlayerId, destination: String) = Unit
        override fun hasPermission(player: PlayerId, permission: String) = true
        override fun scheduleTicks(ticks: Long, task: () -> Unit) = task()
    }

    private val someone = PlayerId("00000000-0000-0000-0000-000000000000")

    @Test
    fun `a plugin verb runs, and does not stop the actions after it`() {
        val host = Host()
        val runner = ActionRunner(host)
        val seen = mutableListOf<String>()

        assertTrue(runner.register("economy") { _, argument -> seen += argument })

        runner.run(
            someone,
            Action.from(
                listOf(
                    ActionSpec("economy", "give 100"),
                    ActionSpec("message", "done"),
                ),
            ),
        )

        assertEquals(listOf("give 100"), seen)
        assertEquals(listOf("done"), host.messages, "a custom verb swallowed the rest of the chain")
    }

    @Test
    fun `a verb cannot shadow a built-in or be registered twice`() {
        val runner = ActionRunner(Host())
        assertFalse(runner.register("close") { _, _ -> }, "a built-in was overridden")
        assertTrue(runner.register("mine") { _, _ -> })
        assertFalse(runner.register("mine") { _, _ -> }, "registered twice")
    }

    @Test
    fun `an unknown verb with no handler is skipped rather than dropping the chain`() {
        val host = Host()
        ActionRunner(host).run(
            someone,
            Action.from(listOf(ActionSpec("nobody_registered_this", "x"), ActionSpec("message", "after"))),
        )
        assertEquals(listOf("after"), host.messages)
    }

    @Test
    fun `merge-only never sends a pack`() {
        assertFalse(HostingMode.MERGE_ONLY.sends)
        assertFalse(HostingMode.EXTERNAL_PACK.sends)
        assertTrue(HostingMode.DEFAULT_PACK.sends)
        assertTrue(HostingMode.SELF_HOST.sends)
        assertTrue(HostingMode.EXTERNAL_HOST.sends)
    }

    @Test
    fun `merge-only is read from the config, with its target`() {
        val dir = createTempDirectory("shadr-config").toFile()
        val file = File(dir, "config.yml")
        file.writeText(
            """
            resource-pack:
              hosting:
                merge-only:
                  enabled: true
                  merge-into: '../other/pack'
            """.trimIndent(),
        )

        val config = ShadrConfig.load(file)
        assertEquals(HostingMode.MERGE_ONLY, config.pack.hosting)
        assertEquals("../other/pack", config.pack.mergeInto)
    }

    @Test
    fun `a translated key wins, a missing one falls back, and placeholders fill`() {
        val lang = Lang(mapOf("reloaded" to "rechargé {pages} page(s)"))

        assertEquals("rechargé 3 page(s)", lang["reloaded", "pages" to 3])
        assertEquals("players only", lang["players-only"], "a missing key should fall back")
        assertEquals("you do not have shadr.editor", lang["no-permission", "permission" to "shadr.editor"])
    }

    @Test
    fun `the shipped language file parses back into every default`() {
        val dir = createTempDirectory("shadr-lang").toFile()
        val file = File(dir, "lang.yml")
        file.writeText(Lang.defaultsYaml())

        val lang = Lang.load(file)
        for ((key, value) in Lang.DEFAULTS) {
            assertEquals(value, lang[key], "'$key' did not survive a round-trip")
        }
    }
}
