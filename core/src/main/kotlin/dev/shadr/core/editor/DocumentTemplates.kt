/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.shadr.core.editor

object DocumentTemplates {
    fun starter(ref: DocumentRef, hud: Boolean, width: Double, height: Double): String =
        when (ref.kind) {
            DocumentKind.COMPONENT -> component(ref.name)
            DocumentKind.PAGE -> if (hud) hud(ref.name, width, height) else page(ref.name, width, height)
        }

    private fun page(name: String, width: Double, height: Double): String {
        val w = number(width)
        val h = number(height)
        return """
            name: $name
            screen:
              width: $w
              height: $h
              hud: false
              cursorSize: 10
              cursorSpeed: 2.0
              cursorLayer: 9700

            blocks:
              - type: block
                id: backdrop
                layer: 0.0
                color: 08080b
                opacity: 240
                position: {x: 0, y: 0}
                size: {width: $w, height: $h}

              - type: text
                id: title
                layer: 5.0
                color: f2f2f7
                position: {x: $w / 2, y: ${number(height / 4)}}
                size: {width: 84, height: 84}
                text: "$name"
                font: shadr_semibold
                textAlign: center
        """.trimIndent() + "\n"
    }

    private fun hud(name: String, width: Double, height: Double): String {
        val w = number(width)
        val h = number(height)
        return """
            name: $name
            screen:
              width: $w
              height: $h
              hud: true
              cursorSize: 0
              cursorSpeed: 2.0
              cursorLayer: 9700

            blocks:
              - type: block_rounded
                id: panel
                layer: 10.0
                color: 0d0d12
                opacity: 190
                position: {x: 24, y: 24}
                size: {width: 260, height: 68}
                rounding: {size: small}
                outline: {size: 1, color: 24242e}

                children:
                  - type: text
                    id: panel_label
                    layer: 11.0
                    color: f2f2f7
                    position: {x: 18, y: 20}
                    size: {width: 48, height: 48}
                    text: "$name"
                    font: shadr_semibold
                    textAlign: left
        """.trimIndent() + "\n"
    }

    private fun component(name: String): String = """
        params:
          id: "$name"
          label: "$name"
          width: 160
          height: 40
          color: "1a1a22"
          textColor: "f2f2f7"
          fontSize: 40

        blocks:
          - type: block_rounded
            id: "${'$'}{id}"
            layer: 10.0
            color: "${'$'}{color}"
            position: {x: 0, y: 0}
            size: {width: "${'$'}{width}", height: "${'$'}{height}"}
            rounding: {size: small}

            children:
              - type: text
                id: "${'$'}{id}_label"
                layer: 11.0
                color: "${'$'}{textColor}"
                position:
                  x: "${'$'}{width} / 2"
                  y: "${'$'}{height} / 2 - 0.1514 * ${'$'}{fontSize}"
                size: {width: "${'$'}{fontSize}", height: "${'$'}{fontSize}"}
                text: "${'$'}{label}"
                font: shadr_semibold
                textAlign: center
    """.trimIndent() + "\n"

    private fun number(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
