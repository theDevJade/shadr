/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.shader.EnvironmentEffect
import dev.shadr.core.video.VideoClip
import java.awt.image.BufferedImage
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoPackTest {
    private fun repo() = File("..").canonicalFile

    private val overlayRoot = "shadr_26_2/assets/minecraft"

    private fun clip(id: String = "intro", frames: Int = 12): VideoAssets.Source {
        val width = 64
        val height = 36
        val mosaic = dev.shadr.core.video.MosaicEncoder.encode(
            List(frames) { f -> IntArray(width * height) { (f * 7 + it) and 0xFFFFFF } },
            width, height, 60.0,
        )!!
        return VideoAssets.Source(
            clip = VideoClip(id = id, width = width, height = height, frameCount = frames, fps = 60.0),
            mosaic = mosaic,
        )
    }

    private fun generate(
        effects: Set<EnvironmentEffect>,
        videos: List<VideoAssets.Source> = emptyList(),
    ): File {
        val root = createTempDirectory("shadr-video").toFile()
        val shaders = File(root, "src").also { File(repo(), "shaders").copyRecursively(it) }
        File(shaders, "custom").deleteRecursively()

        val out = File(root, "pack")
        PackGenerator(
            shaderSrc = shaders,
            fontDir = File(repo(), "assets/font"),
            soundDir = null,
            environment = EnvironmentEffect.entries.associateWith { it in effects },
            videos = videos,
        ).build(out)
        return out
    }

    private fun chain(out: File) = File(out, "$overlayRoot/post_effect/creeper.json")

    @Test
    fun `with video off the authored chain is passed through untouched`() {
        val out = generate(setOf(EnvironmentEffect.FROSTED_GLASS), videos = listOf(clip()))
        assertEquals(
            File(repo(), "shaders/overlays/mc_26_2/post_effect/creeper.json").readText(),
            chain(out).readText(),
            "generating a pack with video disabled rewrote the frosted glass chain",
        )
    }

    @Test
    fun `video passes join the blur chain without displacing it`() {
        val out = generate(
            setOf(EnvironmentEffect.FROSTED_GLASS, EnvironmentEffect.VIDEO),
            videos = listOf(clip()),
        )
        val text = chain(out).readText()

        for (blurPass in listOf("shadr_blur_mask", "shadr_blur_composite", "shadr_blur_blit")) {
            assertTrue(text.contains(blurPass), "the merge dropped the $blurPass pass")
        }
        for (videoPass in listOf("shadr_video_decode", "shadr_video_writeback", "shadr_video_composite")) {
            assertTrue(text.contains(videoPass), "the merge did not add $videoPass")
        }
        for (target in listOf("shadr:mask_a", "shadr:backdrop_a", "shadr:composed")) {
            assertTrue(text.contains(target), "the merge dropped the $target target")
        }
    }

    @Test
    fun `the frame store survives between frames or nothing can predict from it`() {
        val out = generate(setOf(EnvironmentEffect.VIDEO), videos = listOf(clip()))
        val text = chain(out).readText()

        val prev = Regex(""""${PostChainBuilder.TARGET_PREV}"\s*:\s*\{([^}]*)}""")
            .find(text)?.groupValues?.get(1)
        assertTrue(prev != null, "no ${PostChainBuilder.TARGET_PREV} target in the chain")
        assertTrue(
            prev.contains(""""persistent": true"""),
            "the previous-frame target is not persistent, so it is cleared every frame",
        )
        assertTrue(
            !text.substringAfter(PostChainBuilder.TARGET_CUR).substringBefore("}")
                .contains("persistent"),
            "the working target does not need to persist",
        )
    }

    @Test
    fun `no pass reads and writes the same target`() {
        val out = generate(
            setOf(EnvironmentEffect.FROSTED_GLASS, EnvironmentEffect.VIDEO),
            videos = listOf(clip()),
        )
        val text = chain(out).readText()

        for (chunk in text.split(""""vertex_shader"""").drop(1)) {
            val output = Regex(""""output":\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1) ?: continue
            val inputs = Regex(""""target":\s*"([^"]+)"""").findAll(chunk).map { it.groupValues[1] }.toSet()
            assertTrue(output !in inputs, "a pass both reads and writes '$output', which is undefined")
        }
    }

    @Test
    fun `every program the composed chain names is in the pack`() {
        val out = generate(setOf(EnvironmentEffect.VIDEO), videos = listOf(clip()))
        val text = chain(out).readText()

        val referenced = Regex(""""(?:vertex|fragment)_shader":\s*"minecraft:([^"]+)"""")
            .findAll(text).map { it.groupValues[1] }.toSet()
        assertTrue(referenced.isNotEmpty())

        for (id in referenced) {
            val extension = if (id.endsWith("fullscreen")) "vsh" else "fsh"
            val file = File(out, "$overlayRoot/shaders/$id.$extension")
            assertTrue(file.isFile, "the chain names $id but ${file.name} is not in the pack")
        }
    }

    @Test
    fun `the bitstream the chain names is written, sized as declared`() {
        val source = clip(frames = 12)
        val out = generate(setOf(EnvironmentEffect.VIDEO), videos = listOf(source))

        // Textures are base assets: the overlay only carries shader overrides.
        val data = File(out, "assets/minecraft/textures/effect/shadr/video/${source.clip.id}.png")
        assertTrue(data.isFile, "no bitstream at ${data.path}")

        val image = javax.imageio.ImageIO.read(data)
        assertEquals(dev.shadr.core.video.MosaicFormat.SHEET_EDGE, image.width)
        assertEquals(VideoAssets.dataRows(source.mosaic), image.height)

        val declared = Regex(""""width":\s*(\d+),\s*"height":\s*(\d+),\s*"bilinear"""")
            .find(chain(out).readText())
        assertTrue(declared != null, "the chain declares no data dimensions")
        assertEquals(image.width, declared.groupValues[1].toInt(), "the chain lies about data width")
        assertEquals(image.height, declared.groupValues[2].toInt(), "the chain lies about data height")
    }

    @Test
    fun `the bitstream survives the pack byte for byte`() {
        val source = clip(frames = 12)
        val out = generate(setOf(EnvironmentEffect.VIDEO), videos = listOf(source))
        val image = javax.imageio.ImageIO.read(
            File(out, "assets/minecraft/textures/effect/shadr/video/${source.clip.id}.png"),
        )
        val edge = dev.shadr.core.video.MosaicFormat.SHEET_EDGE
        for (i in 0 until source.mosaic.texelCount) {
            assertEquals(
                source.mosaic.data[i], image.getRGB(i % edge, i / edge),
                "texel $i changed on the way into the pack",
            )
        }
    }

    @Test
    fun `the quad that claims the pixels ships with the clip`() {
        val out = generate(setOf(EnvironmentEffect.VIDEO), videos = listOf(clip()))
        for (path in listOf(
            "textures/item/shadr/video_intro.png",
            "models/item/shadr/video_intro.json",
            "items/shadr/video_intro.json",
        )) {
            assertTrue(File(out, "assets/minecraft/$path").isFile, "missing $path")
        }
    }

}
