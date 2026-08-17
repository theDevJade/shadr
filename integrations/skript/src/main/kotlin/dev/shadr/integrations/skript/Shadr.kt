package dev.shadr.integrations.skript

import dev.shadr.core.PlayerId
import dev.shadr.core.shader.ShaderApi
import dev.shadr.paper.ShadrPlugin
import org.bukkit.entity.Player

internal object Shadr {

    @Volatile
    private var host: ShadrPlugin? = null

    fun bind(plugin: ShadrPlugin) {
        host = plugin
    }

    fun unbind() {
        host = null
    }

    val plugin: ShadrPlugin?
        get() = host

    val shaders: ShaderApi?
        get() = host?.shaderApi

    fun id(player: Player): PlayerId = PlayerId(player.uniqueId.toString())
}
