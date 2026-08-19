/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.paper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnvilTextCaptureTest {

    @Test
    fun `the anvil limit is the vanilla rename cap`() {
        assertEquals(
            50, AnvilTextCapture.ANVIL_LIMIT,
            "the client refuses to send a longer name, so this is the point a field has to " +
                "continue in a fresh anvil rather than silently stop accepting input",
        )
    }

    @Test
    fun `a field longer than one anvil box is reachable`() {
        assertTrue(
            dev.shadr.core.page.TextInput.DEFAULT_MAX_LENGTH > AnvilTextCapture.ANVIL_LIMIT,
            "the default field would fit in a single anvil box, so the refill path would never " +
                "run and would rot untested",
        )
    }
}
