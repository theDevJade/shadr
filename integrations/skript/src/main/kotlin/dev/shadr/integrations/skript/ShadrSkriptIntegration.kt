package dev.shadr.integrations.skript

import dev.shadr.paper.ShadrPlugin
import org.bukkit.Bukkit

object ShadrSkriptIntegration {

    private const val SKRIPT = "Skript"

    val isPresent: Boolean
        get() = Bukkit.getPluginManager().isPluginEnabled(SKRIPT)

    @JvmStatic
    fun enableIfPresent(plugin: ShadrPlugin): Boolean {
        if (!isPresent) return false
        return runCatching { SkriptSyntax.register(plugin) }
            .onFailure { plugin.logger.warning("shadr: Skript syntax not registered, ${it.message}") }
            .getOrDefault(false)
    }

    @JvmStatic
    fun disable() {
        Shadr.unbind()
    }
}
