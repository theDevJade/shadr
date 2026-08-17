/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags

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
        Component.text(text)
    }

    fun parseTrusted(text: String): Component = try {
        trusted.deserialize(text)
    } catch (_: Exception) {
        Component.text(text)
    }
}
