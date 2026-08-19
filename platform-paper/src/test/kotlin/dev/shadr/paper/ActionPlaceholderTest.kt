/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import dev.shadr.core.PlayerId
import dev.shadr.core.action.Action
import dev.shadr.core.action.ActionHost
import dev.shadr.core.action.ActionRunner
import dev.shadr.core.action.ActionVerb
import dev.shadr.core.page.PlaceholderResolver
import dev.shadr.core.page.PlaceholderScanner
import java.lang.reflect.Proxy
import org.bukkit.plugin.Plugin
import kotlin.test.Test
import kotlin.test.assertEquals

class ActionPlaceholderTest {

    private val steve = PlayerId("00000000-0000-0000-0000-000000000000")

    @Test
    fun `PaperActionHost resolves placeholders in action arguments`() {
        val host = PaperActionHost(
            plugin = unusedPlugin(),
            openPageHandler = { _, _, _ -> },
            closePageHandler = { },
            placeholders = { PlaceholderResolver { _, name -> if (name == "shadr_player") "Steve" else null } },
        )

        assertEquals("hello Steve", host.resolvePlaceholders(steve, "hello %shadr_player%"))
    }

    @Test
    fun `PaperActionHost leaves an unresolved placeholder as written`() {
        val host = PaperActionHost(
            plugin = unusedPlugin(),
            openPageHandler = { _, _, _ -> },
            closePageHandler = { },
            placeholders = { PlaceholderResolver.NONE },
        )

        assertEquals("hello %nobody%", host.resolvePlaceholders(steve, "hello %nobody%"))
    }

    @Test
    fun `the runner resolves each argument before the verb runs`() {
        val host = RecordingHost { _, name -> if (name == "shadr_player") "Steve" else null }

        ActionRunner(host).run(
            steve,
            listOf(
                Action(ActionVerb.MESSAGE, "hello %shadr_player%"),
                Action(ActionVerb.MESSAGE, "bye %shadr_player%"),
            ),
        )

        assertEquals(listOf("hello Steve", "bye Steve"), host.messages)
    }

    private fun unusedPlugin(): Plugin =
        Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java),
        ) { _, method, _ ->
            error("PaperActionHost.resolvePlaceholders must not touch the plugin, but called ${method.name}")
        } as Plugin

    private class RecordingHost(private val resolver: PlaceholderResolver) : ActionHost {
        val messages = mutableListOf<String>()

        override fun resolvePlaceholders(player: PlayerId, text: String): String =
            PlaceholderScanner.apply(text, player, resolver)

        override fun message(player: PlayerId, text: String) {
            messages += text
        }

        override fun runAsPlayer(player: PlayerId, command: String) = Unit
        override fun runAsConsole(command: String) = Unit
        override fun playSound(player: PlayerId, sound: String, volume: Double) = Unit
        override fun closePage(player: PlayerId) = Unit
        override fun openPage(player: PlayerId, page: String, replacing: Boolean) = Unit
        override fun teleport(player: PlayerId, destination: String) = Unit
        override fun hasPermission(player: PlayerId, permission: String) = true
        override fun scheduleTicks(ticks: Long, task: () -> Unit) = task()
    }
}
