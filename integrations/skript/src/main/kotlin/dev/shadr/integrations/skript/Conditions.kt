package dev.shadr.integrations.skript

import ch.njol.skript.lang.Condition
import ch.njol.skript.lang.Expression
import ch.njol.skript.lang.SkriptParser.ParseResult
import ch.njol.util.Kleenean
import org.bukkit.event.Event

class CondShaderExists : Condition() {

    private lateinit var shader: Expression<String>

    override fun init(
        expressions: Array<out Expression<*>>,
        matchedPattern: Int,
        isDelayed: Kleenean,
        parseResult: ParseResult,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        shader = expressions[0] as Expression<String>
        setNegated(matchedPattern == 1)
        return true
    }

    override fun check(event: Event): Boolean {
        val api = Shadr.shaders ?: return isNegated()
        val id = shader.getSingle(event) ?: return isNegated()
        return api.exists(id) != isNegated()
    }

    override fun toString(event: Event?, debug: Boolean): String =
        "shadr shader ${shader.toString(event, debug)} ${if (isNegated()) "does not exist" else "exists"}"
}
