package dev.shadr.integrations.typewriter.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Default
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.utils.launch
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.toBukkitLocation
import dev.shadr.core.spi.BillboardMode
import dev.shadr.core.spi.WorldAnchor
import dev.shadr.integrations.typewriter.Shadr
import kotlinx.coroutines.Dispatchers

@Entry("shadr_spawn_shader", "Hang a shadr shader in the world", Colors.RED, "mdi:blur-radial")
class SpawnShadrShaderActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Help("The id of a shader in the shaders/items directory.")
    val shader: Var<String> = ConstVar(""),
    @Help("Reusing a handle replaces the shader that was already there.")
    val handle: Var<String> = ConstVar(""),
    val position: Var<Position> = ConstVar(Position.ORIGIN),
    @Default("1.0")
    val scale: Var<Double> = ConstVar(1.0),
    @Help("Tint as rrggbb, with or without a leading #.")
    @Default("\"ffffff\"")
    val color: Var<String> = ConstVar("ffffff"),
    val billboard: BillboardMode = BillboardMode.CENTER,
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val api = Shadr.shaders ?: return
        val shaderId = shader.get(player, context)
        val handleId = handle.get(player, context)
        if (shaderId.isBlank() || handleId.isBlank()) return

        Dispatchers.Sync.launch {
            val at = position.get(player, context).toBukkitLocation()
            val world = at.world ?: return@launch
            val failure = api.spawn(
                handle = handleId,
                shader = shaderId,
                at = WorldAnchor(world.name, at.x, at.y, at.z),
                scale = scale.get(player, context),
                color = Shadr.color(color.get(player, context)),
                billboard = billboard,
            )
            if (failure != null) Shadr.warn(failure)
        }
    }
}
