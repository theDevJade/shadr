/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import java.io.File

fun main(args: Array<String>) {
    val positional = args.filterNot { it.startsWith("--") }
    val shaderSrc = File(positional.getOrElse(0) { "shaders" })
    val outDir = File(positional.getOrElse(1) { "out/pack" })
    val fontDir = File(positional.getOrElse(2) { "assets/font" })
    val soundDir = File(positional.getOrElse(3) { "assets/shadr/sounds" })
    val contentsDir = File(positional.getOrElse(4) { "contents" })

    fun flag(name: String, fallback: Double): Double =
        args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')?.toDoubleOrNull() ?: fallback

    val videos = VideoLibrary(
        contentsDir = contentsDir,
        fps = flag("video-fps", 20.0),
        maxSeconds = flag("video-seconds", dev.shadr.pack.VideoImport.WHOLE_SOURCE),
        quality = flag("video-quality", 24.0).toInt(),
        maxHeight = flag("video-height", dev.shadr.core.video.VideoBudget.MAX_HEIGHT.toDouble()).toInt(),
    ).load()
    videos.issues.forEach { println("  video: $it") }

    val shaderLoader = dev.shadr.core.shader.ShaderLoader(File(shaderSrc, "items"))
    val shaders = shaderLoader.load()
    shaderLoader.issues.forEach { println("  shader: $it") }

    val environment = dev.shadr.core.shader.EnvironmentSettings(File(shaderSrc, "environment.properties"))

    val stream = if (args.contains("--stream") || args.contains("--stream-probe")) {
        dev.shadr.core.stream.StreamGeometry.DEFAULT.copy(
            slots = flag("stream-slots", dev.shadr.core.stream.StreamGeometry.DEFAULT.slots.toDouble()).toInt(),
            regionX = flag("stream-region-x", 0.0).toInt(),
            regionY = flag("stream-region-y", 0.0).toInt(),
            probe = args.contains("--stream-probe"),
            probeMode = flag("stream-probe-mode", 0.0).toInt(),
        )
    } else {
        null
    }

    val generator = PackGenerator(
        shaderSrc = shaderSrc,
        fontDir = fontDir,
        soundDir = soundDir.takeIf { it.isDirectory },
        shapeSupport = args.contains("--shapes"),
        shaders = shaders,
        environment = environment.all(),
        environmentParams = environment.allParams(),
        videos = videos.sources,
        stream = stream,
    )
    val root = generator.build(outDir)
    println("pack tree -> ${root.path}")
    println("shapes    -> " + if (args.contains("--shapes")) "enabled (overrides core/item)" else "off")
    println("shaders   -> ${shaders.shaders.size}" + if (shaders.isEmpty) "" else ": " + shaders.shaders.joinToString { it.id })
    println(
        "stream    -> " + (
            stream?.let {
                "${it.slots} slot(s), ${it.regionWidth}x${it.regionHeight} ingest at " +
                    "design ${it.regionX},${it.regionY}" + if (it.probe) ", probe on" else ""
            } ?: "off"
            ),
    )
    println("world     -> " + environment.all().filterValues { it }.keys.joinToString { it.id }.ifEmpty { "vanilla (no overrides)" })
    println(
        "videos    -> " + videos.sources.joinToString {
            "${it.clip.id} (${it.clip.width}x${it.clip.height}, " +
                "${it.clip.frameCount}f @ ${it.clip.fps}fps)"
        }.ifEmpty { "none" },
    )
    for (overlay in PackOverlay.entries) {
        println("  overlay ${overlay.directory}  formats ${overlay.minFormat}..${overlay.maxFormat}  (${overlay.label})")
    }

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
        println("  writing those programs needs that version's vanilla sources under shaders/overlays/")
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
