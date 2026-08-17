package dev.shadr.integrations.skript

import ch.njol.skript.lang.Effect
import ch.njol.skript.lang.Expression
import ch.njol.skript.lang.SkriptParser.ParseResult
import ch.njol.util.Kleenean
import dev.shadr.core.Rgb
import dev.shadr.core.spi.WorldAnchor
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event

class EffOpenPage : Effect() {

    private lateinit var page: Expression<String>
    private lateinit var players: Expression<Player>
    private var replacing = true

    override fun init(
        expressions: Array<out Expression<*>>,
        matchedPattern: Int,
        isDelayed: Kleenean,
        parseResult: ParseResult,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        page = expressions[0] as Expression<String>
        @Suppress("UNCHECKED_CAST")
        players = expressions[1] as Expression<Player>
        replacing = matchedPattern == 0
        return true
    }

    override fun execute(event: Event) {
        val shadr = Shadr.plugin ?: return
        val name = page.getSingle(event) ?: return
        for (player in players.getArray(event)) {
            shadr.openPage(Shadr.id(player), name, replacing)
        }
    }

    override fun toString(event: Event?, debug: Boolean): String =
        "open shadr ${if (replacing) "page" else "popup"} ${page.toString(event, debug)} for ${players.toString(event, debug)}"
}

class EffClosePage : Effect() {

    private lateinit var players: Expression<Player>

    override fun init(
        expressions: Array<out Expression<*>>,
        matchedPattern: Int,
        isDelayed: Kleenean,
        parseResult: ParseResult,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        players = expressions[0] as Expression<Player>
        return true
    }

    override fun execute(event: Event) {
        val shadr = Shadr.plugin ?: return
        for (player in players.getArray(event)) {
            shadr.closePage(Shadr.id(player))
        }
    }

    override fun toString(event: Event?, debug: Boolean): String =
        "close the shadr page for ${players.toString(event, debug)}"
}

class EffReloadShadr : Effect() {

    override fun init(
        expressions: Array<out Expression<*>>,
        matchedPattern: Int,
        isDelayed: Kleenean,
        parseResult: ParseResult,
    ): Boolean = true

    override fun execute(event: Event) {
        Shadr.plugin?.reload()
    }

    override fun toString(event: Event?, debug: Boolean): String = "reload shadr"
}

class EffSpawnShader : Effect() {

    private lateinit var shader: Expression<String>
    private lateinit var handle: Expression<String>
    private lateinit var location: Expression<Location>
    private var scale: Expression<Number>? = null
    private var color: Expression<String>? = null

    override fun init(
        expressions: Array<out Expression<*>>,
        matchedPattern: Int,
        isDelayed: Kleenean,
        parseResult: ParseResult,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        shader = expressions[0] as Expression<String>
        @Suppress("UNCHECKED_CAST")
        handle = expressions[1] as Expression<String>
        @Suppress("UNCHECKED_CAST")
        location = expressions[2] as Expression<Location>
        @Suppress("UNCHECKED_CAST")
        scale = expressions.getOrNull(3) as? Expression<Number>
        @Suppress("UNCHECKED_CAST")
        color = expressions.getOrNull(4) as? Expression<String>
        return true
    }

    override fun execute(event: Event) {
        val api = Shadr.shaders ?: return
        val id = shader.getSingle(event) ?: return
        val name = handle.getSingle(event) ?: return
        val at = location.getSingle(event) ?: return
        val world = at.world ?: return

        api.spawn(
            handle = name,
            shader = id,
            at = WorldAnchor(world.name, at.x, at.y, at.z),
            scale = scale?.getSingle(event)?.toDouble() ?: 1.0,
            color = Rgb.parse(color?.getSingle(event)) ?: Rgb.WHITE,
        )
    }

    override fun toString(event: Event?, debug: Boolean): String =
        "spawn shadr shader ${shader.toString(event, debug)} named ${handle.toString(event, debug)} " +
            "at ${location.toString(event, debug)}"
}

class EffDespawnShader : Effect() {

    private var handle: Expression<String>? = null

    override fun init(
        expressions: Array<out Expression<*>>,
        matchedPattern: Int,
        isDelayed: Kleenean,
        parseResult: ParseResult,
    ): Boolean {
        @Suppress("UNCHECKED_CAST")
        handle = if (matchedPattern == 0) expressions[0] as Expression<String> else null
        return true
    }

    override fun execute(event: Event) {
        val api = Shadr.shaders ?: return
        val target = handle
        if (target == null) {
            api.despawnAll()
            return
        }
        api.despawn(target.getSingle(event) ?: return)
    }

    override fun toString(event: Event?, debug: Boolean): String =
        handle?.let { "despawn shadr shader ${it.toString(event, debug)}" } ?: "despawn all shadr shaders"
}
