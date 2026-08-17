package dev.shadr.integrations.skript

import ch.njol.skript.lang.Expression
import ch.njol.skript.lang.SkriptParser.ParseResult
import ch.njol.skript.lang.util.SimpleExpression
import ch.njol.util.Kleenean
import org.bukkit.event.Event

class ExprShaders : SimpleExpression<String>() {

    override fun init(
        expressions: Array<out Expression<*>>,
        matchedPattern: Int,
        isDelayed: Kleenean,
        parseResult: ParseResult,
    ): Boolean = true

    override fun get(event: Event): Array<String>? =
        Shadr.shaders?.shaders()?.map { it.id }?.toTypedArray()

    override fun isSingle(): Boolean = false

    override fun getReturnType(): Class<out String> = String::class.java

    override fun toString(event: Event?, debug: Boolean): String = "installed shadr shaders"
}

class ExprPlacedShaders : SimpleExpression<String>() {

    override fun init(
        expressions: Array<out Expression<*>>,
        matchedPattern: Int,
        isDelayed: Kleenean,
        parseResult: ParseResult,
    ): Boolean = true

    override fun get(event: Event): Array<String>? =
        Shadr.shaders?.placed()?.toTypedArray()

    override fun isSingle(): Boolean = false

    override fun getReturnType(): Class<out String> = String::class.java

    override fun toString(event: Event?, debug: Boolean): String = "placed shadr shader handles"
}
