/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.paper

/**
 * The contract between what the build puts in the jar and what the plugin writes out on first
 * run. It lives in its own file so [BundledAssetsTest] can hold both sides to it. A group
 * bundled with no target here is never extracted; a target naming a directory nothing loads
 * from writes files no one reads. Neither shows up at runtime.
 */
object BundledAssets {

    /** Written by the `bundledIndex` Gradle task; one `<group>/<path>` per line. */
    const val INDEX = "bundled/index.txt"

    /**
     * Where each bundled group lands under the data folder. Jar group on the left, the
     * directory the plugin already reads on the right. A group with no entry here is ignored.
     */
    val TARGETS = mapOf(
        "font" to "font",
        "sounds" to "sounds",
        "pages" to "pages",
        "components" to "components",
        "effects" to "effects",
        "shaders" to "shaders",
    )
}
