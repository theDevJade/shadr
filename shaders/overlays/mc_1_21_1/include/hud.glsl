/*
 * Shadr
 *
 * Copyright © 2026 theDevJade. All rights reserved.
 *
 * Part of the Shadr project.
 * See LICENSE for licensing and distribution terms.
 */
#define X 1.0
#define Y 1.0
#define refRes vec2(1920.0, 1080.0)
#define SHADR_FIELD_BAND 100000.0

out float shadrMode;

bool is_hud(vec3 p, mat4 mvm) {
    return (p.y < -1000.0 || mvm[3].y < -1000.0);
}

bool make_hud(vec3 p, vec2 ScreenSize, mat4 mvm) {
    shadrMode = 0.0;

    if (is_hud(p, mvm)) {
        float realY = (mvm[3].y < -1000.0) ? mvm[3].y : p.y;
        shadrMode = 1.0;
        if (realY < -SHADR_FIELD_BAND) {
            realY += SHADR_FIELD_BAND;
            shadrMode = 2.0;
        }
        vec3 pos = p;
        if (p.y > -1000.0) {
            pos += vec3(mvm[3].x, mvm[3].y, mvm[3].z);
        }
        if (shadrMode > 1.5) {
            pos.y += SHADR_FIELD_BAND;
        }
        pos += vec3(0.0, 15000.0, 0.0);
        pos.x *= -1.0;
        float offset = 0.0;
        if (realY < -20000.0) {
            if (realY < -40000.0) {
                pos.y += 20000.0;
                offset = 1.0 - (ScreenSize.y / 9.0 * 16.0) / ScreenSize.x;
            } else if (realY < -30000.0) {
                pos.y += 10000.0;
            } else {
                offset = -1.0 + (ScreenSize.y / 9.0 * 16.0) / ScreenSize.x;
            }
            pos.y += 10000.0;
            pos.x *= (ScreenSize.y / 9.0 * 16.0) / ScreenSize.x;
        }
        pos.xy /= refRes * vec2(X, Y) / 2.0;
        pos.x += offset;
        pos.z /= 1000000.0;
        gl_Position = vec4(pos, 1.0);
        return true;
    }
    return false;
}
