/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoImportTest {

    private fun temp() = createTempDirectory("shadr-import").toFile()

    private fun stills(dir: File, count: Int, width: Int = 320, height: Int = 180): File {
        dir.mkdirs()
        for (i in 0 until count) {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = Color(i * 8 % 256, 40, 200)
            g.fillRect(0, 0, width, height)
            g.dispose()
            ImageIO.write(image, "png", File(dir, "frame_%04d.png".format(i)))
        }
        return dir
    }

    private fun ffmpegPresent(): Boolean =
        runCatching {
            ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)

    @Test
    fun `a folder of stills becomes a clip`() {
        val source = stills(File(temp(), "intro"), count = 24)
        val result = VideoImport().import(
            VideoImport.Request(id = "intro", source = source, fps = 24.0, maxSeconds = 10.0),
        )

        val clip = assertNotNull(result.clip, "import failed: ${result.issues}").clip
        assertEquals("intro", clip.id)
        assertEquals(24, clip.frameCount)
        assertEquals(24.0, clip.fps)
        assertEquals(1.0, clip.duration)
        assertTrue(result.clip!!.mosaic!!.texelCount > 0, "the clip encoded to nothing")
    }

    @Test
    fun `maxSeconds truncates instead of overrunning the sheet`() {
        val source = stills(File(temp(), "long"), count = 120)
        val result = VideoImport().import(
            VideoImport.Request(id = "long", source = source, fps = 30.0, maxSeconds = 1.0),
        )

        val imported = assertNotNull(result.clip, "import failed: ${result.issues}")
        assertEquals(30, imported.clip.frameCount, "maxSeconds did not bound the clip")
        assertTrue(
            result.issues.any { it.contains("120") },
            "dropping 90 frames was not reported: ${result.issues}",
        )
    }

    @Test
    fun `the plan the importer picks is one the sheet can hold`() {
        val source = stills(File(temp(), "big"), count = 40, width = 1920, height = 1080)
        val result = VideoImport().import(
            VideoImport.Request(id = "big", source = source, fps = 60.0, maxSeconds = 10.0),
        )
        val imported = assertNotNull(result.clip, "import failed: ${result.issues}")
        val mosaic = assertNotNull(imported.mosaic, "a baked import must carry a mosaic")

        assertEquals(imported.clip.width, mosaic.width)
        assertEquals(imported.clip.height, mosaic.height)
        assertTrue(
            VideoAssets.dataRows(mosaic) <= dev.shadr.core.video.MosaicFormat.SHEET_EDGE,
            "the bitstream does not fit the data texture",
        )
        assertTrue(
            mosaic.compression > 1.0,
            "encoding made the clip larger than raw frames",
        )
    }

    @Test
    fun `a missing or empty source is reported rather than half imported`() {
        val gone = VideoImport().import(
            VideoImport.Request(id = "gone", source = File(temp(), "nope")),
        )
        assertNull(gone.clip)
        assertTrue(gone.issues.isNotEmpty())

        val empty = VideoImport().import(
            VideoImport.Request(id = "empty", source = File(temp(), "empty").apply { mkdirs() }),
        )
        assertNull(empty.clip)
        assertTrue(empty.issues.any { it.contains("no frames") }, empty.issues.toString())
    }

    @Test
    fun `an id the pack cannot name is refused`() {
        val result = VideoImport().import(
            VideoImport.Request(id = "Not Valid!", source = stills(File(temp(), "x"), 2)),
        )
        assertNull(result.clip)
        assertTrue(result.issues.single().contains("id must match"))
    }

    @Test
    fun `a real video file decodes through ffmpeg`() {
        if (!ffmpegPresent()) return

        val dir = temp()
        val source = File(dir, "clip.mp4")
        val made = ProcessBuilder(
            "ffmpeg", "-v", "error", "-y",
            "-f", "lavfi", "-i", "testsrc=size=320x180:rate=30:duration=2",
            "-pix_fmt", "yuv420p", source.path,
        ).redirectErrorStream(true).start().waitFor()
        assertEquals(0, made, "could not synthesise a test clip")

        val result = VideoImport().import(
            VideoImport.Request(id = "clip", source = source, fps = 30.0, maxSeconds = 1.0),
        )

        val imported = assertNotNull(result.clip, "ffmpeg import failed: ${result.issues}")
        assertEquals(30, imported.clip.frameCount, "expected one second at 30fps")
        assertEquals(320, imported.clip.width)
        assertEquals(180, imported.clip.height)

        // Decode the bitstream back and check it is moving footage, not one frame repeated.
        val decoded = dev.shadr.core.video.MosaicReferenceDecoder.decode(assertNotNull(imported.mosaic))
        assertEquals(30, decoded.size)
        assertTrue(
            decoded.map { it.contentHashCode() }.distinct().size > 1,
            "every frame decoded identically, so this is not moving footage",
        )
    }

    @Test
    fun `probing a real file reports its true size`() {
        if (!ffmpegPresent()) return

        val source = File(temp(), "probe.mp4")
        ProcessBuilder(
            "ffmpeg", "-v", "error", "-y",
            "-f", "lavfi", "-i", "testsrc=size=640x360:rate=25:duration=1",
            "-pix_fmt", "yuv420p", source.path,
        ).redirectErrorStream(true).start().waitFor()

        val probe = assertNotNull(VideoImport().probe(source), "probe returned nothing")
        assertEquals(640, probe.width)
        assertEquals(360, probe.height)
        assertTrue(probe.seconds > 0.5, "duration came back as ${probe.seconds}")
    }

    @Test
    fun `a library picks up everything an author dropped in`() {
        val contents = temp()
        stills(File(contents, "${VideoLibrary.FOLDER}/alpha"), count = 6)
        stills(File(contents, "${VideoLibrary.FOLDER}/beta"), count = 6)
        File(contents, "${VideoLibrary.FOLDER}/notes.txt").writeText("ignored")

        val result = VideoLibrary(contents, fps = 6.0, maxSeconds = 10.0).load()
        assertEquals(listOf("alpha", "beta"), result.sources.map { it.clip.id })
    }

    @Test
    fun `a clip that will not fit at any resolution is refused, not truncated`() {
        val source = stills(File(temp(), "vast"), count = 8, width = 320, height = 180)
        val result = VideoImport().import(
            VideoImport.Request(
                id = "vast",
                source = source,
                fps = 30.0,
                maxSeconds = 10.0,
                quality = 0,
                gopFrames = 1,
            ),
        )
        assertTrue(
            result.clip != null || result.issues.any { it.contains("will not fit") },
            "an unencodable clip neither encoded nor reported why: ${result.issues}",
        )
    }

    @Test
    fun `no videos folder is not an error`() {
        val result = VideoLibrary(temp()).load()
        assertTrue(result.sources.isEmpty())
        assertTrue(result.issues.isEmpty())
    }
}
