/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core.action

import dev.shadr.core.PlayerId
import dev.shadr.core.page.ActionSpec

/**
 * The verbs a page may invoke on click.
 */
enum class ActionVerb(val id: String) {
    /** Run as the clicking player. */
    COMMAND("command"),

    /** Run from console. Requires the element to carry `permission:`. */
    CONSOLE("console"),

    MESSAGE("message"),
    SOUND("sound"),

    /** Pause the remaining actions for N ticks. */
    DELAY("delay"),

    CLOSE("close"),

    /** Close this page and open another. */
    REDIRECT("redirect"),

    TELEPORT("teleport"),

    /** Open a page without closing the current one (a popup). */
    OPEN("open");

    companion object {
        fun parse(raw: String?): ActionVerb? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

data class Action(val verb: ActionVerb, val argument: String) {
    companion object {
        fun from(spec: ActionSpec): Action? =
            ActionVerb.parse(spec.verb)?.let { Action(it, spec.argument) }

        fun from(specs: List<ActionSpec>): List<Action> = specs.mapNotNull { from(it) }
    }
}

/** Abstraction for platform-specific action handling. */
interface ActionHost {
    fun runAsPlayer(player: PlayerId, command: String)
    fun runAsConsole(command: String)
    fun message(player: PlayerId, text: String)
    fun playSound(player: PlayerId, sound: String, volume: Double = 1.0)
    fun closePage(player: PlayerId)
    fun openPage(player: PlayerId, page: String, replacing: Boolean)
    fun teleport(player: PlayerId, destination: String)
    fun hasPermission(player: PlayerId, permission: String): Boolean

    /** Schedule [task] [ticks] from now. */
    fun scheduleTicks(ticks: Long, task: () -> Unit)

    /** Resolve `%placeholder%` */
    fun resolvePlaceholders(player: PlayerId, text: String): String = text
}

/**
 * Executes an action list
 */
class ActionRunner(private val host: ActionHost) {

    fun run(player: PlayerId, actions: List<Action>, permission: String? = null) {
        if (permission != null && !host.hasPermission(player, permission)) return
        step(player, actions, 0, permission)
    }

    private fun step(player: PlayerId, actions: List<Action>, index: Int, permission: String?) {
        if (index >= actions.size) return
        val action = actions[index]
        val argument = host.resolvePlaceholders(player, action.argument)

        when (action.verb) {
            ActionVerb.DELAY -> {
                val ticks = argument.trim().toLongOrNull()?.coerceIn(0, MAX_DELAY_TICKS) ?: 0
                host.scheduleTicks(ticks) { step(player, actions, index + 1, permission) }
                return
            }
            ActionVerb.COMMAND -> host.runAsPlayer(player, argument.removePrefix("/"))
            // Console actions require explicit permission due to security concerns.
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
        /** One minute. */
        const val MAX_DELAY_TICKS = 1200L
    }
}
