/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.page.ScreenDef
import dev.shadr.core.page.TemplateResolver
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectorExpressionTest {

    private fun xOf(expression: String): Double = TemplateResolver().resolve(
        listOf(
            mapOf(
                "type" to "block",
                "id" to "probe",
                "position" to mapOf("x" to expression, "y" to 0),
                "size" to mapOf("width" to 10, "height" to 10),
            ),
        ),
        ScreenDef(),
    ).single().x

    @Test
    fun `the offset forms the demo selector emits all evaluate`() {
        assertEquals(820.0, xOf("halfWidth - 140.0"))
        assertEquals(960.0, xOf("halfWidth + 0.0"))
        assertEquals(1115.0, xOf("halfWidth + 155.0"))
        assertEquals(665.0, xOf("halfWidth - 295.0"))
    }

    @Test
    fun `a negative literal after a plus also evaluates`() {
        assertEquals(
            820.0, xOf("halfWidth + -140.0"),
            "the demo selector formats the sign itself for readability, but the evaluator handles " +
                "'+ -' too, so a caller that does not format is still correct",
        )
    }

    @Test
    fun `an expression that cannot parse falls back rather than failing the page`() {
        assertEquals(
            0.0, xOf("halfWidth +"),
            "a broken offset must fall back to the default, which is exactly why the working " +
                "forms above are pinned: nothing else would report the breakage",
        )
    }
}
