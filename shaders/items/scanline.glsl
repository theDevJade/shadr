/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
// @description CRT scanlines with a rolling refresh bar and edge vignette
// @preview 4cc9f0

float sc_bar(float y, float phase) {
    float d = abs(fract(y - phase + 0.5) - 0.5);
    return exp(-d * d * 90.0);
}

vec4 shadr_main(vec2 uv, float time, vec4 tint) {
    float roll = shadr_phase(time, 6.0);

    float lines = 0.5 + 0.5 * cos(uv.y * 300.0);
    float bar = sc_bar(uv.y, roll);

    vec2 p = shadr_centered(uv, vec2(shadr_aspect(uv), 1.0));
    float vignette = 1.0 - 0.45 * dot(p, p);

    float intensity = (0.55 + 0.45 * lines) * vignette + bar * 0.35;
    vec3 colour = tint.rgb * intensity;

    return shadr_resolve(colour, tint.a * clamp(intensity, 0.0, 1.0));
}
