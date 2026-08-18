/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.video.MosaicEncoder
import dev.shadr.core.video.MosaicFormat
import dev.shadr.core.video.VideoBudget
import dev.shadr.core.video.VideoClip
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class VideoImport(
    private val ffmpeg: String = "ffmpeg",
    private val ffprobe: String = "ffprobe",
    private val report: (String) -> Unit = ::println,
) {

    data class Request(
        val id: String,
        val source: File,
        val fps: Double = 30.0,
        /** Seconds to bake. */
        val maxSeconds: Double = WHOLE_SOURCE,
        val maxHeight: Int = VideoBudget.MAX_HEIGHT,
        val quality: Int = MosaicEncoder.Options().quality,
        val gopFrames: Int = MosaicEncoder.Options().gopFrames,
    )

    data class Result(val clip: VideoAssets.Source?, val issues: List<String>)

    fun import(request: Request): Result {
        VideoClip.validateId(request.id)?.let { return Result(null, listOf("${request.id}: $it")) }
        if (!request.source.exists()) {
            return Result(null, listOf("${request.id}: no such source ${request.source.path}"))
        }

        val issues = mutableListOf<String>()
        val geometry = geometryOf(request, issues) ?: return Result(null, issues)

        var height = even(minOf(geometry.height, request.maxHeight))
        var attempts = 0
        while (height >= VideoBudget.MIN_HEIGHT && attempts < MAX_ATTEMPTS) {
            val width = even((height * geometry.aspect).roundToInt()).coerceAtLeast(2)
            attempts++

            report(
                "${request.id}: encoding ${geometry.frames} frames at ${width}x$height" +
                    if (attempts > 1) " (attempt $attempts)" else "",
            )
            val started = System.nanoTime()
            var lastReport = started
            var reached = 0

            val options = MosaicEncoder.Options(
                quality = request.quality,
                gopFrames = request.gopFrames,
                targetTexelsPerFrame = targetTexelsPerFrame(width, height, geometry.frames),
                onProgress = { progress ->
                    reached = progress.frame + 1
                    val now = System.nanoTime()
                    if (now - lastReport >= PROGRESS_INTERVAL_NS) {
                        lastReport = now
                        val elapsed = (now - started) / 1e9
                        val left = if (progress.fraction > 0) {
                            elapsed / progress.fraction - elapsed
                        } else {
                            0.0
                        }
                        report(
                            "  ${request.id}: ${"%.0f".format(progress.fraction * 100)}% " +
                                "(frame ${progress.frame + 1}/${progress.frameCount}" +
                                (if (progress.passes > 1) ", pass ${progress.pass}" else "") +
                                "), ${"%.0f".format(progress.fillFraction * 100)}% of a sheet, " +
                                "${"%.0f".format(left)}s left",
                        )
                    }
                },
            )

            val stats = MosaicEncoder.Stats()
            val feeds = mutableListOf<Feed>()
            val mosaic = try {
                MosaicEncoder.encode(
                    frameCount = geometry.frames,
                    width = width,
                    height = height,
                    fps = request.fps,
                    options = options,
                    stats = stats,
                ) { _ ->
                    val feed = feed(request, geometry, width, height)
                    feeds += feed
                    { _: Int -> feed.next() }
                }
            } catch (e: IOException) {
                return Result(null, issues + "${request.id}: ${e.message}")
            } finally {
                feeds.forEach { it.close() }
            }

            if (mosaic != null) {
                if (height < geometry.height) {
                    issues += "${request.id}: scaled to ${width}x$height to fit the sheet"
                }
                val seconds = mosaic.frameCount / request.fps
                val fill = stats.fillFraction.coerceAtLeast(1e-9)
                issues += "${request.id}: ${mosaic.frameCount} frames, " +
                    "${mosaic.bytes / 1024} KiB, " +
                    "${"%.0f".format(fill * 100)}% of a sheet " +
                    "(${"%.1f".format(seconds / fill)}s per sheet) ($stats)"
                stats.report?.let { issues += "${request.id}: $it" }

                val audio = if (request.source.isDirectory) {
                    null
                } else {
                    extractAudio(request, mosaic.frameCount / request.fps, issues)
                }
                if (audio != null) {
                    issues += "${request.id}: audio track ${audio.size / 1024} KiB"
                }
                return Result(
                    VideoAssets.Source(
                        clip = VideoClip(
                            id = request.id,
                            width = width,
                            height = height,
                            frameCount = mosaic.frameCount,
                            fps = request.fps,
                        ),
                        mosaic = mosaic,
                        audio = audio,
                    ),
                    issues,
                )
            }

            val shrink = if (reached in 1 until geometry.frames) {
                kotlin.math.sqrt(reached.toDouble() / geometry.frames) * FIT_MARGIN
            } else {
                STEP_DOWN
            }
            report(
                "${request.id}: ${width}x$height fills the sheet by frame $reached " +
                    "of ${geometry.frames}, retrying smaller",
            )
            height = even((height * shrink.coerceAtMost(STEP_DOWN)).roundToInt())
        }

        return Result(null, issues + tooLarge(request, geometry.frames))
    }

    private class Geometry(val width: Int, val height: Int, val frames: Int) {
        val aspect: Double get() = width.toDouble() / height
    }

    private fun targetTexelsPerFrame(width: Int, height: Int, frames: Int): Int {
        val plane = MosaicFormat.superblocksPerFrame(width, height).toLong() * frames
        val budget = MosaicFormat.MAX_TEXELS.toLong() * FILL_TARGET_PERCENT / 100 -
            plane - MosaicFormat.CODEBOOK_TEXELS
        if (budget <= 0) return 0
        return (budget / frames).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun geometryOf(request: Request, issues: MutableList<String>): Geometry? {
        val wanted = if (request.maxSeconds >= WHOLE_SOURCE) {
            Int.MAX_VALUE
        } else {
            VideoBudget.frameCount(request.maxSeconds, request.fps)
        }

        if (request.source.isDirectory) {
            val files = stills(request.source)
            if (files.isEmpty()) {
                issues += "${request.id}: no frames in ${request.source.path}"
                return null
            }
            val first = runCatching { ImageIO.read(files.first()) }.getOrNull()
            if (first == null) {
                issues += "${request.id}: unreadable frame ${files.first().name}"
                return null
            }
            if (files.size > wanted) {
                issues += "${request.id}: baked $wanted of ${files.size} frames " +
                    "(maxSeconds ${request.maxSeconds})"
            }
            return Geometry(first.width, first.height, minOf(wanted, files.size))
        }

        val probe = probe(request.source)
        if (probe == null) {
            issues += "${request.id}: could not probe ${request.source.name}. Is ffprobe on PATH?"
            return null
        }
        if (probe.seconds > request.maxSeconds) {
            issues += "${request.id}: source runs ${"%.1f".format(probe.seconds)}s, " +
                "baked the first ${"%.1f".format(request.maxSeconds)}s"
        }
        val seconds = when {
            probe.seconds <= 0.0 -> request.maxSeconds
            else -> minOf(request.maxSeconds, probe.seconds)
        }
        return Geometry(probe.width, probe.height, VideoBudget.frameCount(seconds, request.fps))
    }

    private fun stills(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in STILL_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

    private fun tooLarge(request: Request, frames: Int): String =
        "${request.id}: $frames frames will not fit one sheet at any resolution. " +
            "Lower fps or maxSeconds, or raise quality (a larger number encodes smaller)."

    private fun extractAudio(request: Request, seconds: Double, issues: MutableList<String>): ByteArray? {
        if (probe(request.source)?.let { true } != true) return null
        if (!hasAudioStream(request.source)) return null

        var lastError = "ffmpeg produced nothing"
        for (encoder in VORBIS_ENCODERS) {
            val out = File.createTempFile("shadr-audio", ".ogg").apply { delete() }
            try {
                val command = buildList {
                    addAll(listOf(ffmpeg, "-v", "error", "-y", "-i", request.source.path))
                    addAll(listOf("-t", seconds.toString(), "-vn", "-ac", "2", "-ar", "44100"))
                    addAll(encoder)
                    add(out.path)
                }
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() == 0 && out.isFile && out.length() > 0L) {
                    return out.readBytes()
                }
                lastError = output.trim().lines().firstOrNull() ?: lastError
            } catch (e: IOException) {
                lastError = e.message ?: "ffmpeg could not be run"
            } finally {
                out.delete()
            }
        }

        issues += "${request.id}: no audio baked ($lastError)"
        return null
    }

    private fun hasAudioStream(source: File): Boolean {
        val out = run(
            listOf(
                ffprobe, "-v", "error",
                "-select_streams", "a",
                "-show_entries", "stream=codec_type",
                "-of", "default=noprint_wrappers=1:nokey=1",
                source.path,
            ),
        ) ?: return false
        return out.contains("audio")
    }

    private interface Feed : AutoCloseable {
        fun next(): IntArray?
    }

    private fun feed(request: Request, geometry: Geometry, width: Int, height: Int): Feed =
        if (request.source.isDirectory) {
            StillFeed(stills(request.source), geometry.frames, width, height)
        } else {
            FfmpegFeed(request, geometry.frames, width, height)
        }

    private class StillFeed(
        private val files: List<File>,
        private val limit: Int,
        private val width: Int,
        private val height: Int,
    ) : Feed {
        private var at = 0

        override fun next(): IntArray? {
            if (at >= limit || at >= files.size) return null
            val image = runCatching { ImageIO.read(files[at++]) }.getOrNull() ?: return null
            return pixels(scaled(image, width, height))
        }

        override fun close() = Unit
    }

    private inner class FfmpegFeed(
        request: Request,
        private val limit: Int,
        private val width: Int,
        private val height: Int,
    ) : Feed {
        private val process: Process
        private val input: DataInputStream
        private val errors = StringBuilder()
        private val drain: Thread
        private val buffer = ByteArray(width * height * 3)
        private var at = 0

        init {
            val seconds = limit / request.fps
            val command = listOf(
                ffmpeg, "-v", "error",
                "-i", request.source.path,
                "-t", seconds.toString(),
                "-vf", "fps=${request.fps},scale=$width:$height:flags=lanczos",
                "-f", "rawvideo", "-pix_fmt", "rgb24",
                "-",
            )
            process = try {
                ProcessBuilder(command).start()
            } catch (e: IOException) {
                throw IOException("could not run ffmpeg. Is it on PATH? (${e.message})")
            }
            drain = thread(isDaemon = true) {
                runCatching { process.errorStream.bufferedReader().forEachLine { errors.appendLine(it) } }
            }
            input = DataInputStream(process.inputStream.buffered(buffer.size.coerceAtLeast(1 shl 16)))
        }

        override fun next(): IntArray? {
            if (at >= limit) return null
            try {
                input.readFully(buffer)
            } catch (_: java.io.EOFException) {
                if (at == 0 && errors.isNotBlank()) {
                    throw IOException(errors.trim().lines().first())
                }
                return null
            }
            at++
            val out = IntArray(width * height)
            var read = 0
            for (i in out.indices) {
                out[i] = ((buffer[read].toInt() and 0xFF) shl 16) or
                    ((buffer[read + 1].toInt() and 0xFF) shl 8) or
                    (buffer[read + 2].toInt() and 0xFF)
                read += 3
            }
            return out
        }

        override fun close() {
            runCatching { process.inputStream.close() }
            runCatching { process.destroy() }
            runCatching { process.waitFor() }
            runCatching { drain.join(DRAIN_TIMEOUT_MS) }
        }
    }

    data class Probe(val width: Int, val height: Int, val seconds: Double)

    fun probe(source: File): Probe? {
        val out = run(
            listOf(
                ffprobe, "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height:format=duration",
                "-of", "default=noprint_wrappers=1",
                source.path,
            ),
        ) ?: return null

        val fields = out.lineSequence()
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
            .associate { it[0].trim() to it[1].trim() }

        val width = fields["width"]?.toIntOrNull() ?: return null
        val height = fields["height"]?.toIntOrNull() ?: return null
        return Probe(width, height, fields["duration"]?.toDoubleOrNull() ?: 0.0)
    }

    private fun run(command: List<String>): String? = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) text else null
    } catch (_: IOException) {
        null
    }

    companion object {
        val STILL_EXTENSIONS = setOf("png", "jpg", "jpeg")

        val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mov", "mkv", "gif", "avi")

        private const val DRAIN_TIMEOUT_MS = 2_000L

        private const val STEP_DOWN = 0.8

        private const val FIT_MARGIN = 0.92

        private const val PROGRESS_INTERVAL_NS = 5_000_000_000L

        private const val MAX_ATTEMPTS = 8

        private const val FILL_TARGET_PERCENT = 96L

        private val VORBIS_ENCODERS = listOf(
            listOf("-c:a", "libvorbis", "-q:a", "4"),
            listOf("-c:a", "vorbis", "-strict", "-2", "-q:a", "4"),
        )

        const val WHOLE_SOURCE = Double.MAX_VALUE

        private fun even(value: Int): Int = value - (value % 2)

        private fun scaled(image: BufferedImage, width: Int, height: Int): BufferedImage {
            if (image.width == width && image.height == height &&
                image.type == BufferedImage.TYPE_INT_RGB
            ) {
                return image
            }
            val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            try {
                g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR,
                )
                g.drawImage(image, 0, 0, width, height, null)
            } finally {
                g.dispose()
            }
            return out
        }

        private fun pixels(image: BufferedImage): IntArray {
            val out = IntArray(image.width * image.height)
            image.getRGB(0, 0, image.width, image.height, out, 0, image.width)
            for (i in out.indices) out[i] = out[i] and 0xFFFFFF
            return out
        }
    }
}

class VideoLibrary @JvmOverloads constructor(
    private val contentsDir: File,
    private val importer: VideoImport = VideoImport(),
    private val fps: Double = 20.0,
    private val maxSeconds: Double = VideoImport.WHOLE_SOURCE,
    private val quality: Int = MosaicEncoder.Options().quality,
    private val maxHeight: Int = VideoBudget.MAX_HEIGHT,
) {
    data class Result(val sources: List<VideoAssets.Source>, val issues: List<String>)

    fun load(): Result {
        val dir = File(contentsDir, FOLDER).takeIf { it.isDirectory }
            ?: return Result(emptyList(), emptyList())

        val sources = mutableListOf<VideoAssets.Source>()
        val issues = mutableListOf<String>()

        val entries = dir.listFiles()
            ?.filter { it.isDirectory || it.extension.lowercase() in VideoImport.VIDEO_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

        for (entry in entries) {
            val result = importer.import(
                VideoImport.Request(
                    id = entry.nameWithoutExtension.lowercase(),
                    source = entry,
                    fps = fps,
                    maxSeconds = maxSeconds,
                    maxHeight = maxHeight,
                    quality = quality,
                ),
            )
            issues += result.issues
            result.clip?.let { sources += it }
        }
        return Result(sources, issues)
    }

    companion object {
        const val FOLDER = "videos"
    }
}
