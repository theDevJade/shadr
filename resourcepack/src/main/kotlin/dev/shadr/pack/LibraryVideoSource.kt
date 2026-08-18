/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import dev.shadr.core.editor.VideoEntry
import dev.shadr.core.editor.VideoSource
import java.io.File
import java.io.IOException
import java.util.Base64

class LibraryVideoSource @JvmOverloads constructor(
    private val contentsDir: File,
    private val ffmpeg: String = "ffmpeg",
    private val importer: VideoImport = VideoImport(),
) : VideoSource {
    private val videosDir: File get() = File(contentsDir, VideoLibrary.FOLDER)

    private val cacheDir: File get() = File(contentsDir, THUMBNAIL_CACHE)

    override fun write(name: String, extension: String, bytes: ByteArray): String? {
        val target = File(videosDir, "$name.${extension.lowercase()}")
        return runCatching {
            videosDir.mkdirs()
            clipsNamed(name).forEach { it.delete() }
            target.writeBytes(bytes)
            File(cacheDir, "$name.png").delete()
            null
        }.getOrElse { "could not write ${target.name}: ${it.message}" }
    }

    override fun delete(name: String): Boolean {
        if (VideoSource.validateName(name) != null) return false
        val clips = clipsNamed(name)
        if (clips.isEmpty()) return false
        File(cacheDir, "$name.png").delete()
        return clips.all { it.delete() }
    }

    override fun list(): List<VideoEntry> = sources().map { source ->
        val probe = importer.probe(source)
        val name = source.nameWithoutExtension.lowercase()
        VideoEntry(
            name = name,
            width = probe?.width ?: 0,
            height = probe?.height ?: 0,
            seconds = probe?.seconds ?: 0.0,
            thumbnail = thumbnail(name, source)?.let { Base64.getEncoder().encodeToString(it) }.orEmpty(),
            issue = if (probe == null) "could not read $name. Is ffprobe on PATH?" else null,
        )
    }

    private fun sources(): List<File> = videosDir.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in VideoSource.EXTENSIONS }
        ?.sortedBy { it.name.lowercase() }
        .orEmpty()

    private fun clipsNamed(name: String): List<File> =
        sources().filter { it.nameWithoutExtension.lowercase() == name }

    private fun thumbnail(name: String, source: File): ByteArray? {
        val cached = File(cacheDir, "$name.png")
        if (cached.isFile && cached.lastModified() >= source.lastModified()) {
            return runCatching { cached.readBytes() }.getOrNull()
        }

        cacheDir.mkdirs()
        val command = listOf(
            ffmpeg, "-v", "error", "-y",
            "-i", source.path,
            "-frames:v", "1",
            "-vf", "scale=$THUMBNAIL_WIDTH:-2",
            cached.path,
        )
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0 || !cached.isFile) null else cached.readBytes()
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val THUMBNAIL_CACHE = ".video-thumbnails"
        const val THUMBNAIL_WIDTH = 160
    }
}
