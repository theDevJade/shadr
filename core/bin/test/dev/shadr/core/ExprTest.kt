/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core

import dev.shadr.core.page.Expr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExprTest {
    @Test
    fun `plain numbers short-circuit`() {
        assertEquals(42.0, Expr.eval(42))
        assertEquals(-1.5, Expr.eval("-1.5"))
    }

    @Test
    fun `the forms real pages use`() {
        assertEquals(-960.0, Expr.eval("-1920/2"))
        assertEquals(3840.0, Expr.eval("1920*2"))
        assertEquals(32.0, Expr.eval("52-20"))
    }

    @Test
    fun `precedence and parentheses`() {
        assertEquals(7.0, Expr.eval("1 + 2 * 3"))
        assertEquals(9.0, Expr.eval("(1 + 2) * 3"))
        assertEquals(-6.0, Expr.eval("-(2 * 3)"))
        assertEquals(1.0, Expr.eval("7 % 3"))
    }

    @Test
    fun `screen variables resolve`() {
        val vars = Expr.screenVars(1920.0, 1080.0)
        assertEquals(700.0, Expr.eval("screenWidth/2 - 260", vars))
        assertEquals(540.0, Expr.eval("halfHeight", vars))
    }

    @Test
    fun `malformed input yields null`() {
        assertNull(Expr.eval("1920/"))
        assertNull(Expr.eval("(1+2"))
        assertNull(Expr.eval("1 + unknownVar"))
        assertNull(Expr.eval("1/0"))
        assertNull(Expr.eval("¯\\_(ツ)_/¯"))
        assertEquals(5.0, Expr.evalOr("nonsense", fallback = 5.0))
    }
}
