/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.stream

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class StreamVideoSource @JvmOverloads constructor(
    private val source: File,
    private val width: Int,
    private val height: Int,
    private val fps: Double,
    private val loop: Boolean = true,
    private val ffmpeg: String = "ffmpeg",
    private val realTime: Boolean = true,
) : AutoCloseable {

    private val queue = ArrayBlockingQueue<IntArray>(QUEUE_DEPTH)

    private val recycled = ArrayBlockingQueue<IntArray>(QUEUE_DEPTH + 2)

    @Volatile
    private var failure: String? = null

    @Volatile
    private var running = true

    @Volatile
    private var delivered = 0L

    private val process: Process

    init {
        require(width > 0 && height > 0) { "frame size must be positive" }
        require(fps > 0.0) { "fps must be positive" }
        if (!source.isFile) throw IOException("no such video: ${source.path}")

        val command = buildList {
            add(ffmpeg)
            add("-v"); add("error")
            if (loop) { add("-stream_loop"); add("-1") }
            if (realTime) add("-re")
            add("-i"); add(source.path)
            add("-vf"); add("fps=$fps,scale=$width:$height:flags=bilinear")
            add("-f"); add("rawvideo"); add("-pix_fmt"); add("rgb24")
            add("-")
        }

        process = try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            throw IOException("could not run ffmpeg. Is it on PATH? (${e.message})")
        }

        thread(isDaemon = true, name = "shadr-stream-video-errors") {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank() && failure == null) failure = line.trim()
                }
            }
        }

        thread(isDaemon = true, name = "shadr-stream-video") {
            val bytes = ByteArray(width * height * 3)
            val input = DataInputStream(process.inputStream.buffered(bytes.size.coerceAtLeast(1 shl 16)))
            try {
                while (running) {
                    input.readFully(bytes)
                    val frame = recycled.poll() ?: IntArray(width * height)
                    var at = 0
                    for (i in frame.indices) {
                        frame[i] = ((bytes[at].toInt() and 0xFF) shl 16) or
                            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                            (bytes[at + 2].toInt() and 0xFF)
                        at += 3
                    }
                    if (realTime) {
                        while (running && !queue.offer(frame, 200, TimeUnit.MILLISECONDS)) {
                            queue.poll()?.let { recycled.offer(it) }
                        }
                    } else {
                        while (running && !queue.offer(frame, 500, TimeUnit.MILLISECONDS)) {
                            if (!running) break
                        }
                    }
                }
            } catch (_: java.io.EOFException) {
                if (failure == null && delivered == 0L) failure = "ffmpeg produced no frames"
            } catch (_: IOException) {
                // the process was closed underneath us, which is how stop() ends this thread
            } finally {
                running = false
            }
        }
    }

    fun poll(): IntArray? = queue.poll()?.also { delivered++ }

    fun recycle(frame: IntArray) {
        if (frame.size == width * height) recycled.offer(frame)
    }

    fun failure(): String? = failure

    fun frames(): Long = delivered

    fun isRunning(): Boolean = running

    override fun close() {
        running = false
        process.destroy()
        queue.clear()
    }

    private companion object {
        const val QUEUE_DEPTH = 3
    }
}
