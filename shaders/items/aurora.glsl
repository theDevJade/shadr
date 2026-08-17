/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
// @description A slow aurora sweep, tinted by the element colour
// @preview 4cc9f0

vec4 shadr_main(vec2 uv, float time, vec4 tint) {
    vec2 p = shadr_centered(uv, vec2(1.0));

    float bands = shadr_fbm(vec2(p.x * 2.0, p.y * 0.8 + time * 0.15));
    float glow = smoothstep(0.75, 0.15, abs(p.y - (bands - 0.5) * 0.7));

    vec3 colour = mix(tint.rgb, shadr_hsv(vec3(fract(0.55 + bands * 0.2), 0.7, 1.0)), 0.45);
    return shadr_resolve(colour * glow, glow * tint.a);
}
