/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.action

import dev.shadr.core.PlayerId
import dev.shadr.core.page.ActionSpec

enum class ActionVerb(val id: String) {
    COMMAND("command"),

    CONSOLE("console"),

    MESSAGE("message"),
    SOUND("sound"),

    DELAY("delay"),

    CLOSE("close"),

    REDIRECT("redirect"),

    TELEPORT("teleport"),

    OPEN("open");

    companion object {
        fun parse(raw: String?): ActionVerb? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

data class Action(val verb: ActionVerb?, val argument: String, val custom: String = "") {
    companion object {
        fun from(spec: ActionSpec): Action? {
            val builtin = ActionVerb.parse(spec.verb)
            if (builtin != null) return Action(builtin, spec.argument)
            val name = spec.verb.trim().lowercase()
            return if (name.isEmpty()) null else Action(null, spec.argument, name)
        }

        fun from(specs: List<ActionSpec>): List<Action> = specs.mapNotNull { from(it) }
    }
}

interface ActionHost {
    fun runAsPlayer(player: PlayerId, command: String)
    fun runAsConsole(command: String)
    fun message(player: PlayerId, text: String)
    fun playSound(player: PlayerId, sound: String, volume: Double = 1.0)

    fun stopSound(player: PlayerId, sound: String) = Unit
    fun closePage(player: PlayerId)
    fun openPage(player: PlayerId, page: String, replacing: Boolean)
    fun teleport(player: PlayerId, destination: String)
    fun hasPermission(player: PlayerId, permission: String): Boolean

    fun scheduleTicks(ticks: Long, task: () -> Unit)

    fun resolvePlaceholders(player: PlayerId, text: String): String = text
}

class ActionRunner(private val host: ActionHost) {
    private val custom = linkedMapOf<String, dev.shadr.core.api.ActionHandler>()

    fun register(verb: String, handler: dev.shadr.core.api.ActionHandler): Boolean {
        val name = verb.trim().lowercase()
        if (name.isEmpty() || ActionVerb.parse(name) != null || custom.containsKey(name)) return false
        custom[name] = handler
        return true
    }

    fun customVerbs(): Set<String> = custom.keys.toSet()

    fun run(player: PlayerId, actions: List<Action>, permission: String? = null) {
        if (permission != null && !host.hasPermission(player, permission)) return
        step(player, actions, 0, permission)
    }

    private fun step(player: PlayerId, actions: List<Action>, index: Int, permission: String?) {
        if (index >= actions.size) return
        val action = actions[index]
        val argument = host.resolvePlaceholders(player, action.argument)

        if (action.verb == null) {
            custom[action.custom]?.run(player, argument)
            step(player, actions, index + 1, permission)
            return
        }

        when (action.verb) {
            ActionVerb.DELAY -> {
                val ticks = argument.trim().toLongOrNull()?.coerceIn(0, MAX_DELAY_TICKS) ?: 0
                host.scheduleTicks(ticks) { step(player, actions, index + 1, permission) }
                return
            }
            ActionVerb.COMMAND -> host.runAsPlayer(player, argument.removePrefix("/"))
            ActionVerb.CONSOLE -> if (permission != null) host.runAsConsole(argument.removePrefix("/"))
            ActionVerb.MESSAGE -> host.message(player, argument)
            ActionVerb.SOUND -> {
                val parts = argument.split(' ', limit = 2)
                host.playSound(player, parts[0], parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0)
            }
            ActionVerb.CLOSE -> host.closePage(player)
            ActionVerb.REDIRECT -> host.openPage(player, argument, replacing = true)
            ActionVerb.OPEN -> host.openPage(player, argument, replacing = false)
            ActionVerb.TELEPORT -> host.teleport(player, argument)
        }
        step(player, actions, index + 1, permission)
    }

    private companion object {
        const val MAX_DELAY_TICKS = 1200L
    }
}
