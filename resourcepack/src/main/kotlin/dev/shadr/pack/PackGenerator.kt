/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Builds the shadr resource pack: **one** pack, every supported client version.
 *
 * Layout:
 * ```
 *   pack.mcmeta                      base format + overlay ranges
 *   pack.png
 *   assets/minecraft/font/ (json)    UI font, semibold, head_1..8   (shared)
 *   assets/minecraft/textures/font/  generated shape/corner/cursor art (shared)
 *   assets/minecraft/sounds/         UI sounds + dig/stone overrides  (shared)
 *   assets/minecraft/optifine/       loading-screen colours           (shared)
 *   shadr_26_2/assets/minecraft/shaders/...   per-version core overrides
 *   shadr_26_1/...
 *   ...
 * ```
 *
 * Only the shaders differ per version, because only the shaders track Mojang's renderer
 * rewrites. Everything a page actually draws with is version-independent.
 */
class PackGenerator(
    /** Repo `shaders/`; expects `overlays/<mc_dir>/{core,include}`. */
    private val shaderSrc: File,
    /** Repo `assets/font/`: the vendored TTFs. */
    private val fontDir: File,
    /** Repo `assets/shadr/sounds/`: UI sound effects. */
    private val soundDir: File? = null,
    /**
     * Emit the SDF shape path: item definitions, shape models, and the item fragment
     * program that reconstructs a rounded box.
     *
     * Off by default. The glyph path (`block_rounded`) is the one proven across clients and
     * mod setups; the shape path additionally overrides `core/item` and redefines a vanilla
     * item, which is exactly the surface Sodium reimplements. Leaving it opt-in means the
     * default pack is the conservative one, and turning it on is a deliberate act with a
     * known blast radius.
     */
    private val shapeSupport: Boolean = false,
    /**
     * Custom item shaders to bake in.
     *
     * Their presence forces the `core/item` override on regardless of [shapeSupport], because
     * that program is the only place a shader can run, since there is no per-item shader to
     * override. An empty registry writes an empty dispatch and changes nothing.
     */
    private val shaders: dev.shadr.core.shader.ShaderRegistry = dev.shadr.core.shader.ShaderRegistry.EMPTY,
    /**
     * Which vanilla programs to take over.
     *
     * Each one replaces something the client draws the whole world with, so an override that is
     * off is not shipped at all rather than shipped and bypassed. A pack that overrides
     * `core/sky` and then does nothing with it is still a pack that overrides `core/sky`.
     */
    private val environment: Map<dev.shadr.core.shader.EnvironmentEffect, Boolean> =
        dev.shadr.core.shader.EnvironmentEffect.entries.associateWith { false },
) {
    /** Files an operator override replaced, so the caller can report them rather than guess. */
    val overrides = mutableListOf<String>()

    /**
     * Features this build asked for that some clients will not get, and why.
     *
     * Populated by [writeShaderOverlays] and meant to be *printed*. shadr's per-version story
     * is one pack with overlays, and only the newest overlay carries an item *fragment*
     * program, so a pack can be built with three custom shaders in it and have those shaders
     * run for a fraction of the player base, with nothing anywhere saying so. That silence is
     * the bug; this list is the fix.
     */
    val gaps = mutableListOf<Gap>()

    /**
     * One capability an overlay cannot provide.
     *
     * Structured rather than a formatted string because both the CLI and the plugin report
     * these, and an operator reading a server log wants the same words as one reading build
     * output.
     */
    data class Gap(
        val overlay: PackOverlay,
        /** What was asked for, in the author's vocabulary: "custom item shaders", "sky". */
        val feature: String,
        /** Overlay-relative program paths that would have to exist for it to work. */
        val missing: List<String>,
        /** What a player on this overlay actually sees instead. */
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
        if (shapeSupport) ShapeAssets.writeAll(outRoot)
        ItemShaderAssets.writeAll(outRoot, shaders)
        // The celestial parameter textures replace vanilla art, so they only belong in the pack
        // when the program that reads them is there too. Shipping them alone would blank the sun.
        if (environment[dev.shadr.core.shader.EnvironmentEffect.CELESTIALS] == true) {
            CelestialAssets.writeAll(outRoot)
        }
        writeSounds(outRoot)
        writeOptifineColors(outRoot)
        writeShaderOverlays(outRoot)
        return outRoot
    }

    /**
     * `supported_formats` plus `min_format`/`max_format` are all declared: 26.2 rejects
     * metadata that omits the newer pair and silently falls back, which shows up as core
     * shader overrides intermittently not loading: invisible HUDs that look like culling.
     */
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

    /**
     * UI sounds live under the `minecraft:shadr` namespace, and `dig/stone1..4` are overridden with
     * the click sound. That second part is not decoration: clicks are registered by making
     * the player break an interaction stand-in, so the block-break sound fires on every
     * click whether we want it or not. Replacing it *is* the click sound.
     */
    private fun writeSounds(root: File) {
        val src = soundDir?.takeIf { it.isDirectory } ?: return
        val sfx = File(src, "sfx").takeIf { it.isDirectory } ?: src

        val copied = mutableListOf<String>()
        sfx.listFiles { f -> f.isFile && f.extension == "ogg" }?.sortedBy { it.name }?.forEach { file ->
            val name = file.nameWithoutExtension.removePrefix("ui_")
            file.copyTo(File(root, "assets/minecraft/sounds/shadr/$name.ogg"), overwrite = true)
            copied += name
        }
        if (copied.isEmpty()) return

        val click = File(sfx, "ui_click.ogg").takeIf { it.isFile }
        if (click != null) {
            for (index in 1..4) {
                click.copyTo(File(root, "assets/minecraft/sounds/dig/stone$index.ogg"), overwrite = true)
            }
        }

        val entries = copied.joinToString(",\n") { name ->
            """	"shadr.$name": { "category": "master", "sounds": [ "shadr/$name" ] }"""
        }
        write(root, "assets/minecraft/sounds.json", "{\n$entries\n}\n")
    }

    /** The loading screen would otherwise flash vanilla orange between pages. */
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

    /**
     * Copies each overlay's shader tree verbatim. These are Mojang's vanilla core shaders
     * with one `#moj_import <hud.glsl>` and one `make_hud()` call added. The only way to
     * override a core shader is to reproduce the whole file, so per-version copies are
     * inherent, not duplication we could factor away.
     */
    private fun writeShaderOverlays(root: File) {
        val overlaysDir = File(shaderSrc, "overlays")
        for (overlay in PackOverlay.entries) {
            val source = File(overlaysDir, overlay.sourceDirectory)
            if (!source.isDirectory) {
                error("missing shader sources for ${overlay.label}: ${source.path}")
            }
            val target = File(root, "${overlay.directory}/assets/minecraft/shaders")
            copyTree(source, target)

            // Operator overrides, applied last so they win.
            //
            // `custom/all` covers every client version, `custom/<version>` only one. Both are
            // plain shader trees copied over the top, which means overriding a program shadr
            // does not ship (clouds, the sky, particles) needs no support here at all: drop
            // the file in and it is in the pack. That is the whole feature.
            for (dir in listOf("all", overlay.sourceDirectory)) {
                val custom = File(File(shaderSrc, "custom"), dir)
                if (!custom.isDirectory) continue
                custom.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(custom).path
                    overrides += "${overlay.directory}/$rel"
                }
                copyTree(custom, target)
            }

            // The generated dispatch. Written even when empty, because core/item.fsh imports
            // it unconditionally and a dangling import fails the whole program, which the
            // client reports by silently ignoring the override.
            //
            // Checked *after* the custom/ copy on purpose: an operator who writes their own
            // `core/item.fsh` for an older client is exactly the person this gap is waiting
            // for, and their file should count.
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

            // Environment overrides that are switched off are removed from the overlay. This
            // runs after the custom/ copy, so an operator's own file for a disabled effect goes
            // too, since turning the effect off means the program is vanilla, whoever supplied it.
            for ((effect, on) in environment) {
                if (on) {
                    reportEnvironmentGap(target, overlay, effect)
                    continue
                }
                effect.programs.forEach { File(target, it).delete() }
            }

            // Without shape support *and* without shaders the pack must not override
            // `core/item` at all. Shipping an unused override still replaces a vanilla program
            // for every item the client draws, so removing it is what makes the default pack
            // genuinely conservative.
            if (!shapeSupport && shaders.isEmpty) {
                itemProgram.delete()
                File(target, "include/hud_shape.glsl").delete()
                File(target, "include/shadr_shaders.glsl").delete()
            } else if (!shapeSupport) {
                // The shape half is still off; only its include goes, and item.fsh stays for
                // the shader branch. hud_shape.glsl has to remain, because item.fsh imports it.
                File(target, "include/hud_shape.glsl").takeIf { !it.isFile }?.let { }
            }
        }
    }

    /**
     * Records, and *mitigates*, an overlay with no item fragment program.
     *
     * Only `mc_26_2` ships a `core/item.fsh`; the older overlays would need one written against
     * that version's vanilla sources. Until they have one, both features that ride the item
     * program are unavailable to those clients, and the mitigation matters as much as the
     * message: left alone the parameter sprites are drawn *as sprites* by the vanilla program,
     * so the element does not degrade to nothing, it degrades to a coloured square. Blanking
     * the sprites inside this overlay only makes it degrade to nothing.
     */
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

    /**
     * Records an environment override switched on that this overlay cannot carry.
     *
     * The world overrides are 26.2-only for the same reason as the item program: each is a
     * verbatim copy of *that version's* vanilla shader with shadr's changes spliced in, and a
     * copy from the wrong version does not compile. A core shader that does not compile is
     * ignored in silence, so without this the operator's only evidence is a sky that looks
     * vanilla on some clients and not others.
     */
    private fun reportEnvironmentGap(
        target: File,
        overlay: PackOverlay,
        effect: dev.shadr.core.shader.EnvironmentEffect,
    ) {
        val missing = effect.programs.filterNot { File(target, it).isFile }
        if (missing.isEmpty()) return
        gaps += Gap(
            overlay = overlay,
            feature = "world override '${effect.id}' (${effect.title})",
            missing = missing,
            consequence = "these clients see vanilla",
        )
    }

    /**
     * Copies a shader tree, skipping the junk a filesystem leaves lying around.
     *
     * macOS writes `.DS_Store` into every directory it has been browsed with, and a plain
     * `copyRecursively` puts them straight into the pack, where the client reports
     * `Invalid path in datapack: minecraft:shaders/.DS_Store` on every load. Harmless, but it is
     * noise in exactly the log an operator reads when something is wrong.
     */
    private fun copyTree(from: File, to: File) {
        for (file in from.walkTopDown()) {
            if (!file.isFile) continue
            if (file.name.startsWith(".") || file.name == "Thumbs.db") continue
            val target = File(to, file.relativeTo(from).path)
            target.parentFile.mkdirs()
            file.copyTo(target, overwrite = true)
        }
    }

    private fun write(root: File, rel: String, text: String) {
        val file = File(root, rel)
        file.parentFile.mkdirs()
        file.writeText(text)
    }
}
