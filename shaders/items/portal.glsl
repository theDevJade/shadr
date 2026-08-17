/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
// @description A swirling portal disc with a bright rim
// @preview 9d4cf0

vec2 pt_swirl(vec2 p, float amount) {
    return shadr_rotate(p, amount / (0.25 + dot(p, p)));
}

vec4 shadr_main(vec2 uv, float time, vec4 tint) {
    float aspect = shadr_aspect(uv);
    vec2 p = shadr_centered(uv, vec2(aspect, 1.0));

    float r = length(p);
    float disc = shadr_aa((r - 0.82) * 2.0);
    if (disc < 0.001) return vec4(0.0);

    vec2 swirled = pt_swirl(p, shadr_phase(time, 30.0) * SHADR_TAU * 0.12);

    float clouds = shadr_fbm(swirled * 2.6 + vec2(0.0, shadr_phase(time, 40.0) * 4.0));

    vec3 inner = shadr_hsv(vec3(fract(0.72 + clouds * 0.18), 0.72, 0.45 + 0.55 * clouds));
    vec3 colour = mix(tint.rgb * 0.35, inner, 0.8);

    float rim = exp(-abs(r - 0.82) * 26.0);
    colour += tint.rgb * rim * 1.4;

    return shadr_resolve(colour, tint.a * clamp(disc + rim * 0.5, 0.0, 1.0));
}
