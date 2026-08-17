package dev.shadr.integrations.typewriter.entries.audience

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.entries.AudienceDisplay
import com.typewritermc.engine.paper.entry.entries.AudienceEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.TickableDisplay
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.utils.Sync
import dev.shadr.integrations.typewriter.Shadr
import kotlinx.coroutines.Dispatchers
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Entry("shadr_page_audience", "Shadr Page", Colors.BLUE, "mdi:monitor-dashboard")
class ShadrPageAudienceEntry(
    override val id: String = "",
    override val name: String = "",
    @Placeholder
    @Help("The name of a page in the shadr pages directory. Re-evaluated every tick, so a page that resolves per player follows the player.")
    val page: Var<String> = ConstVar(""),
) : AudienceEntry {
    override suspend fun display(): AudienceDisplay = ShadrPageDisplay(page)
}

class ShadrPageDisplay(
    private val page: Var<String>,
) : AudienceDisplay(), TickableDisplay {

    private val open = ConcurrentHashMap<UUID, String>()

    override fun tick() {
        for (player in players) {
            val wanted = page.get(player)
            if (wanted.isBlank() || open[player.uniqueId] == wanted) continue
            show(player, wanted)
        }
    }

    override fun onPlayerAdd(player: Player) {
        val wanted = page.get(player)
        if (wanted.isBlank()) return
        show(player, wanted)
    }

    override fun onPlayerRemove(player: Player) {
        if (open.remove(player.uniqueId) == null) return
        val shadr = Shadr.plugin ?: return
        Dispatchers.Sync.launch {
            shadr.closePage(Shadr.id(player))
        }
    }

    private fun show(player: Player, pageName: String) {
        val shadr = Shadr.plugin ?: return
        open[player.uniqueId] = pageName
        Dispatchers.Sync.launch {
            shadr.openPage(Shadr.id(player), pageName, replacing = true)
        }
    }
}
