/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
// @description A breathing rounded frame, for item rarity or a selection highlight
// @preview f0a04c

vec4 shadr_main(vec2 uv, float time, vec4 tint) {
    float aspect = shadr_aspect(uv);
    vec2 p = shadr_centered(uv, vec2(aspect, 1.0));

    vec2 extent = vec2(aspect, 1.0) * 0.86;
    float d = shadr_sd_round_box(p, extent, 0.30);

    float breath = 0.5 + 0.5 * shadr_wave(time, 4.0);

    float line = shadr_aa(abs(d) - 0.035);
    float bloom = exp(-max(d, 0.0) * (9.0 - 3.0 * breath)) * (0.35 + 0.35 * breath);

    vec3 colour = tint.rgb * (1.0 + 0.6 * breath);
    return shadr_resolve(colour, tint.a * clamp(line + bloom, 0.0, 1.0));
}
