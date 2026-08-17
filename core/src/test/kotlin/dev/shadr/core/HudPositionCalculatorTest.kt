/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
package dev.shadr.core

import dev.shadr.core.hud.HudPositionCalculator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HudPositionCalculatorTest {
    private val calculator = HudPositionCalculator()

    @Test
    fun `design pixels scale by the HUD size factor`() {
        val placement = calculator.calculateBoxPlacement(x = 0.0, y = 0.0, layer = 0.0, width = 160.0, height = 36.0)
        assertEquals(160.0 * 1.25 / 2.0, placement.scale.x, EPSILON)
        assertEquals(36.0 * 1.25 / 2.0, placement.scale.y, EPSILON)
        assertEquals(1.0, placement.scale.z, EPSILON)
    }

    @Test
    fun `edge compensation cancels the scaled quad's inward creep`() {
        val placement = calculator.calculateBoxPlacement(x = 100.0, y = 200.0, layer = 0.0, width = 128.0, height = 28.8)
        assertEquals(100.0 + 64.0 - 1.0, placement.location.x, EPSILON)
        assertEquals(200.0 + 28.8 + 1.0, placement.location.y, EPSILON)
    }

    @Test
    fun `tall elements drift down and are corrected linearly past 200px`() {
        val short = calculator.calculateBoxPlacement(0.0, 0.0, 0.0, 100.0, 200.0)
        val tall = calculator.calculateBoxPlacement(0.0, 0.0, 0.0, 100.0, 400.0)
        val shortDrift = short.location.y - (0.0 + 200.0 + 200.0 * 1.25 / 36.0)
        val tallDrift = tall.location.y - (0.0 + 400.0 + 400.0 * 1.25 / 36.0)
        assertEquals(0.0, shortDrift, EPSILON)
        assertEquals(-(400.0 - 200.0) * 0.0032, tallDrift, EPSILON)
    }

    @Test
    fun `top drift can be disabled`() {
        val with = calculator.calculateBoxPlacement(0.0, 0.0, 0.0, 100.0, 800.0, applyTopDrift = true)
        val without = calculator.calculateBoxPlacement(0.0, 0.0, 0.0, 100.0, 800.0, applyTopDrift = false)
        assertTrue(without.location.y > with.location.y)
    }

    @Test
    fun `left step correction only kicks in past 2876px`() {
        val narrow = calculator.calculateBoxPlacement(0.0, 0.0, 0.0, 2876.0, 10.0)
        val wide = calculator.calculateBoxPlacement(0.0, 0.0, 0.0, 2877.0, 10.0)
        val veryWide = calculator.calculateBoxPlacement(0.0, 0.0, 0.0, 2876.0 + 1920.0 + 1.0, 10.0)

        assertEquals(2876.0 / 2.0 - 2876.0 * 1.25 / 160.0, narrow.location.x, EPSILON)
        assertEquals(1.0, wide.location.x - (2877.0 / 2.0 - 2877.0 * 1.25 / 160.0), EPSILON)
        assertEquals(2.0, veryWide.location.x - (4797.0 / 2.0 - 4797.0 * 1.25 / 160.0), EPSILON)
    }

    @Test
    fun `alignment lands the vertex in the band hud_glsl decodes`() {
        val placement = calculator.calculateBoxPlacement(0.0, 0.0, 0.0, 100.0, 100.0)

        val left = calculator.toDisplayTranslation(placement.location, placement.scale, HudAlignment.LEFT)
        val center = calculator.toDisplayTranslation(placement.location, placement.scale, HudAlignment.CENTER)
        val right = calculator.toDisplayTranslation(placement.location, placement.scale, HudAlignment.RIGHT)

        assertTrue(left.y < -20_000 && left.y > -30_000, "left band was ${left.y}")

        assertTrue(center.y < -30_000 && center.y > -40_000, "center band was ${center.y}")

        assertTrue(right.y < -40_000, "right band was ${right.y}")
    }

    @Test
    fun `X is mirrored about the screen centre because make_hud flips it`() {
        val placement = calculator.calculateBoxPlacement(0.0, 0.0, 0.0, 100.0, 100.0)
        val translation = calculator.toDisplayTranslation(placement.location, placement.scale)
        assertEquals(960.0 - placement.location.x, translation.x, EPSILON)
    }

    @Test
    fun `layer rides Z scaled by the pixel multiplier`() {
        val placement = calculator.calculateBoxPlacement(0.0, 0.0, layer = 7.0, width = 100.0, height = 100.0)
        val translation = calculator.toDisplayTranslation(placement.location, placement.scale)
        assertEquals(-7.0 * 100.0, translation.z, EPSILON)
    }

    @Test
    fun `item displays are pushed out by their model depth`() {
        val placement = calculator.calculateBoxPlacement(0.0, 0.0, 0.0, 100.0, 100.0)
        val block = calculator.toDisplayTranslation(
            placement.location, placement.scale, itemThickness = HudPositionCalculator.ItemThickness.BLOCK,
        )
        val item = calculator.toDisplayTranslation(
            placement.location, placement.scale, itemThickness = HudPositionCalculator.ItemThickness.ITEM,
        )
        assertEquals(placement.scale.z / 2.0, block.z, EPSILON)
        assertEquals(placement.scale.z / 32.0, item.z, EPSILON)
    }

    @Test
    fun `fix-shaders packs high layers back down before scaling`() {
        val packed = calculator.toFixedShaderLayer(9200.0)
        val low = calculator.toFixedShaderLayer(10.0)
        assertEquals((9200.0 - 7400.0) * 2.0e-5, packed, EPSILON)
        assertEquals(10.0 * 2.0e-5, low, EPSILON)
        assertTrue(abs(packed) < 1.0)
    }

    @Test
    fun `text top-Y accounts for the baseline anchor`() {
        val shifted = HudPositionCalculator.toInternalTextTopY(y = 100.0, height = 60.0)
        assertEquals(100.0 - (60.0 - 7.0), shifted, EPSILON)
    }

    @Test
    fun `the distance-field band is independent of alignment`() {
        val placement = calculator.calculateBoxPlacement(0.0, 0.0, 0.0, 100.0, 100.0)

        for (alignment in HudAlignment.entries) {
            val plain = calculator.toDisplayTranslation(placement.location, placement.scale, alignment)
            val field = calculator.toDisplayTranslation(
                placement.location, placement.scale, alignment, distanceField = true,
            )

            assertEquals(HudPositionCalculator.FIELD_BAND, plain.y - field.y, EPSILON)
            assertTrue(field.y < -HudPositionCalculator.FIELD_BAND, "field band was ${field.y}")

            assertEquals(plain.y, field.y + HudPositionCalculator.FIELD_BAND, EPSILON)
        }
    }

    @Test
    fun `the field band clears the deepest alignment band by a wide margin`() {
        val deepestPlain = HudPositionCalculator.HUD_Y_BASE -
            HudPositionCalculator.alignedOffset(HudAlignment.RIGHT) - HudPositionCalculator.SCREEN_Y
        val shallowestField = HudPositionCalculator.HUD_Y_BASE - HudPositionCalculator.FIELD_BAND
        assertTrue(
            deepestPlain - shallowestField > 50_000,
            "only ${deepestPlain - shallowestField} units of clearance between the bands",
        )
    }

    @Test
    fun `every overlay's GLSL agrees with the Kotlin band constant`() {
        val overlays = java.io.File("../shaders/overlays").listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            .orEmpty()
        check(overlays.isNotEmpty()) { "no shader overlays found" }

        for (overlay in overlays) {
            val glsl = java.io.File(overlay, "include/hud.glsl")
            check(glsl.isFile) { "${overlay.name} has no hud.glsl" }
            val source = glsl.readText()

            val declared = Regex("#define\\s+SHADR_FIELD_BAND\\s+([0-9.]+)")
                .find(source)?.groupValues?.get(1)?.toDouble()
            assertEquals(
                HudPositionCalculator.FIELD_BAND, declared,
                "${overlay.name}/hud.glsl disagrees with HudPositionCalculator",
            )

            assertTrue(
                source.contains("shadrMode"),
                "${overlay.name}/hud.glsl does not strip the distance-field band",
            )
        }
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}
