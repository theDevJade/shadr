/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class PackGenerator(
    private val shaderSrc: File,
    private val fontDir: File,
    private val soundDir: File? = null,
    private val shapeSupport: Boolean = false,
    private val shaders: dev.shadr.core.shader.ShaderRegistry = dev.shadr.core.shader.ShaderRegistry.EMPTY,
    private val environment: Map<dev.shadr.core.shader.EnvironmentEffect, Boolean>,
    private val videos: List<VideoAssets.Source> = emptyList(),
    private val stream: dev.shadr.core.stream.StreamGeometry? = null,
) {
    val overrides = mutableListOf<String>()

    val gaps = mutableListOf<Gap>()

    data class Gap(
        val overlay: PackOverlay,
        val feature: String,
        val missing: List<String>,
        val consequence: String,
    ) {
        override fun toString(): String =
            "${overlay.label} (${overlay.directory}): $feature needs ${missing.joinToString(", ")} " +
                "($consequence)"
    }

    fun build(outRoot: File, clean: Boolean = true): File {
        overrides.clear()
        gaps.clear()
        if (clean) outRoot.deleteRecursively()
        outRoot.mkdirs()

        writePackMeta(outRoot)
        writePackIcon(outRoot)
        FontAssets.writeAll(outRoot, fontDir)
        ShapeAssets.writeAll(outRoot)
        ItemShaderAssets.writeAll(outRoot, shaders)
        VideoAssets.writeAll(outRoot, videos)
        if (environment[dev.shadr.core.shader.EnvironmentEffect.CELESTIALS] == true) {
            CelestialAssets.writeAll(outRoot)
        }
        writeSounds(outRoot)
        writeOptifineColors(outRoot)
        writeShaderOverlays(outRoot)
        return outRoot
    }

    private fun writePackMeta(root: File) {
        val entries = PackOverlay.entries.joinToString(",\n") { overlay ->
            """
            |      {
            |        "formats": [${overlay.minFormat}, ${overlay.maxFormat}],
            |        "min_format": ${overlay.minFormat},
            |        "max_format": ${overlay.maxFormat},
            |        "directory": "${overlay.directory}"
            |      }
            """.trimMargin()
        }
        write(
            root, "pack.mcmeta",
            """
            |{
            |  "pack": {
            |    "description": "§bshadr §8| §7UI resource pack",
            |    "pack_format": ${PackOverlay.BASE_PACK_FORMAT},
            |    "supported_formats": [0, 9999],
            |    "min_format": 0,
            |    "max_format": 9999
            |  },
            |  "overlays": {
            |    "entries": [
            |$entries
            |    ]
            |  }
            |}
            |
            """.trimMargin(),
        )
    }

    private fun writePackIcon(root: File) {
        val size = 128
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x0F, 0x0F, 0x12)
        g.fillRoundRect(0, 0, size, size, 28, 28)
        g.color = Color(0x4C, 0xC9, 0xF0)
        g.fillRoundRect(26, 30, 76, 20, 10, 10)
        g.color = Color(0xE8, 0xE8, 0xF0)
        g.fillRoundRect(26, 58, 50, 14, 7, 7)
        g.fillRoundRect(26, 80, 66, 14, 7, 7)
        g.dispose()
        val file = File(root, "pack.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }

    private fun writeSounds(root: File) {
        val entries = mutableListOf<String>()
        val src = soundDir?.takeIf { it.isDirectory }
        val sfx = src?.let { File(it, "sfx").takeIf { dir -> dir.isDirectory } ?: it }

        if (sfx != null) {
            val copied = mutableListOf<String>()
            sfx.listFiles { f -> f.isFile && f.extension == "ogg" }?.sortedBy { it.name }?.forEach { file ->
                val name = file.nameWithoutExtension.removePrefix("ui_")
                file.copyTo(File(root, "assets/minecraft/sounds/shadr/$name.ogg"), overwrite = true)
                copied += name
            }

            val click = File(sfx, "ui_click.ogg").takeIf { it.isFile }
            if (click != null) {
                for (index in 1..4) {
                    click.copyTo(File(root, "assets/minecraft/sounds/dig/stone$index.ogg"), overwrite = true)
                }
            }
            entries += copied.map { name -> soundEntry("shadr.$name", "shadr/$name") }
        }

        entries += videos.filter { it.audio != null }.map { source ->
            soundEntry(source.clip.soundKey, VideoAssets.soundPath(source.clip))
        }

        if (entries.isEmpty()) return
        write(root, "assets/minecraft/sounds.json", "{\n" + entries.joinToString(",\n") + "\n}\n")
    }

    private fun soundEntry(event: String, path: String): String =
        """	"$event": { "category": "master", "sounds": [ "$path" ] }"""

    private fun writeOptifineColors(root: File) {
        write(
            root, "assets/minecraft/optifine/color.properties",
            """
            |# Loading screen
            |screen.loading=000000
            |screen.loading.bar=101014
            |screen.loading.progress=4cc9f0
            |screen.loading.outline=4cc9f0
            |screen.loading.blend=off
            |
            """.trimMargin(),
        )
    }

    private fun writeShaderOverlays(root: File) {
        val overlaysDir = File(shaderSrc, "overlays")
        for (overlay in PackOverlay.entries) {
            val source = File(overlaysDir, overlay.sourceDirectory)
            if (!source.isDirectory) {
                error("missing shader sources for ${overlay.label}: ${source.path}")
            }
            val assets = File(root, "${overlay.directory}/assets/minecraft")
            val target = File(assets, "shaders")
            copyTree(source, assets)

            for (dir in listOf("all", overlay.sourceDirectory)) {
                val custom = File(File(shaderSrc, "custom"), dir)
                if (!custom.isDirectory) continue
                custom.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(custom).path
                    overrides += "${overlay.directory}/$rel"
                }
                copyTree(custom, assets)
            }

            val itemProgram = File(target, "core/item.fsh")
            if (itemProgram.isFile) {
                write(
                    root,
                    "${overlay.directory}/assets/minecraft/shaders/include/shadr_shaders.glsl",
                    dev.shadr.core.shader.GlslComposer.compose(shaders),
                )
            } else {
                reportItemFragmentGap(root, overlay)
            }

            if (File(target, "include/shadr_stream.glsl").isFile) {
                write(
                    root,
                    "${overlay.directory}/assets/minecraft/shaders/include/shadr_map.glsl",
                    dev.shadr.core.stream.MapPalette.glsl() + "\n" +
                        dev.shadr.core.stream.StreamFormat.glsl() + "\n" +
                        dev.shadr.core.stream.StreamLayout.glsl() + "\n" +
                        dev.shadr.core.stream.StreamImage.glsl() + "\n" +
                        dev.shadr.core.stream.StreamBlocks.glsl() + "\n" +
                        dev.shadr.core.stream.StreamCodec.glsl(dev.shadr.core.stream.StreamPresets.CODEC_1080),
                )
            }

            val kept = environment.filterValues { it }.keys.flatMap { it.programs }.toSet()
            for ((effect, on) in environment) {
                if (on) {
                    reportEnvironmentGap(assets, overlay, effect)
                    continue
                }
                effect.programs.filterNot { it in kept }.forEach { assetFor(assets, it).delete() }
            }

            writeVideoChain(assets, overlay)
        }
    }

    private fun writeVideoChain(assets: File, overlay: PackOverlay) {
        if (environment[dev.shadr.core.shader.EnvironmentEffect.VIDEO] != true) return
        val active = stream?.takeIf {
            (it.probe || it.codec) && File(assets, "shaders/include/shadr_stream.glsl").isFile
        }
        if (videos.isEmpty() && active == null) return

        val chainFile = assetFor(assets, dev.shadr.core.shader.PostChains.HOST_PATH)

        val blurChain = chainFile.takeIf { frostedGlassOn && it.isFile }?.readText()
        fun abandon() {
            if (!frostedGlassOn) chainFile.delete()
        }

        if (active != null) {
            val programs = if (active.codec) PostChainBuilder.CODEC_PROGRAMS else PostChainBuilder.STREAM_PROGRAMS
            val absent = programs.filterNot { assetFor(assets, it).isFile }
            if (absent.isNotEmpty()) {
                gaps += Gap(
                    overlay = overlay,
                    feature = if (active.codec) "streamed video decode" else "streamed video probe",
                    missing = absent,
                    consequence = "the map stream cannot run on these clients",
                )
                abandon()
                return
            }
        }

        val baked = videos.filter { it.mosaic != null }
        if (baked.isEmpty()) {
            val composed = PostChainBuilder.compose(blurChain, null, active) ?: run { abandon(); return }
            chainFile.parentFile.mkdirs()
            chainFile.writeText(composed)
            return
        }

        val missing = PostChainBuilder.PROGRAMS.filterNot { assetFor(assets, it).isFile }
        if (missing.isNotEmpty()) {
            gaps += Gap(
                overlay = overlay,
                feature = "video panels (${videos.joinToString { it.clip.id }})",
                missing = missing,
                consequence = "'type: video' elements draw nothing for these clients",
            )
            abandon()
            return
        }

        val source = baked.first()
        if (baked.size > 1) {
            gaps += Gap(
                overlay = overlay,
                feature = "baked video panels beyond the first (" +
                    baked.drop(1).joinToString { it.clip.id } + ")",
                missing = listOf("a clip id in the on-screen marker"),
                consequence = "only '${source.clip.id}' plays",
            )
        }

        val composed = PostChainBuilder.compose(
            blurChain = blurChain,
            video = PostChainBuilder.Video(
                width = source.clip.width,
                height = source.clip.height,
                frameCount = source.clip.frameCount,
                fps = source.clip.fps,
                startSeconds = 0.0,
                data = source.clip.sheetTexture,
                dataWidth = dev.shadr.core.video.MosaicFormat.SHEET_EDGE,
                dataHeight = VideoAssets.dataRows(source.mosaic!!),
                superColumns = source.mosaic.superColumns,
                superblocksPerFrame = source.mosaic.superblocksPerFrame,
                codebookBase = source.mosaic.codebookBase,
            ),
            stream = active,
        ) ?: return

        chainFile.parentFile.mkdirs()
        chainFile.writeText(composed)
    }

    private val frostedGlassOn: Boolean
        get() = environment[dev.shadr.core.shader.EnvironmentEffect.FROSTED_GLASS] == true

    private fun reportItemFragmentGap(root: File, overlay: PackOverlay) {
        if (!shaders.isEmpty) {
            ItemShaderAssets.writeBlankMarkers(root, overlay.directory, shaders)
            gaps += Gap(
                overlay = overlay,
                feature = "custom item shaders (${shaders.shaders.size}: " +
                    shaders.shaders.joinToString { it.id } + ")",
                missing = listOf("core/item.fsh"),
                consequence = "'type: shader' elements and world shader displays draw nothing " +
                    "for these clients",
            )
        }
        if (shapeSupport) {
            ShapeAssets.writeBlankParameters(root, overlay.directory)
            gaps += Gap(
                overlay = overlay,
                feature = "SDF shapes (--shapes)",
                missing = listOf("core/item.fsh"),
                consequence = "'block_sdf' draws nothing for these clients; the glyph path " +
                    "('block_rounded') still works",
            )
        }
    }

    private fun reportEnvironmentGap(
        assets: File,
        overlay: PackOverlay,
        effect: dev.shadr.core.shader.EnvironmentEffect,
    ) {
        val missing = effect.programs.filterNot { assetFor(assets, it).isFile }
        if (missing.isEmpty()) return
        gaps += Gap(
            overlay = overlay,
            feature = "world override '${effect.id}' (${effect.title})",
            missing = missing,
            consequence = "these clients see vanilla",
        )
    }

    private fun copyTree(from: File, assets: File) {
        for (file in from.walkTopDown()) {
            if (!file.isFile) continue
            if (file.name.startsWith(".") || file.name == "Thumbs.db") continue
            val target = assetFor(assets, file.relativeTo(from).invariantPath)
            target.parentFile.mkdirs()
            file.copyTo(target, overwrite = true)
        }
    }

    private val File.invariantPath: String get() = path.replace(File.separatorChar, '/')

    private fun assetFor(assets: File, rel: String): File =
        if (rel == POST_EFFECT_DIR || rel.startsWith("$POST_EFFECT_DIR/")) {
            File(assets, rel)
        } else {
            File(File(assets, "shaders"), rel)
        }

    private fun write(root: File, rel: String, text: String) {
        val file = File(root, rel)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    companion object {
        const val POST_EFFECT_DIR = "post_effect"
    }
}
