/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.page.PageLoader
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HudScreenTest {

    private fun screenOf(screen: String) = createTempDirectory("shadr-hud").toFile().let { dir ->
        val pages = File(dir, "pages").apply { mkdirs() }
        File(dir, "components").mkdirs()
        File(dir, "effects").mkdirs()
        val file = File(pages, "p.yml").apply { writeText("name: p\n$screen\nblocks: []\n") }
        PageLoader(pages, File(dir, "components"), File(dir, "effects")).loadPage(file)!!.screen
    }

    @Test
    fun `an ordinary page locks the camera so the cursor can be driven`() {
        val screen = screenOf("screen:\n  width: 1920\n  height: 1080\n  cursorSpeed: 2.0")
        assertFalse(screen.hud)
        assertTrue(screen.locksCamera)
    }

    @Test
    fun `a page that asks for hud mode leaves the player free to move`() {
        val screen = screenOf("screen:\n  width: 1920\n  height: 1080\n  hud: true")
        assertTrue(screen.hud)
        assertFalse(screen.locksCamera, "a HUD must not take the mouse; the player has to keep playing")
    }

    @Test
    fun `a cursorless page is a hud even without the flag`() {
        val screen = screenOf("screen:\n  width: 1920\n  height: 1080\n  cursorSize: 0")
        assertTrue(
            screen.hud,
            "cursorSize 0 is how the shipped hud_overlay page has always signalled itself",
        )
        assertFalse(screen.locksCamera)
    }

    @Test
    fun `the shipped hud overlay page is recognised as a hud`() {
        val repo = File("..").canonicalFile
        val loader = PageLoader(
            File(repo, "protocol/pages"),
            File(repo, "protocol/components"),
            File(repo, "protocol/effects"),
        )
        val page = loader.loadPage(File(repo, "protocol/pages/hud_overlay.yml"))
        if (page == null) return
        assertTrue(page.screen.hud, "hud_overlay still locks the camera, so the player cannot move")
    }
}
