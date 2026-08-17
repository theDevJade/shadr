package dev.shadr.integrations.skript

import ch.njol.skript.Skript
import ch.njol.skript.lang.ExpressionType
import dev.shadr.paper.ShadrPlugin

internal object SkriptSyntax {

    fun register(plugin: ShadrPlugin): Boolean {
        if (!Skript.isAcceptRegistrations()) {
            plugin.logger.warning("shadr: Skript is present but no longer accepting registrations")
            return false
        }

        Shadr.bind(plugin)
        Skript.registerAddon(plugin)
        registerEffects()
        registerExpressions()
        registerConditions()
        plugin.logger.info("shadr: Skript found, shadr syntax registered")
        return true
    }

    private fun registerEffects() {
        Skript.registerEffect(
            EffOpenPage::class.java,
            "open shadr page %string% (for|to) %players%",
            "open shadr popup %string% (for|to) %players%",
        )
        Skript.registerEffect(
            EffClosePage::class.java,
            "close [the] shadr page (for|of) %players%",
        )
        Skript.registerEffect(
            EffReloadShadr::class.java,
            "reload shadr [pack]",
        )
        Skript.registerEffect(
            EffSpawnShader::class.java,
            "spawn shadr shader %string% (as|named|with handle) %string% at %location% " +
                "[with scale %-number%] [(colored|coloured) %-string%]",
        )
        Skript.registerEffect(
            EffDespawnShader::class.java,
            "despawn shadr shader %string%",
            "despawn all shadr shaders",
        )
    }

    private fun registerExpressions() {
        Skript.registerExpression(
            ExprShaders::class.java,
            String::class.java,
            ExpressionType.SIMPLE,
            "[all] [installed] shadr shaders",
        )
        Skript.registerExpression(
            ExprPlacedShaders::class.java,
            String::class.java,
            ExpressionType.SIMPLE,
            "[all] placed shadr shader handles",
        )
    }

    private fun registerConditions() {
        Skript.registerCondition(
            CondShaderExists::class.java,
            "shadr shader %string% (exists|is installed)",
            "shadr shader %string% (does not|doesn't) exist",
        )
    }
}
