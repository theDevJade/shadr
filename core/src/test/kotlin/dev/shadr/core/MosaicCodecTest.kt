/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.video.MosaicClip
import dev.shadr.core.video.MosaicEncoder
import dev.shadr.core.video.MosaicFormat
import dev.shadr.core.video.MosaicReferenceDecoder
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MosaicCodecTest {

    private val width = 128
    private val height = 96

    private fun rgb(r: Int, g: Int, b: Int) =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun scene(offsetX: Int, offsetY: Int, tint: Int): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val u = ((x + offsetX) * 2) and 0xFF
                val v = ((y + offsetY) * 2) and 0xFF
                pixels[y * width + x] = rgb(u, v, tint)
            }
        }
        return pixels
    }

    private fun psnr(source: List<IntArray>, decoded: List<IntArray>): Double {
        var total = 0.0
        var count = 0L
        for (f in source.indices) {
            val a = source[f]
            val b = decoded[f]
            for (i in a.indices) {
                for (shift in intArrayOf(16, 8, 0)) {
                    val d = ((a[i] ushr shift) and 0xFF) - ((b[i] ushr shift) and 0xFF)
                    total += (d * d).toDouble()
                    count++
                }
            }
        }
        val mse = total / count
        return if (mse <= 0.0) 99.0 else 10.0 * log10(255.0 * 255.0 / mse)
    }

    private fun encode(
        frames: List<IntArray>,
        options: MosaicEncoder.Options = MosaicEncoder.Options(),
        stats: MosaicEncoder.Stats? = null,
    ): MosaicClip = assertNotNull(
        MosaicEncoder.encode(frames, width, height, 30.0, options, stats),
        "the clip did not fit a sheet",
    )

    @Test
    fun `a clip survives the round trip at a quality worth shipping`() {
        val frames = (0 until 20).map { scene(it, it / 2, 120) }
        val clip = encode(frames)
        val decoded = MosaicReferenceDecoder.decode(clip)

        assertEquals(frames.size, decoded.size)
        val quality = psnr(frames, decoded)
        assertTrue(quality > 30.0, "round trip came back at ${quality.roundToInt()} dB")
    }

    @Test
    fun `a still shot costs almost nothing`() {
        val still = scene(0, 0, 90)
        val stats = MosaicEncoder.Stats()
        val clip = encode(List(30) { still.copyOf() }, MosaicEncoder.Options(gopFrames = 30), stats)

        assertTrue(stats.superSkip > 0, "nothing was skipped at superblock level: $stats")
        assertTrue(
            clip.compression > 50.0,
            "a still shot only compressed ${clip.compression.roundToInt()}x",
        )

        // Frames after the keyframe must be pixel-identical.
        val decoded = MosaicReferenceDecoder.decode(clip)
        for (f in 2 until decoded.size) {
            assertTrue(
                decoded[f].contentEquals(decoded[1]),
                "frame $f drifted from frame 1 despite nothing moving",
            )
        }
    }

    @Test
    fun `a pan is carried by motion vectors rather than re-encoded`() {
        val stats = MosaicEncoder.Stats()
        val frames = (0 until 16).map { scene(it * 3, 0, 100) }
        encode(frames, MosaicEncoder.Options(gopFrames = 16), stats)

        assertTrue(stats.motion > 0, "a pure horizontal pan used no motion vectors: $stats")
    }

    @Test
    fun `a fade is carried by delta rather than re-encoded`() {
        val stats = MosaicEncoder.Stats()
        val frames = (0 until 16).map { scene(0, 0, 60 + it * 6) }
        encode(frames, MosaicEncoder.Options(gopFrames = 16), stats)

        assertTrue(stats.delta > 0, "a slow fade used no delta blocks: $stats")
    }

    @Test
    fun `new content falls through to intra`() {
        val stats = MosaicEncoder.Stats()
        val frames = listOf(scene(0, 0, 40), scene(9999, 4444, 200))
        encode(frames, MosaicEncoder.Options(gopFrames = 1000), stats)

        assertTrue(stats.intra > 0, "a hard cut used no intra blocks: $stats")
    }

    @Test
    fun `a keyframe stands on its own`() {
        val frames = (0 until 8).map { scene(it, it, 111) }
        val clip = encode(frames, MosaicEncoder.Options(gopFrames = 1000))

        // Frame 0 is a keyframe.
        val out = IntArray(width * height)
        MosaicReferenceDecoder.decodeFrame(clip, 0, IntArray(width * height), out)
        val quality = psnr(listOf(frames[0]), listOf(out))
        assertTrue(quality > 28.0, "the keyframe alone reconstructed at ${quality.roundToInt()} dB")
    }

    @Test
    fun `the same input always encodes to the same bytes`() {
        val frames = (0 until 8).map { scene(it, 0, 77) }
        val a = encode(frames)
        val b = encode(frames)

        assertEquals(a.texelCount, b.texelCount, "encoding is not deterministic in size")
        for (i in 0 until a.texelCount) {
            assertEquals(a.data[i], b.data[i], "encoding differs at texel $i")
        }
    }

    @Test
    fun `the sheet survives the archive's png round trip byte for byte`() {
        val clip = encode((0 until 6).map { scene(it, it, 130) })

        val edge = MosaicFormat.SHEET_EDGE
        val rows = (clip.texelCount + edge - 1) / edge
        val image = java.awt.image.BufferedImage(edge, rows, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (i in 0 until clip.texelCount) {
            image.setRGB(i % edge, i / edge, clip.data[i])
        }

        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        val back = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(out.toByteArray()))

        for (i in 0 until clip.texelCount) {
            assertEquals(
                clip.data[i], back.getRGB(i % edge, i / edge),
                "texel $i changed passing through png",
            )
        }
    }

    @Test
    fun `a clip too large for the pointer range is refused`() {
        val frames = List(4) { IntArray(width * height) }
        assertNull(
            MosaicEncoder.encode(frames, 1 shl 20, 1 shl 20, 30.0),
            "an absurd frame size was accepted",
        )
    }

    @Test
    fun `mode and pointer packing round trips`() {
        for (pointer in intArrayOf(0, 1, 255, 256, 65535, 65536, MosaicFormat.MAX_TEXELS - 1)) {
            val texel = MosaicFormat.withPointer(MosaicFormat.SB_CODED, pointer)
            assertEquals(MosaicFormat.SB_CODED, MosaicFormat.red(texel))
            assertEquals(pointer, MosaicFormat.pointer(texel), "pointer $pointer did not survive")
        }
    }

    @Test
    fun `the shader decodes the same format the reference does`() {
        val shader = java.io.File("../shaders/overlays/mc_26_2/post/shadr_video_decode.fsh")
        assertTrue(shader.isFile, "no decode shader at ${shader.path}")
        val source = shader.readText()

        fun define(name: String): Int? =
            Regex("""#define\s+$name\s+(-?\d+)""").find(source)?.groupValues?.get(1)?.toInt()

        assertEquals(MosaicFormat.SUPER, define("MOSAIC_SUPER"))
        assertEquals(MosaicFormat.BLOCK, define("MOSAIC_BLOCK"))
        assertEquals(MosaicFormat.BLOCKS_PER_SUPER_EDGE, define("MOSAIC_BLOCKS_PER_SUPER_EDGE"))
        assertEquals(MosaicFormat.SHEET_EDGE, define("MOSAIC_SHEET_EDGE"))
        assertEquals(MosaicFormat.SB_SKIP, define("MOSAIC_SB_SKIP"))
        assertEquals(MosaicFormat.SB_MOTION, define("MOSAIC_SB_MOTION"))
        assertEquals(MosaicFormat.BLK_SKIP, define("MOSAIC_BLK_SKIP"))
        assertEquals(MosaicFormat.BLK_MOTION, define("MOSAIC_BLK_MOTION"))
        assertEquals(MosaicFormat.BLK_DELTA, define("MOSAIC_BLK_DELTA"))
        assertEquals(MosaicFormat.BLK_INTRA, define("MOSAIC_BLK_INTRA"))
        assertEquals(MosaicFormat.BLK_MOTION_DELTA, define("MOSAIC_BLK_MOTION_DELTA"))
        assertEquals(MosaicFormat.BLK_MOTION_RESIDUAL, define("MOSAIC_BLK_MOTION_RESIDUAL"))
        assertEquals(MosaicFormat.BLK_ENDPOINT_REUSE, define("MOSAIC_BLK_ENDPOINT_REUSE"))
        assertEquals(MosaicFormat.BLK_INTRA_VQ, define("MOSAIC_BLK_INTRA_VQ"))
        assertEquals(MosaicFormat.MV_BIAS, define("MOSAIC_MV_BIAS"))
        assertEquals(MosaicFormat.RESIDUAL_BIAS, define("MOSAIC_RESIDUAL_BIAS"))
        assertEquals(MosaicFormat.HEADER_TEXELS, define("MOSAIC_HEADER_TEXELS"))
        assertEquals(MosaicFormat.CODEBOOK, define("MOSAIC_CODEBOOK"))

        fun table(name: String): List<Int>? =
            Regex("""$name\[8\] = int\[8\]\(([^)]*)\)""").find(source)
                ?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() }

        val payload = assertNotNull(table("MOSAIC_PAYLOAD_TEXELS"), "no payload size table")
        val commands = assertNotNull(table("MOSAIC_COMMAND_TEXELS"), "no command size table")
        for (mode in 0 until MosaicFormat.MODES) {
            assertEquals(
                MosaicFormat.payloadTexels(mode), payload[mode],
                "payload texels for mode $mode disagree with the format",
            )
            assertEquals(
                MosaicFormat.commandTexels(mode), commands[mode],
                "command texels for mode $mode disagree with the format",
            )
        }

        for (
            mode in listOf(
                "MOSAIC_BLK_SKIP", "MOSAIC_BLK_MOTION", "MOSAIC_BLK_DELTA",
                "MOSAIC_BLK_MOTION_DELTA", "MOSAIC_BLK_INTRA",
                "MOSAIC_BLK_MOTION_RESIDUAL", "MOSAIC_BLK_ENDPOINT_REUSE",
            )
        ) {
            assertTrue(
                source.contains("mode == $mode"),
                "the shader has no branch for $mode",
            )
        }
        assertTrue(source.contains("mosaic_bc1("), "the shader never decodes a bc1 quarter")
        assertTrue(
            source.contains("plane.r == MOSAIC_SB_MOTION"),
            "the shader has no branch for a superblock that moved as a whole",
        )
    }

    @Test
    fun `the pointer is exactly wide enough for the sheet`() {
        assertEquals(
            MosaicFormat.MAX_TEXELS, 1 shl 24,
            "the sheet and the 24-bit pointer have to describe the same range",
        )
    }

    @Test
    fun `every block of a coded superblock is addressable`() {
        val clip = encode((0 until 4).map { scene(it * 7, it, 150) })
        val planeEnd = clip.frameCount * clip.superblocksPerFrame
        for (frame in 0 until clip.frameCount) {
            for (sb in 0 until clip.superblocksPerFrame) {
                val texel = clip.data[frame * clip.superblocksPerFrame + sb]
                val mode = MosaicFormat.red(texel)
                if (mode == MosaicFormat.SB_SKIP || mode == MosaicFormat.SB_MOTION) continue
                val at = MosaicFormat.pointer(texel)
                assertTrue(
                    at >= planeEnd,
                    "superblock $sb of frame $frame points into the plane region",
                )
                assertTrue(
                    at + MosaicFormat.HEADER_TEXELS <= clip.texelCount,
                    "superblock $sb of frame $frame has no room for its header",
                )

                var commands = 0
                var payload = 0
                for (block in 0 until MosaicFormat.BLOCKS_PER_SUPER) {
                    val header = clip.data[at + if (block < 8) 0 else 1]
                    val blockMode = MosaicFormat.nibble(header, block)
                    assertTrue(
                        blockMode < MosaicFormat.MODES,
                        "block $block of superblock $sb carries mode $blockMode",
                    )
                    commands += MosaicFormat.commandTexels(blockMode)
                    payload += MosaicFormat.payloadTexels(blockMode)
                }
                assertTrue(
                    at + MosaicFormat.HEADER_TEXELS + commands + payload <= clip.texelCount,
                    "superblock $sb of frame $frame runs past the end of the sheet",
                )
            }
        }
    }

    @Test
    fun `the encoder reconstruction matches the reference decoder bit for bit`() {
        val frames = (0 until 12).map { scene(it * 2, it, 90 + it * 4) }
        val stats = MosaicEncoder.Stats().apply { keepFrames = true }
        val clip = encode(frames, MosaicEncoder.Options(gopFrames = 6), stats)
        val decoded = MosaicReferenceDecoder.decode(clip)

        assertEquals(stats.reconstructed.size, decoded.size)
        for (f in decoded.indices) {
            assertTrue(
                stats.reconstructed[f].contentEquals(decoded[f]),
                "the encoder and the decoder disagree about frame $f",
            )
        }
    }

    @Test
    fun `a moving fade rides motion plus delta rather than intra`() {
        val stats = MosaicEncoder.Stats()
        val frames = (0 until 8).map { scene(it * 5, 0, 40 + it * 24) }
        encode(frames, MosaicEncoder.Options(gopFrames = 1000), stats)

        assertTrue(
            stats.motionDelta > 0,
            "a pan with a brightness ramp used no motion plus delta blocks: $stats",
        )
    }

    @Test
    fun `a contrast change over texture is carried by a residual`() {
        fun noise(x: Int, y: Int): Int =
            ((x * 73856093) xor (y * 19349663)).ushr(8) and 0xFF

        val base = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                base[y * width + x] = rgb(noise(x, y), noise(x + 311, y), noise(x, y + 191))
            }
        }
        val faded = IntArray(base.size) { i ->
            val c = base[i]
            rgb(
                (((c ushr 16) and 0xFF) * 85) / 100,
                (((c ushr 8) and 0xFF) * 85) / 100,
                ((c and 0xFF) * 85) / 100,
            )
        }

        val stats = MosaicEncoder.Stats()
        encode(listOf(base, faded), MosaicEncoder.Options(gopFrames = 1000), stats)

        assertTrue(
            stats.motionResidual > 0,
            "a structured contrast change used no residual blocks: $stats",
        )
    }

    @Test
    fun `flat rows of a keyframe reuse their neighbour's endpoints`() {
        val gradient = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                gradient[y * width + x] = rgb(y * 2, 255 - y * 2, 128)
            }
        }

        val stats = MosaicEncoder.Stats()
        encode(listOf(gradient), stats = stats)

        assertTrue(
            stats.endpointReuse > 0,
            "a keyframe of identical columns reused no endpoints: $stats",
        )
    }

    @Test
    fun `a long clip settles into the codebook and stays decodable`() {
        val frames = (0 until 3).map { scene(it * 3, it, 80 + it * 9) }
        val stats = MosaicEncoder.Stats().apply { keepFrames = true }
        val clip = encode(
            frames,
            MosaicEncoder.Options(gopFrames = 1, codebook = true, codebookThreshold = 1),
            stats,
        )

        assertTrue(stats.intraVq > 0, "no block chose the codebook: $stats")
        assertEquals(
            clip.frameCount * clip.superblocksPerFrame, clip.codebookBase,
            "the codebook does not sit directly after the plane",
        )
        assertTrue(
            clip.codebookBase + MosaicFormat.CODEBOOK_TEXELS <= clip.texelCount,
            "the codebook runs past the end of the sheet",
        )

        val decoded = MosaicReferenceDecoder.decode(clip)
        for (f in decoded.indices) {
            assertTrue(
                stats.reconstructed[f].contentEquals(decoded[f]),
                "the codebook path diverges between encoder and decoder at frame $f",
            )
        }
        val quality = psnr(frames, decoded)
        assertTrue(quality > 25.0, "the codebook path decodes at ${quality.roundToInt()} dB")
    }
}
