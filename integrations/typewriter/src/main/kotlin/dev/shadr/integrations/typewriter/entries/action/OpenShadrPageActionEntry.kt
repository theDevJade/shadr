package dev.shadr.integrations.typewriter.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Placeholder
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.utils.Sync
import dev.shadr.integrations.typewriter.Shadr
import kotlinx.coroutines.Dispatchers

@Entry("shadr_open_page", "Open a shadr page for the player", Colors.RED, "mdi:monitor-dashboard")
class OpenShadrPageActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    @Placeholder
    @Help("The name of a page in the shadr pages directory.")
    val page: Var<String> = ConstVar(""),
    @Help("Replace whatever the player already has open, with no popup layered on top of it.")
    val replacing: Boolean = true,
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val shadr = Shadr.plugin ?: return
        val target = page.get(player, context)
        if (target.isBlank()) return
        Dispatchers.Sync.launch {
            shadr.openPage(Shadr.id(player), target, replacing)
        }
    }
}
