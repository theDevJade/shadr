#version 330

#moj_import <shadr_video.glsl>

uniform sampler2D InSampler;
uniform sampler2D FrameSampler;

in vec2 texCoord;
out vec4 fragColor;

#define SHADR_VIDEO_DEBUG 0

void main() {
    vec4 scene = texture(InSampler, texCoord);

    ivec2 packed = shadr_video_unpack(scene.rgb);
    if (packed.x < 0) {
        fragColor = scene;
        return;
    }

    vec2 step = 1.0 / vec2(textureSize(InSampler, 0));
    int agree = 0;
    if (shadr_video_adjoins(packed, texture(InSampler, texCoord + vec2(step.x, 0.0)).rgb)) agree++;
    if (shadr_video_adjoins(packed, texture(InSampler, texCoord - vec2(step.x, 0.0)).rgb)) agree++;
    if (shadr_video_adjoins(packed, texture(InSampler, texCoord + vec2(0.0, step.y)).rgb)) agree++;
    if (shadr_video_adjoins(packed, texture(InSampler, texCoord - vec2(0.0, step.y)).rgb)) agree++;

    if (agree < 3) {
        fragColor = scene;
        return;
    }

    vec2 uv = vec2(packed) / float(SHADR_VIDEO_UV_MAX);

#if SHADR_VIDEO_DEBUG == 1
    // The marker
    fragColor = vec4(uv, 0.0, 1.0);
    return;
#endif

    fragColor = vec4(texture(FrameSampler, vec2(uv.x, 1.0 - uv.y)).rgb, 1.0);
}
