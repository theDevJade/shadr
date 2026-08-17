/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core

import dev.shadr.core.action.ActionRunner
import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.page.BuiltinPlaceholders
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Page
import dev.shadr.core.page.PlaceholderResolver
import dev.shadr.core.page.PlaceholderScanner
import dev.shadr.core.session.UiSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceholderTest {
    private val player = PlayerId("00000000-0000-0000-0000-000000000001")

    private object SilentHost : dev.shadr.core.action.ActionHost {
        override fun runAsPlayer(player: PlayerId, command: String) = Unit
        override fun runAsConsole(command: String) = Unit
        override fun message(player: PlayerId, text: String) = Unit
        override fun playSound(player: PlayerId, sound: String, volume: Double) = Unit
        override fun closePage(player: PlayerId) = Unit
        override fun openPage(player: PlayerId, page: String, replacing: Boolean) = Unit
        override fun teleport(player: PlayerId, destination: String) = Unit
        override fun hasPermission(player: PlayerId, permission: String) = true
        override fun scheduleTicks(ticks: Long, task: () -> Unit) = task()
    }

    private val resolver = PlaceholderResolver { _, name ->
        when (name) {
            "shadr_online" -> "128"
            "shadr_player" -> "Steve"
            else -> null
        }
    }

    @Test
    fun `a known placeholder is substituted`() {
        assertEquals(
            "128 online",
            PlaceholderScanner.apply("%shadr_online% online", player, resolver),
        )
    }

    @Test
    fun `several in one string are all substituted`() {
        assertEquals(
            "Steve, 128 online",
            PlaceholderScanner.apply("%shadr_player%, %shadr_online% online", player, resolver),
        )
    }

    @Test
    fun `an unknown placeholder is left exactly as written`() {
        assertEquals(
            "tps %shadr_tps%",
            PlaceholderScanner.apply("tps %shadr_tps%", player, resolver),
        )
    }

    @Test
    fun `percentages in ordinary text are not placeholders`() {
        for (text in listOf(
            "62% of today's limit",
            "50% off, 20% back",
            "100%",
            "a % on its own",
            "%",
            "% %",
        )) {
            assertEquals(text, PlaceholderScanner.apply(text, player, resolver), "mangled: $text")
            assertFalse(
                PlaceholderScanner.hasPlaceholder(text) &&
                    PlaceholderScanner.apply(text, player, resolver) != text,
                "substituted something in: $text",
            )
        }
    }

    @Test
    fun `a span with whitespace inside is not a placeholder`() {
        val text = "up 20% and 5% down"
        assertEquals(text, PlaceholderScanner.apply(text, player, resolver))
    }

    @Test
    fun `text with no percent at all is returned untouched`() {
        val text = "Every element here is a glyph."
        assertFalse(PlaceholderScanner.hasPlaceholder(text))
        assertEquals(text, PlaceholderScanner.apply(text, player, resolver))
    }

    @Test
    fun `PlaceholderAPI-shaped names are recognised`() {
        val papi = PlaceholderResolver { _, name ->
            when (name) {
                "statistic_mine_block:stone" -> "482"
                "vault_eco_balance_formatted" -> "1,240"
                "some-expansion.value" -> "ok"
                else -> null
            }
        }
        assertEquals("482 mined", PlaceholderScanner.apply("%statistic_mine_block:stone% mined", player, papi))
        assertEquals("1,240", PlaceholderScanner.apply("%vault_eco_balance_formatted%", player, papi))
        assertEquals("ok", PlaceholderScanner.apply("%some-expansion.value%", player, papi))
    }

    @Test
    fun `a wider name class still leaves unclaimed spans verbatim`() {
        val nothing = PlaceholderResolver.NONE
        for (text in listOf("A%B-C%D", "%1.5%", "%a:b%", "cost %12-30% each")) {
            assertEquals(text, PlaceholderScanner.apply(text, player, nothing), "mangled: $text")
        }
    }

    private fun pageOf(yaml: String): dev.shadr.core.page.Page {
        val dir = kotlin.io.path.createTempDirectory("shadr-dynamic").toFile()
        val pages = java.io.File(dir, "pages").apply { mkdirs() }
        java.io.File(dir, "components").mkdirs()
        java.io.File(dir, "effects").mkdirs()
        val file = java.io.File(pages, "p.yml").apply { writeText(yaml) }
        val loader = dev.shadr.core.page.PageLoader(pages, java.io.File(dir, "components"), java.io.File(dir, "effects"))
        return loader.loadPage(file)!!
    }

    @Test
    fun `a numeric field with a placeholder is carried, not flattened`() {
        val page = pageOf(
            """
            |name: p
            |blocks:
            |  - type: block
            |    id: bar
            |    size: {width: "440 * %shadr_online% / 100", height: 8}
            """.trimMargin(),
        )
        val element = page.elements.single { it.id == "bar" }
        assertEquals(
            "440 * %shadr_online% / 100", element.dynamic["size.width"],
            "the expression was not carried: ${element.dynamic}",
        )
    }

    @Test
    fun `an ordinary expression is still evaluated at load`() {
        val page = pageOf(
            """
            |name: p
            |blocks:
            |  - type: block
            |    id: box
            |    size: {width: "440 / 2", height: 8}
            """.trimMargin(),
        )
        val element = page.elements.single { it.id == "box" }
        assertTrue(element.dynamic.isEmpty(), "a static expression was marked dynamic")
        assertEquals(220.0, element.width)
    }

    @Test
    fun `a dynamic width is evaluated against the resolver at render time`() {
        var online = 50
        val live = PlaceholderResolver { _, name -> if (name == "shadr_online") online.toString() else null }
        val page = pageOf(
            """
            |name: p
            |blocks:
            |  - type: block
            |    id: bar
            |    size: {width: "440 * %shadr_online% / 100", height: 8}
            """.trimMargin(),
        )
        val open = UiSession(
            player = player,
            page = page,
            renderer = PageRenderer(),
            effects = emptyMap(),
            actionRunner = ActionRunner(SilentHost),
            placeholders = live,
        )

        assertEquals(220.0, open.currentPageWidthOf("bar", open), 0.001)
        online = 100
        assertTrue(open.refreshPlaceholders(), "a changed dynamic field went unnoticed")
        assertEquals(440.0, open.currentPageWidthOf("bar", open), 0.001)
    }

    private fun UiSession.currentPageWidthOf(id: String, session: UiSession): Double {
        val draw = session.draws().first { it.key.startsWith(id) }

        return draw.scale.x * 2.0 / dev.shadr.core.hud.HudPositionCalculator.YAML_TO_HUD_SIZE_FACTOR
    }

    @Test
    fun `opacity is clamped rather than wrapped`() {
        val page = pageOf(
            """
            |name: p
            |blocks:
            |  - type: block
            |    id: fade
            |    opacity: "%level% * 100"
            |    size: {width: 10, height: 10}
            """.trimMargin(),
        )
        for ((level, expected) in listOf(0 to 0, 2 to 200, 9 to 255)) {
            val resolver = PlaceholderResolver { _, name -> if (name == "level") level.toString() else null }
            val open = UiSession(
                player = player, page = page, renderer = PageRenderer(), effects = emptyMap(),
                actionRunner = ActionRunner(SilentHost), placeholders = resolver,
            )
            val drawn = open.draws().firstOrNull { it.key.startsWith("fade") }

            if (expected == 0) assertTrue(drawn == null, "a fully transparent element was drawn")
            else assertEquals(expected, drawn!!.opacity)
        }
    }

    @Test
    fun `position is not treated as a dynamic field`() {
        val page = pageOf(
            """
            |name: p
            |blocks:
            |  - type: block
            |    id: box
            |    position: {x: "%shadr_online%", y: 0}
            |    size: {width: 10, height: 10}
            """.trimMargin(),
        )
        val element = page.elements.single { it.id == "box" }
        assertTrue(
            "position.x" !in element.dynamic,
            "position was collected as dynamic, which would desynchronise it from layout",
        )
    }

    @Test
    fun `the built-in resolver answers every name it advertises`() {
        val snapshot = BuiltinPlaceholders.Snapshot(
            playerName = "Steve", online = 12, maxPlayers = 40,
            tps = "19.9", pingMillis = 24, world = "world", worldTime = "06:00",
        )
        val builtin = BuiltinPlaceholders { snapshot }
        for (name in BuiltinPlaceholders.NAMES) {
            val value = builtin.resolve(player, name)
            assertTrue(value != null, "$name is advertised but unanswered")
            assertTrue(value.isNotBlank(), "$name resolved to blank")
        }
        assertEquals(null, builtin.resolve(player, "something_else"))
    }

    @Test
    fun `every built-in name is namespaced`() {
        for (name in BuiltinPlaceholders.NAMES) {
            assertTrue(name.startsWith("shadr_"), "$name would collide with another plugin's")
        }
    }

    @Test
    fun `a chain takes the first answer and falls through otherwise`() {
        val first = PlaceholderResolver { _, name -> if (name == "a") "first" else null }
        val second = PlaceholderResolver { _, name -> if (name == "a") "second" else "fallback" }
        val chained = PlaceholderResolver.chain(first, second)
        assertEquals("first", chained.resolve(player, "a"))
        assertEquals("fallback", chained.resolve(player, "b"))
    }

    private fun session(text: String, resolver: PlaceholderResolver, value: () -> String): UiSession {
        val element = Element(id = "label", type = ElementType.TEXT, text = text, width = 64.0, height = 64.0)
        return UiSession(
            player = player,
            page = Page(name = "t", elements = listOf(element)),
            renderer = PageRenderer(),
            effects = emptyMap(),
            actionRunner = ActionRunner(SilentHost),
            placeholders = resolver,
        ).also { value() }
    }

    @Test
    fun `a session renders the substituted text, not the template`() {
        val open = session("%shadr_online% online", resolver) { "" }
        val drawn = open.draws().first { it.key.startsWith("label") }
        assertTrue(drawn.content.contains("128"), "the template was drawn instead of the value: ${drawn.content}")
        assertFalse(drawn.content.contains("%shadr_online%"))
    }

    @Test
    fun `refreshing does nothing while the value is unchanged`() {
        var online = 128
        val live = PlaceholderResolver { _, name -> if (name == "shadr_online") online.toString() else null }
        val open = session("%shadr_online% online", live) { "" }
        open.consumeDirty()

        assertFalse(open.refreshPlaceholders(), "an unchanged value reported a change")
        assertFalse(open.consumeDirty(), "an unchanged refresh marked the page dirty")

        online = 129
        assertTrue(open.refreshPlaceholders(), "a changed value went unnoticed")
        val drawn = open.draws().first { it.key.startsWith("label") }
        assertTrue(drawn.content.contains("129"), "the new value was not rendered: ${drawn.content}")
    }

    @Test
    fun `a page with no placeholders reports nothing to refresh`() {
        val open = session("static text", resolver) { "" }
        assertFalse(open.hasPlaceholders)
        assertFalse(open.refreshPlaceholders())
    }

    @Test
    fun `a page with placeholders says so`() {
        assertTrue(session("%shadr_player%", resolver) { "" }.hasPlaceholders)
    }
}
