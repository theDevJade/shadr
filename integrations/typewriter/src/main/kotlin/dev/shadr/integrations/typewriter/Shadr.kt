package dev.shadr.integrations.typewriter

import dev.shadr.core.PlayerId
import dev.shadr.core.Rgb
import dev.shadr.core.shader.ShaderApi
import dev.shadr.paper.ShadrPlugin
import org.bukkit.Bukkit
import org.bukkit.entity.Player

internal object Shadr {

    const val PLUGIN = "shadr"

    val plugin: ShadrPlugin?
        get() = Bukkit.getPluginManager().getPlugin(PLUGIN) as? ShadrPlugin

    val shaders: ShaderApi?
        get() = plugin?.shaderApi

    fun id(player: Player): PlayerId = PlayerId(player.uniqueId.toString())

    fun color(raw: String): Rgb = Rgb.parse(raw) ?: Rgb.WHITE

    fun warn(message: String) {
        plugin?.logger?.warning("shadr: $message")
    }
}
