package dev.shadr.integrations.typewriter.entries.audience

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Default
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.utils.launch
import com.typewritermc.core.utils.point.Position
import com.typewritermc.engine.paper.entry.entries.AudienceDisplay
import com.typewritermc.engine.paper.entry.entries.AudienceEntry
import com.typewritermc.engine.paper.utils.Sync
import com.typewritermc.engine.paper.utils.toBukkitLocation
import dev.shadr.core.spi.BillboardMode
import dev.shadr.core.spi.WorldAnchor
import dev.shadr.integrations.typewriter.Shadr
import kotlinx.coroutines.Dispatchers
import org.bukkit.entity.Player

@Entry("shadr_shader_audience", "Shadr World Shader", Colors.PURPLE, "mdi:blur-radial")
class ShadrShaderAudienceEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("The id of a shader in the shaders/items directory.")
    val shader: String = "",
    val position: Position = Position.ORIGIN,
    @Default("1.0")
    val scale: Double = 1.0,
    @Help("Tint as rrggbb, with or without a leading #.")
    @Default("\"ffffff\"")
    val color: String = "ffffff",
    val billboard: BillboardMode = BillboardMode.CENTER,
    @Help("Blocks of view distance. Zero leaves the server default.")
    val viewRange: Float = 0f,
) : AudienceEntry {
    override suspend fun display(): AudienceDisplay = ShadrShaderDisplay(
        handle = "typewriter/$id",
        shader = shader,
        position = position,
        scale = scale,
        color = color,
        billboard = billboard,
        viewRange = viewRange.takeIf { it > 0f },
    )
}

class ShadrShaderDisplay(
    private val handle: String,
    private val shader: String,
    private val position: Position,
    private val scale: Double,
    private val color: String,
    private val billboard: BillboardMode,
    private val viewRange: Float?,
) : AudienceDisplay() {

    override fun initialize() {
        super.initialize()
        val api = Shadr.shaders ?: return
        if (shader.isBlank()) return
        Dispatchers.Sync.launch {
            val at = position.toBukkitLocation()
            val world = at.world ?: return@launch
            val failure = api.spawn(
                handle = handle,
                shader = shader,
                at = WorldAnchor(world.name, at.x, at.y, at.z),
                scale = scale,
                color = Shadr.color(color),
                billboard = billboard,
                viewRange = viewRange,
            )
            if (failure != null) Shadr.warn(failure)
        }
    }

    override fun dispose() {
        super.dispose()
        val api = Shadr.shaders ?: return
        Dispatchers.Sync.launch {
            api.despawn(handle)
        }
    }

    override fun onPlayerAdd(player: Player) {}

    override fun onPlayerRemove(player: Player) {}
}
