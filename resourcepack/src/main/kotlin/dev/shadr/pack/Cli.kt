/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack

import java.io.File

/**
 * `gen [shaders] [out] [assets/font] [assets/shadr/sounds]`: build the pack, then zip it.
 *
 * One pack covers every supported client; there is no target to choose. The plugin calls
 * [PackGenerator] and [PackBuilder] directly at runtime, so this exists for CI and for
 * inspecting the output by hand.
 *
 * Flags: `--no-zip`, `--strip-hud`, `--no-compress`, `--shapes`.
 *
 * `--shapes` opts into the SDF box path, which overrides `core/item` and redefines a
 * vanilla item. The default pack leaves both alone.
 */
fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("--") }
    val shaderSrc = File(positional.getOrElse(0) { "shaders" })
    val outDir = File(positional.getOrElse(1) { "out/pack" })
    val fontDir = File(positional.getOrElse(2) { "assets/font" })
    val soundDir = File(positional.getOrElse(3) { "assets/shadr/sounds" })

    // Custom item shaders live beside the core overrides they are compiled into, so they are
    // found from the same root rather than needing an argument of their own.
    val shaderLoader = dev.shadr.core.shader.ShaderLoader(File(shaderSrc, "items"))
    val shaders = shaderLoader.load()
    shaderLoader.issues.forEach { println("  shader: $it") }

    // The same file the editor writes, so `:resourcepack:run` and the plugin agree on which
    // environment overrides ship.
    val environment = dev.shadr.core.shader.EnvironmentSettings(File(shaderSrc, "environment.properties"))

    val generator = PackGenerator(
        shaderSrc = shaderSrc,
        fontDir = fontDir,
        soundDir = soundDir.takeIf { it.isDirectory },
        shapeSupport = args.contains("--shapes"),
        shaders = shaders,
        environment = environment.all(),
    )
    val root = generator.build(outDir)
    println("pack tree -> ${root.path}")
    println("shapes    -> " + if (args.contains("--shapes")) "enabled (overrides core/item)" else "off")
    println("shaders   -> ${shaders.shaders.size}" + if (shaders.isEmpty) "" else ": " + shaders.shaders.joinToString { it.id })
    println("world     -> " + environment.all().filterValues { it }.keys.joinToString { it.id }.ifEmpty { "vanilla (no overrides)" })
    for (overlay in PackOverlay.entries) {
        println("  overlay ${overlay.directory}  formats ${overlay.minFormat}..${overlay.maxFormat}  (${overlay.label})")
    }

    // Printed last and grouped by client, because this is the one part of the output an
    // operator has to act on: a pack can carry a shader that runs for 26.2 and for nobody
    // else, and until this existed nothing said so at any point in the pipeline.
    if (generator.gaps.isNotEmpty()) {
        println()
        println("not available on every client:")
        generator.gaps.groupBy { it.overlay }.forEach { (overlay, gaps) ->
            println("  ${overlay.label}  (${overlay.directory})")
            for (gap in gaps) {
                println("    - ${gap.feature}")
                println("      needs ${gap.missing.joinToString(", ")}; ${gap.consequence}")
            }
        }
        println("  writing those programs needs that version's vanilla sources; see SURVEY.md §4.2")
    }

    if (args.contains("--no-zip")) return
    val archive = PackBuilder.build(
        packRoot = root,
        outFile = File(outDir.parentFile ?: File("."), "shadr-pack.zip"),
        removeVanillaHud = args.contains("--strip-hud"),
        compressImages = !args.contains("--no-compress"),
    )
    println("archive   -> ${archive.file?.path}  ${archive.bytes.size / 1024} KiB")
    println("sha1      -> ${archive.sha1Hex}")
}
