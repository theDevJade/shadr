#version 330

#moj_import <minecraft:globals.glsl>
#moj_import <shadr_video.glsl>

uniform sampler2D PrevSampler;
uniform sampler2D DataSampler;
uniform sampler2D StateSampler;
uniform sampler2D LastStateSampler;

layout(std140) uniform ShadrVideoConfig {
    vec4 Geometry;
    vec4 Playback;
};

in vec2 texCoord;
out vec4 fragColor;

#define MOSAIC_SUPER 32
#define MOSAIC_BLOCK 8
#define MOSAIC_BLOCKS_PER_SUPER_EDGE 4
#define MOSAIC_SHEET_EDGE 4096

#define MOSAIC_SB_SKIP 0
#define MOSAIC_SB_MOTION 2
#define MOSAIC_BLK_SKIP 0
#define MOSAIC_BLK_MOTION 1
#define MOSAIC_BLK_DELTA 2
#define MOSAIC_BLK_INTRA 3
#define MOSAIC_MV_BIAS 128

ivec4 mosaic_fetch(int index) {
    return ivec4(round(texelFetch(
        DataSampler,
        ivec2(index % MOSAIC_SHEET_EDGE, index / MOSAIC_SHEET_EDGE),
        0) * 255.0));
}

int mosaic_pointer(ivec4 texel) {
    return (texel.g << 16) | (texel.b << 8) | texel.a;
}


ivec3 mosaic_previous(ivec2 size, int x, int y) {
    int cx = clamp(x, 0, size.x - 1);
    int cy = clamp(y, 0, size.y - 1);
    return ivec3(round(texelFetch(PrevSampler, ivec2(cx, size.y - 1 - cy), 0).rgb * 255.0));
}

ivec3 mosaic_expand565(int packed) {
    int r = (packed >> 11) & 31;
    int g = (packed >> 5) & 63;
    int b = packed & 31;
    return ivec3((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2));
}

ivec3 mosaic_intra(ivec4 command, int quarter, int x, int y) {
    int at = mosaic_pointer(command) + quarter * 2;
    ivec4 endpoints = mosaic_fetch(at);
    ivec4 indices = mosaic_fetch(at + 1);

    ivec3 c0 = mosaic_expand565((endpoints.g << 8) | endpoints.r);
    ivec3 c1 = mosaic_expand565((endpoints.a << 8) | endpoints.b);

    int row = y % 4 == 0 ? indices.r : (y % 4 == 1 ? indices.g : (y % 4 == 2 ? indices.b : indices.a));
    int pick = (row >> ((x % 4) * 2)) & 3;

    if (pick == 0) return c0;
    if (pick == 1) return c1;
    if (pick == 2) return (2 * c0 + c1) / 3;
    return (c0 + 2 * c1) / 3;
}

void main() {
    ivec2 size = ivec2(Geometry.xy);
    int superColumns = int(Geometry.z);
    int superblocksPerFrame = int(Geometry.w);

    ivec4 state = ivec4(round(texelFetch(StateSampler, ivec2(0), 0) * 255.0));
    ivec4 last = ivec4(round(texelFetch(LastStateSampler, ivec2(0), 0) * 255.0));

    int frame = (state.r << 16) | (state.g << 8) | state.b;

    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int x = pixel.x;
    int y = size.y - 1 - pixel.y;

    if (last.a > 127 && state.rgb == last.rgb) {
        fragColor = vec4(vec3(mosaic_previous(size, x, y)) / 255.0, 1.0);
        return;
    }

    ivec4 plane = mosaic_fetch(
        frame * superblocksPerFrame + (y / MOSAIC_SUPER) * superColumns + (x / MOSAIC_SUPER));

    ivec3 colour;
    if (plane.r == MOSAIC_SB_SKIP) {
        colour = mosaic_previous(size, x, y);
    } else if (plane.r == MOSAIC_SB_MOTION) {
        colour = mosaic_previous(size, x + plane.g - MOSAIC_MV_BIAS, y + plane.b - MOSAIC_MV_BIAS);
    } else {
        int inSuper =
            ((y % MOSAIC_SUPER) / MOSAIC_BLOCK) * MOSAIC_BLOCKS_PER_SUPER_EDGE +
            ((x % MOSAIC_SUPER) / MOSAIC_BLOCK);
        ivec4 command = mosaic_fetch(mosaic_pointer(plane) + inSuper);
        int quarter = ((y % MOSAIC_BLOCK) / 4) * 2 + ((x % MOSAIC_BLOCK) / 4);

        if (command.r == MOSAIC_BLK_SKIP) {
            colour = mosaic_previous(size, x, y);
        } else if (command.r == MOSAIC_BLK_MOTION) {
            colour = mosaic_previous(
                size, x + command.g - MOSAIC_MV_BIAS, y + command.b - MOSAIC_MV_BIAS);
        } else if (command.r == MOSAIC_BLK_DELTA) {
            ivec4 offset = mosaic_fetch(mosaic_pointer(command) + quarter);
            colour = clamp(mosaic_previous(size, x, y) + offset.rgb - MOSAIC_MV_BIAS, 0, 255);
        } else {
            colour = mosaic_intra(command, quarter, x, y);
        }
    }

    fragColor = vec4(vec3(colour) / 255.0, 1.0);
}
