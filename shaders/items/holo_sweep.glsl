/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
// @description A holographic sheen that sweeps across at an angle
// @preview 9d7bff

vec4 shadr_main(vec2 uv, float time, vec4 tint) {
    float aspect = shadr_aspect(uv);

    float axis = (uv.x * aspect + uv.y) / (aspect + 1.0);

    float a = fract(axis - shadr_phase(time, 3.0));
    float b = fract(axis - shadr_phase(time, 5.0));
    float band = exp(-a * a * 260.0) + 0.5 * exp(-b * b * 90.0);

    vec3 sheen = shadr_hsv(vec3(fract(axis * 0.5 + shadr_phase(time, 20.0)), 0.55, 1.0));

    float carrier = 0.16 + 0.10 * cos(uv.y * 70.0);

    vec3 colour = tint.rgb * carrier + sheen * band * 0.9;
    return shadr_resolve(colour, tint.a * clamp(carrier * 1.6 + band, 0.0, 1.0));
}
