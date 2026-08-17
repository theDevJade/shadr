/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.pack

import dev.shadr.core.RoundingSize
import dev.shadr.core.hud.PageRenderer
import dev.shadr.core.hud.HudDraw
import dev.shadr.core.hud.ShapeBuckets
import dev.shadr.core.page.Element
import dev.shadr.core.page.ElementType
import dev.shadr.core.page.Page
import dev.shadr.core.page.Rounding
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShapeAssetsTest {
    private fun renderSdfBox(size: RoundingSize): HudDraw {
        val element = Element(
            id = "box",
            type = ElementType.BLOCK_SDF,
            width = 200.0,
            height = 100.0,
            color = dev.shadr.core.Rgb(0x4CC9F0),
            rounding = Rounding(size = size),
        )
        val rendered = PageRenderer().render(Page(name = "t", elements = listOf(element)))
        return rendered.draws.single()
    }

    @Test
    fun `an SDF box is one item draw carrying its colour and radius as data`() {
        val draw = renderSdfBox(RoundingSize.REGULAR)

        assertEquals(HudDraw.Kind.ITEM, draw.kind)
        assertEquals(PageRenderer.SHAPE_ITEM, draw.item)

        assertEquals(0x4CC9F0, draw.tint?.packed)

        assertTrue(draw.distanceField)
        assertTrue(draw.translation.y < -100_000)
    }

    @Test
    fun `each preset lands in a bucket whose model exists`() {
        for (size in RoundingSize.entries) {
            val bucket = renderSdfBox(size).itemCustomModelData
            assertNotNull(bucket)
            assertTrue(bucket in 0 until ShapeBuckets.COUNT, "bucket $bucket out of range")

            val model = File("../out/pack/assets/minecraft/models/item/shadr/box_$bucket.json")
            if (model.isFile) {
                assertTrue(
                    model.readText().contains("box_$bucket"),
                    "model $bucket does not reference its own texture",
                )
            }
        }
    }

    @Test
    fun `an absolute radius is honoured to within one bucket`() {
        val step = ShapeBuckets.MAX_FRACTION / (ShapeBuckets.COUNT - 1)
        for (radius in listOf(4.0, 12.0, 20.0, 40.0)) {
            val element = Element(
                id = "box",
                type = ElementType.BLOCK_SDF,
                width = 200.0,
                height = 100.0,
                rounding = Rounding(size = RoundingSize.REGULAR, radius = radius),
            )
            val draw = PageRenderer().render(Page(name = "t", elements = listOf(element))).draws.single()

            val got = draw.cornerFraction
            assertNotNull(got)
            assertEquals(radius / 100.0, got, step / 2 + 1e-9, "radius $radius quantised too far")
        }
    }

    @Test
    fun `an oversized radius clamps to the capsule bucket`() {
        val element = Element(
            id = "box",
            type = ElementType.BLOCK_SDF,
            width = 200.0,
            height = 100.0,
            rounding = Rounding(size = RoundingSize.REGULAR, radius = 500.0),
        )
        val draw = PageRenderer().render(Page(name = "t", elements = listOf(element))).draws.single()
        assertEquals(ShapeBuckets.COUNT - 1, draw.itemCustomModelData)
        assertEquals(ShapeBuckets.MAX_FRACTION, draw.cornerFraction)
    }

    @Test
    fun `the baked corner radius matches what the renderer asked for`() {
        val root = File("../out/pack/assets/minecraft/textures/item/shadr")

        if (!File(root, "box_0.png").isFile) return

        for (bucket in 0 until ShapeBuckets.COUNT) {
            val texture = File(root, "box_$bucket.png")
            assertTrue(texture.isFile, "missing parameter texture for bucket $bucket")
            val image = javax.imageio.ImageIO.read(texture)
            assertNotNull(image)

            val baked = ((image.getRGB(0, 0) shr 16) and 0xFF) / 255.0
            assertEquals(
                ShapeBuckets.fractionFor(bucket), baked, 1.0 / 255.0,
                "bucket $bucket bakes a different fraction than the renderer assumes",
            )
        }
    }

    @Test
    fun `the position channels ramp across the whole sprite`() {
        val texture = File("../out/pack/assets/minecraft/textures/item/shadr/box_8.png")
        if (!texture.isFile) return
        val image = javax.imageio.ImageIO.read(texture)

        assertEquals(ShapeAssets.GRID, image.width)
        assertEquals(ShapeAssets.GRID, image.height)

        assertEquals(0, (image.getRGB(0, 0) shr 8) and 0xFF, "green must start at 0")
        assertEquals(255, (image.getRGB(ShapeAssets.GRID - 1, 0) shr 8) and 0xFF, "green must reach 255")
        assertEquals(0, image.getRGB(0, 0) and 0xFF, "blue must start at 0")
        assertEquals(255, image.getRGB(0, ShapeAssets.GRID - 1) and 0xFF, "blue must reach 255")
    }

    @Test
    fun `every tinted item face claims the tint index its definition declares`() {
        val models = File("../out/pack/assets/minecraft/models/item/shadr")
        if (!models.isDirectory) return

        val checked = models.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue(checked.isNotEmpty(), "no shadr item models were generated")

        for (model in checked) {
            val text = model.readText()

            val faces = Regex("\"(north|south|east|west|up|down)\"\\s*:\\s*\\{([^}]*)}")
                .findAll(text).map { it.groupValues[2] }.toList()
            assertTrue(faces.isNotEmpty(), "${model.name} declares no faces")
            for (face in faces) {
                assertTrue(
                    face.contains("\"tintindex\""),
                    "${model.name} has a face with no tintindex, so it renders white:\n$face",
                )
            }
        }
    }

    @Test
    fun `the GLSL grid constant matches the generator`() {
        val glsl = File("../shaders/overlays/mc_26_2/include/hud_shape.glsl")
        check(glsl.isFile) { "expected the shape include at ${glsl.absolutePath}" }
        val declared = Regex("#define\\s+SHADR_SHAPE_GRID\\s+([0-9.]+)")
            .find(glsl.readText())?.groupValues?.get(1)?.toDouble()
        assertEquals(ShapeAssets.GRID.toDouble(), declared, "hud_shape.glsl and ShapeAssets disagree")
    }
}
