/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

object BundledAssets {
    const val INDEX = "bundled/index.txt"

    const val STAMP = ".shadr-version"

    val TARGETS = mapOf(
        "font" to "font",
        "sounds" to "sounds",
        "pages" to "pages",
        "components" to "components",
        "effects" to "effects",
        "shaders" to "shaders",
        "editor-web" to "editor-web",
    )

    val GENERATED = setOf("editor-web")
}
