/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags

/**
 * Renders shadr's draw strings into Adventure components.
 *
 * [parse] enables a fixed set of tag resolvers and nothing else, because a page is data. An
 * unrestricted MiniMessage parse would let an authored page inject click and hover events, run
 * commands, and insert arbitrary translated content.
 */
object MiniMessageText {

    private val trusted: MiniMessage = MiniMessage.miniMessage()

    private val safe: MiniMessage = MiniMessage.builder()
        .tags(
            TagResolver.resolver(
                StandardTags.color(),
                StandardTags.font(),
                StandardTags.decorations(),
                StandardTags.gradient(),
                StandardTags.newline(),
            ),
        )
        .build()

    fun parse(text: String): Component = try {
        safe.deserialize(text)
    } catch (_: Exception) {
        // A malformed tag must not blank the element; show the raw text instead.
        Component.text(text)
    }

    /** Full-strength parse, for operator-authored strings such as join messages. */
    fun parseTrusted(text: String): Component = try {
        trusted.deserialize(text)
    } catch (_: Exception) {
        Component.text(text)
    }
}
