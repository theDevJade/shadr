/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.hud.HudDraw
import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Page
import dev.shadr.core.video.VideoClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VideoElementTest {

    private fun draw(element: Element): HudDraw =
        PageRenderer().render(Page(name = "t", elements = listOf(element))).draws
            .first { it.elementId == element.id }

    private fun video(id: String = "panel", clip: String? = "intro") = Element(
        id = id,
        type = ElementType.VIDEO,
        item = clip,
        width = 640.0,
        height = 360.0,
    )

    @Test
    fun `a video element draws as an item quad wearing the clip's model`() {
        val out = draw(video())
        assertEquals(HudDraw.Kind.ITEM, out.kind)
        assertEquals(PageRenderer.SHAPE_ITEM, out.item, "the quad should be the ordinary shape item")
        assertEquals("minecraft:shadr/video_intro", out.itemModel)
    }

    @Test
    fun `the model matches what the pack writes for that clip`() {
        val clip = VideoClip(id = "intro", width = 64, height = 36, frameCount = 4, fps = 30.0)
        assertEquals(
            clip.itemModel, draw(video()).itemModel,
            "the renderer and the pack disagree on the model name, so the quad would be untextured",
        )
    }

    @Test
    fun `a video element with no clip names no model rather than a broken one`() {
        assertEquals(null, draw(video(clip = null)).itemModel)
    }

    @Test
    fun `the quad sits exactly where an equivalent shader quad would`() {
        val out = draw(video())
        val shader = draw(
            Element(id = "panel", type = ElementType.SHADER, item = "portal", width = 640.0, height = 360.0),
        )
        assertEquals(shader.translation, out.translation, "a video panel drifted from a shader quad")
    }

    @Test
    fun `a video panel covers the size it was authored at`() {
        val out = draw(video())
        val sdf = draw(
            Element(
                id = "panel",
                type = ElementType.BLOCK_SDF,
                width = 640.0,
                height = 360.0,
            ),
        )
        assertEquals(sdf.scale.x, out.scale.x, 1e-9, "a video panel is narrower than its element")
        assertEquals(sdf.scale.y, out.scale.y, 1e-9, "a video panel is shorter than its element")
    }

    @Test
    fun `a video panel takes part in hit testing like anything else`() {
        val page = Page(
            name = "t",
            elements = listOf(video().copy(x = 100.0, y = 50.0)),
        )
        val rendered = PageRenderer().render(page)
        val region = rendered.hitRegions.firstOrNull { it.elementId == "panel" }
        assertNotNull(region, "a video panel produced no hit region")
        assertTrue(region.contains(420.0, 230.0), "the region does not cover the panel")
    }

    @Test
    fun `the shipped demo page really produces a video quad`() {
        val protocol = java.io.File("../protocol").canonicalFile
        val loader = dev.shadr.core.page.PageLoader(
            pagesDir = java.io.File(protocol, "pages"),
            componentsDir = java.io.File(protocol, "components"),
            effectsDir = java.io.File(protocol, "effects"),
        )
        val page = assertNotNull(
            loader.loadPage(java.io.File(protocol, "pages/video_demo.yml"), loader.loadComponents()),
            "video_demo.yml did not load",
        )

        val element = assertNotNull(
            page.elements.firstOrNull { it.type == ElementType.VIDEO },
            "the demo page has no video element; `video:` may not be reaching the loader",
        )
        assertEquals("demo", element.item, "the clip id did not survive parsing")

        val out = PageRenderer().render(page).draws.first { it.elementId == element.id }
        assertEquals("minecraft:shadr/video_demo", out.itemModel)
        assertTrue(
            element.width >= VideoFormatBounds.NARROWEST,
            "a panel this narrow steps uv faster than the composite's neighbour test allows",
        )
    }

    private object VideoFormatBounds {
        val NARROWEST =
            dev.shadr.core.video.VideoFormat.UV_MAX /
                dev.shadr.core.video.VideoFormat.UV_CONTINUITY
    }

    @Test
    fun `video is a distinct element type the loader can name`() {
        assertEquals("video", ElementType.VIDEO.id)
        assertTrue(
            ElementType.entries.count { it.id == "video" } == 1,
            "two element types answer to 'video'",
        )
    }
}
