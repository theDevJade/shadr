/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.pack

object GlslCompiler {

    private val present: Boolean by lazy {
        runCatching {
            ProcessBuilder("glslangValidator", "-v").redirectErrorStream(true).start().waitFor() == 0
        }.getOrDefault(false)
    }

    fun available(): Boolean {
        if (present) return true
        check(System.getenv("CI") == null) {
            "glslangValidator is not on PATH. Every shader compile test skips without it, so CI " +
                "would report green while compiling nothing. Install it in the workflow " +
                "(apt-get install -y glslang-tools) or unset CI to skip locally."
        }
        return false
    }
}
