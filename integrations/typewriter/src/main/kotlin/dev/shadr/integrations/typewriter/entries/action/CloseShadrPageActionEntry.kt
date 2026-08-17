package dev.shadr.integrations.typewriter.entries.action

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.utils.launch
import com.typewritermc.engine.paper.entry.Criteria
import com.typewritermc.engine.paper.entry.Modifier
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.ActionEntry
import com.typewritermc.engine.paper.entry.entries.ActionTrigger
import com.typewritermc.engine.paper.utils.Sync
import dev.shadr.integrations.typewriter.Shadr
import kotlinx.coroutines.Dispatchers

@Entry("shadr_close_page", "Close the shadr page the player has open", Colors.RED, "mdi:monitor-off")
class CloseShadrPageActionEntry(
    override val id: String = "",
    override val name: String = "",
    override val criteria: List<Criteria> = emptyList(),
    override val modifiers: List<Modifier> = emptyList(),
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
) : ActionEntry {
    override fun ActionTrigger.execute() {
        val shadr = Shadr.plugin ?: return
        Dispatchers.Sync.launch {
            shadr.closePage(Shadr.id(player))
        }
    }
}
