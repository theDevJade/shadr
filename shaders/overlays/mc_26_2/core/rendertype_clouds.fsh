#version 330

#moj_import <minecraft:fog.glsl>

#define CLOUD_DEBUG 0

in float vertexDistance;
in vec4 vertexColor;
in vec3 cloudPosition;
flat in int cloudFace;
flat in float cloudLayerY;

out vec4 fragColor;

const float THICKNESS = 34.0;

const float MAX_MARCH = 260.0;

const int STEPS = 14;
const int LIGHT_STEPS = 2;

const float NOISE_SCALE = 1.0 / 46.0;

const float COVERAGE = 0.46;

const float DENSITY = 0.13;

const float LATERAL_FALLOFF = 1.0 / (55.0 * 55.0);

const vec3 LIGHT_DIR = normalize(vec3(0.45, 0.85, 0.25));

const float LIGHT_STEP = 7.0;

const vec3 CLOUD_LIT = vec3(1.06, 1.03, 0.99);
const vec3 CLOUD_SHADOW = vec3(0.52, 0.58, 0.72);

const float ABSORPTION = 1.35;

float cloud_hash(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.11, 0.17, 0.13));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float cloud_noise(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float n000 = cloud_hash(cell);
    float n100 = cloud_hash(cell + vec3(1.0, 0.0, 0.0));
    float n010 = cloud_hash(cell + vec3(0.0, 1.0, 0.0));
    float n110 = cloud_hash(cell + vec3(1.0, 1.0, 0.0));
    float n001 = cloud_hash(cell + vec3(0.0, 0.0, 1.0));
    float n101 = cloud_hash(cell + vec3(1.0, 0.0, 1.0));
    float n011 = cloud_hash(cell + vec3(0.0, 1.0, 1.0));
    float n111 = cloud_hash(cell + vec3(1.0, 1.0, 1.0));

    return mix(
        mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
        mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y),
        f.z
    );
}

float cloud_fbm(vec3 p) {
    float sum = cloud_noise(p) * 0.5;
    sum += cloud_noise(p * 2.03 + 17.3) * 0.32;
    sum += cloud_noise(p * 4.11 + 41.7) * 0.18;
    return sum;
}

float cloud_density(vec3 p) {
    float height = clamp((p.y - cloudLayerY) / THICKNESS, 0.0, 1.0);
    float profile = smoothstep(0.0, 0.22, height) * smoothstep(1.0, 0.45, height);
    if (profile <= 0.0) return 0.0;

    float shape = cloud_fbm(p * NOISE_SCALE);
    return max(0.0, shape - COVERAGE) * (1.0 / (1.0 - COVERAGE)) * profile;
}

float cloud_light(vec3 p) {
    float shadow = 0.0;
    for (int i = 1; i <= LIGHT_STEPS; i++) {
        vec3 sample_at = p + LIGHT_DIR * (LIGHT_STEP * float(i));
        float height = clamp((sample_at.y - cloudLayerY) / THICKNESS, 0.0, 1.0);
        float profile = smoothstep(0.0, 0.22, height) * smoothstep(1.0, 0.45, height);
        float shape = cloud_noise(sample_at * NOISE_SCALE) * 0.5;
        shadow += max(0.0, shape - COVERAGE * 0.5) * profile;
    }
    return exp(-shadow * ABSORPTION);
}

void main() {
#if CLOUD_DEBUG == 1
    fragColor = vec4(1.0, 0.0, 1.0, 1.0);
    return;
#endif

    if (cloudFace >= 2) discard;

#if CLOUD_DEBUG == 3
    fragColor = vec4(normalize(cloudPosition) * 0.5 + 0.5, 1.0);
    return;
#endif

    vec3 rayDirection = normalize(cloudPosition);
    vec3 origin = cloudPosition;

    float bottom = cloudLayerY;
    float top = cloudLayerY + THICKNESS;
    float exit = MAX_MARCH;
    if (abs(rayDirection.y) > 1.0e-4) {
        float toTop = (top - origin.y) / rayDirection.y;
        float toBottom = (bottom - origin.y) / rayDirection.y;
        exit = clamp(max(toTop, toBottom), 0.0, MAX_MARCH);
    }
    if (exit <= 0.0) discard;

    float stepSize = exit / float(STEPS);
    float transmittance = 1.0;
    vec3 scattered = vec3(0.0);

    float jitter = cloud_hash(vec3(gl_FragCoord.xy, 0.0));

    for (int i = 0; i < STEPS; i++) {
        float along = (float(i) + jitter) * stepSize;
        vec3 p = origin + rayDirection * along;
        float density = cloud_density(p);
        if (density <= 0.0) continue;

        density *= 1.0 - smoothstep(MAX_MARCH * 0.8, MAX_MARCH, along);

        float lateral = length((p - origin).xz);
        density *= exp(-lateral * lateral * LATERAL_FALLOFF);
        if (density <= 0.0) continue;

        float light = cloud_light(p);
        vec3 colour = mix(CLOUD_SHADOW, CLOUD_LIT, light);

        float absorbed = density * DENSITY * stepSize;
        absorbed = clamp(absorbed, 0.0, 1.0);

        scattered += colour * absorbed * transmittance;
        transmittance *= 1.0 - absorbed;
        if (transmittance < 0.01) break;
    }

    float alpha = 1.0 - transmittance;

#if CLOUD_DEBUG == 2
    fragColor = vec4(vec3(alpha), 1.0);
    return;
#endif

    if (alpha < 0.01) discard;

    vec3 colour = (scattered / max(alpha, 1.0e-4)) * vertexColor.rgb;

    alpha *= 1.0f - linear_fog_value(vertexDistance, 0, FogCloudsEnd);
    fragColor = vec4(colour, alpha * vertexColor.a);
}
