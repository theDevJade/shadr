/*
 * Copyright © 2026 theDevJade
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE.
 * SPDX-License-Identifier: Apache-2.0
 */
// @description Raymarched procedural planet with continents, ocean and volumetric clouds
// @preview 4cc9f0

#define MARCH_STEPS      40
#define CLOUD_STEPS      10
#define CLOUD_LIGHT_STEPS 3
#define REFINE_STEPS     4
#define MARCH_OCT        3
#define MAX_OCT          6
#define MIN_OCT          3

#define OPAQUE_SPACE     0

#define SEED             1.0

#define MACRO_SCALE      3.2
#define DETAIL_SCALE     22.0
#define HEIGHT_STRENGTH  1.6
#define RELIEF           0.030
#define SEA_LEVEL        0.54
#define MOUNTAIN_AMOUNT  0.55
#define MOISTURE_SCALE   5.0
#define CLOSE_SCALE      160.0
#define CLOSE_STRENGTH   0.35
#define CLOSE_RANGE      1.5

#define OCEAN_COL        vec3(0.008, 0.035, 0.090)
#define SHALLOW_COL      vec3(0.020, 0.180, 0.230)
#define SAND_COL         vec3(0.420, 0.310, 0.160)
#define GRASS_COL        vec3(0.080, 0.250, 0.070)
#define FOREST_COL       vec3(0.025, 0.120, 0.040)
#define ROCK_COL         vec3(0.240, 0.220, 0.200)
#define SNOW_COL         vec3(0.780, 0.900, 1.000)

#define TEMPERATURE      0.78
#define LAT_COOLING      0.65
#define ALT_COOLING      0.45
#define SNOW_LINE        0.45

#define OCEAN_F0         0.02
#define OCEAN_ROUGH      0.08
#define OCEAN_SPEC       2.2
#define WAVE_SCALE       55.0
#define WAVE_STRENGTH    0.35

#define SUN_INTENSITY    2.0
#define SUN_TINT         vec3(1.0, 0.93, 0.78)
#define AMBIENT          0.35
#define TERMINATOR       0.20

#define CITY_COL         vec3(1.0, 0.34, 0.08)
#define CITY_INTENSITY   3.0
#define CITY_AMOUNT      0.25

#define CLOUDS           1
#define CLOUD_BOTTOM     0.008
#define CLOUD_TOP        0.038
#define CLOUD_SCALE      6.0
#define CLOUD_COVERAGE   0.42
#define CLOUD_SOFT       0.30
#define CLOUD_DETAIL     3.2
#define CLOUD_EROSION    0.55
#define CLOUD_EXTINCT    130.0
#define CLOUD_WIND       0.020
#define CLOUD_SHEAR      1.80
#define CLOUD_SHADOW     0.65

#define R_CB             (1.0 + CLOUD_BOTTOM)
#define R_CT             (1.0 + CLOUD_TOP)
#define CLOUD_TH         (mix(0.72, 0.30, CLOUD_COVERAGE) - CLOUD_SOFT * 0.5)

float pl_hash13(vec3 p3)
{
    p3  = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 pl_hash33(vec3 p3)
{
    p3 = fract(p3 * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yxz + 33.33);
    return fract((p3.xxy + p3.yxx) * p3.zyx);
}

float pl_vnoise(vec3 x)
{
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);

    float a = pl_hash13(i + vec3(0.0, 0.0, 0.0));
    float b = pl_hash13(i + vec3(1.0, 0.0, 0.0));
    float c = pl_hash13(i + vec3(0.0, 1.0, 0.0));
    float d = pl_hash13(i + vec3(1.0, 1.0, 0.0));
    float e = pl_hash13(i + vec3(0.0, 0.0, 1.0));
    float g = pl_hash13(i + vec3(1.0, 0.0, 1.0));
    float h = pl_hash13(i + vec3(0.0, 1.0, 1.0));
    float k = pl_hash13(i + vec3(1.0, 1.0, 1.0));

    return mix(mix(mix(a, b, f.x), mix(c, d, f.x), f.y),
               mix(mix(e, g, f.x), mix(h, k, f.x), f.y), f.z);
}

const mat3 PL_ROT = mat3( 0.00,  0.80,  0.60,
                         -0.80,  0.36, -0.48,
                         -0.60, -0.48,  0.64);

float pl_fbm(vec3 p, int oct)
{
    float s = 0.0, a = 0.5, n = 0.0;
    for (int i = 0; i < 8; i++)
    {
        if (i >= oct) break;
        s += a * pl_vnoise(p);
        n += a;
        p  = PL_ROT * p * 2.02;
        a *= 0.5;
    }
    return s / n;
}

vec3 pl_fbm3(vec3 p, int oct)
{
    return vec3(pl_fbm(p, oct), pl_fbm(p + 31.7, oct), pl_fbm(p - 17.3, oct)) - 0.5;
}

float pl_ridgedMF(vec3 p, int oct)
{
    float sum = 0.0, amp = 0.5, prev = 1.0, norm = 0.0;
    for (int i = 0; i < 8; i++)
    {
        if (i >= oct) break;
        float n = 1.0 - abs(pl_vnoise(p) * 2.0 - 1.0);
        n *= n;
        sum  += n * amp * prev;
        prev  = clamp(n * 1.4, 0.0, 1.0);
        norm += amp;
        p     = PL_ROT * p * 2.1;
        amp  *= 0.5;
    }
    return sum / norm;
}

float pl_ridgedFbm(vec3 p, int oct)
{
    float sum = 0.0, amp = 0.5, norm = 0.0;
    for (int i = 0; i < 8; i++)
    {
        if (i >= oct) break;
        sum  += amp * (1.0 - abs(pl_vnoise(p) * 2.0 - 1.0));
        norm += amp;
        p     = PL_ROT * p * 2.06;
        amp  *= 0.5;
    }
    return sum / norm;
}

float pl_worley(vec3 p)
{
    vec3 b = floor(p - 0.5);
    float d = 8.0;
    for (int z = 0; z <= 1; z++)
    for (int y = 0; y <= 1; y++)
    for (int x = 0; x <= 1; x++)
    {
        vec3 g = b + vec3(x, y, z);
        vec3 o = g + 0.25 + 0.5 * pl_hash33(g) - p;
        d = min(d, dot(o, o));
    }
    return sqrt(d);
}

float pl_baseHeight(vec3 dir, int oct, out float shore)
{
    vec3 p = dir * MACRO_SCALE + SEED * 13.73;

    vec3  warp = pl_fbm3(p * 0.73, 2) * 1.1;
    float g    = 1.0 - pl_worley((p + warp) * 0.62);
    float body = pl_fbm(p * 0.9 + warp.zxy, 3);
    g = g * 0.62 + body * 0.48;
    g = (g - 0.55) * 1.45 + 0.52;

    shore = smoothstep(SEA_LEVEL - 0.01, SEA_LEVEL + 0.05, g);

    float mont = pl_ridgedMF(dir * (MACRO_SCALE * 2.1) + SEED * 7.7, oct);
    return g + mont * (0.34 * MOUNTAIN_AMOUNT)
                    * smoothstep(SEA_LEVEL + 0.005, SEA_LEVEL + 0.26, g) * shore;
}

float pl_terrainHeight(vec3 dir, int oct, float closeAmt)
{
    float shore;
    float h = pl_baseHeight(dir, max(oct - 1, 3), shore);

    if (shore <= 0.001) return clamp(h, 0.0, 1.0);

    h += (pl_fbm(dir * DETAIL_SCALE + SEED * 3.1, max(oct - 2, 2)) - 0.5)
       * 0.06 * shore;

    if (closeAmt > 0.001)
    {
        float ridg = pl_ridgedFbm(dir * CLOSE_SCALE + SEED * 5.3, 3);
        float fine = pl_fbm(dir * (CLOSE_SCALE * 3.7) + SEED * 9.4, 2);
        h += ((ridg - 0.45) * 0.03 + (fine - 0.5) * 0.012)
           * CLOSE_STRENGTH * closeAmt * shore;
    }
    return clamp(h, 0.0, 1.0);
}

float pl_relAltOf(float h)
{
    return clamp((h - SEA_LEVEL) / (1.0 - SEA_LEVEL), 0.0, 1.0);
}

float pl_surfaceR(vec3 dir)
{
    float shore;
    return 1.0 + RELIEF * pl_relAltOf(pl_baseHeight(dir, MARCH_OCT, shore));
}

void pl_tangentFrame(vec3 dir, out vec3 t1, out vec3 t2)
{
    vec3 up = abs(dir.y) < 0.99 ? vec3(0, 1, 0) : vec3(1, 0, 0);
    t1 = normalize(cross(dir, up));
    t2 = cross(dir, t1);
}

vec3 pl_terrainNormal(vec3 dir, float hCenter, int oct, float closeAmt,
                      float strength, out float slope)
{
    float e = mix(0.012, 0.0015, closeAmt);
    vec3 t1, t2;
    pl_tangentFrame(dir, t1, t2);

    float hx = pl_terrainHeight(normalize(dir + t1 * e), oct, closeAmt);
    float hy = pl_terrainHeight(normalize(dir + t2 * e), oct, closeAmt);
    vec2  g  = vec2(hx - hCenter, hy - hCenter) / e;
    slope    = clamp(length(g) * 0.35, 0.0, 1.0);

    vec3 grad = t1 * g.x + t2 * g.y;
    vec3 bump = grad - dir * dot(grad, dir);
    return normalize(dir - bump * strength);
}

vec3 pl_oceanNormal(vec3 dir, float waveFade, float time)
{
    if (waveFade <= 0.001) return dir;

    vec3  wp = dir * WAVE_SCALE + SEED * 4.9;
    float t  = time * 0.45;
    const float e = 0.4;

    vec3 t1, t2;
    pl_tangentFrame(dir, t1, t2);

    float w0 = pl_vnoise(wp + t)          + pl_vnoise(wp * 2.7 - t * 1.3) * 0.5;
    float wx = pl_vnoise(wp + t1 * e + t) + pl_vnoise((wp + t1 * e) * 2.7 - t * 1.3) * 0.5;
    float wy = pl_vnoise(wp + t2 * e + t) + pl_vnoise((wp + t2 * e) * 2.7 - t * 1.3) * 0.5;

    vec2 g    = vec2(wx - w0, wy - w0) / e;
    vec3 grad = t1 * g.x + t2 * g.y;
    vec3 bump = grad - dir * dot(grad, dir);
    return normalize(dir - bump * WAVE_STRENGTH * 0.35 * waveFade);
}

vec2 pl_raySphere(vec3 ro, vec3 rd, float r)
{
    float b = dot(ro, rd);
    float c = dot(ro, ro) - r * r;
    float d = b * b - c;
    if (d < 0.0) return vec2(1.0, -1.0);
    d = sqrt(d);
    return vec2(-b - d, -b + d);
}

vec3 pl_cloudSpin(vec3 pn, float time)
{
    float a = time * CLOUD_WIND * (1.0 + CLOUD_SHEAR * pn.y * pn.y);
    float s = sin(a), c = cos(a);
    return vec3(c * pn.x - s * pn.z, pn.y, s * pn.x + c * pn.z);
}

float pl_cloudDensity(vec3 p, float time, int oct)
{
    float r  = length(p);
    float hf = (r - R_CB) / (CLOUD_TOP - CLOUD_BOTTOM);
    if (hf < 0.0 || hf > 1.0) return 0.0;

    float prof = smoothstep(0.0, 0.25, hf) * smoothstep(1.0, 0.55, hf);
    if (prof <= 0.001) return 0.0;

    vec3  q   = pl_cloudSpin(p / r, time) * CLOUD_SCALE + SEED * 11.3;
    float cov = smoothstep(CLOUD_TH, CLOUD_TH + CLOUD_SOFT, pl_fbm(q, oct));
    float d   = cov * prof;
    if (d <= 0.001) return 0.0;

    float er = clamp((pl_fbm(q * CLOUD_DETAIL + 19.7, oct - 1) - 0.5) * 2.2 + 0.5,
                     0.0, 1.0);
    er *= CLOUD_EROSION * (0.4 + 0.6 * hf);
    return clamp((d - er) / max(1.0 - er, 1e-3), 0.0, 1.0);
}

float pl_cloudLight(vec3 p, vec3 L, float time)
{
    float dt  = (CLOUD_TOP - CLOUD_BOTTOM) * 1.7 / float(CLOUD_LIGHT_STEPS);
    float sum = 0.0;
    float t   = dt * 0.5;
    for (int i = 0; i < CLOUD_LIGHT_STEPS; i++)
    {
        sum += pl_cloudDensity(p + L * t, time, 3);
        t   += dt;
    }
    return exp(-sum * dt * CLOUD_EXTINCT * 0.55);
}

float pl_hg(float mu, float g)
{
    float g2 = g * g;
    return (1.0 - g2) / pow(max(1.0 + g2 - 2.0 * g * mu, 1e-4), 1.5);
}

float pl_cloudShadow(vec3 pos, vec3 sunDir, float time)
{
    vec2  t  = pl_raySphere(pos, sunDir, (R_CB + R_CT) * 0.5);
    vec3  cp = normalize(pos + sunDir * max(t.y, 0.0));
    vec3  q  = pl_cloudSpin(cp, time) * CLOUD_SCALE + SEED * 11.3;
    float cov = smoothstep(CLOUD_TH, CLOUD_TH + CLOUD_SOFT, pl_fbm(q, 3));
    return 1.0 - cov * CLOUD_SHADOW;
}

float pl_cityMask(vec3 dir, float landMask, float snowMask, float relAlt)
{
    if (CITY_AMOUNT <= 0.001) return 0.0;
    float w       = pl_worley(dir * 48.0 + SEED * 9.1);
    float cluster = pl_fbm(dir * 4.0 + SEED * 2.3, 3);
    float lights  = smoothstep(0.26, 0.03, w) * smoothstep(0.45, 0.72, cluster);
    lights *= landMask * (1.0 - snowMask)
            * (1.0 - smoothstep(0.3, 0.6, relAlt));
    return clamp(lights * CITY_AMOUNT * 4.0, 0.0, 1.0);
}

vec3 pl_starField(vec3 rd, float time)
{
    vec3  p = rd * 190.0;
    vec3  i = floor(p);
    vec3  f = fract(p) - 0.5;
    vec3  o = pl_hash33(i) - 0.5;
    float d = length(f - o * 0.75);
    float s = smoothstep(0.14, 0.0, d) * step(0.983, pl_hash13(i + 3.1));
    float tw = 0.65 + 0.35 * sin(time * 2.0 + pl_hash13(i) * 40.0);
    vec3  c  = mix(vec3(0.65, 0.75, 1.0), vec3(1.0, 0.85, 0.65), pl_hash13(i + 9.7));
    return c * s * tw * 1.4;
}

vec3 pl_shadeSurface(vec3 pos, vec3 rd, vec3 sunDir, bool oceanHit,
                     int octs, float closeAmt, float relDist, float time,
                     vec3 tint)
{
    vec3 dir     = normalize(pos);
    vec3 sphereN = dir;
    vec3 V       = -rd;

    float h        = pl_terrainHeight(dir, octs, closeAmt);
    float landMask = smoothstep(SEA_LEVEL - 0.004, SEA_LEVEL + 0.004, h);
    float relAlt   = pl_relAltOf(h);
    if (oceanHit) landMask = 0.0;

    float slope = 0.0;
    vec3  terrN = sphereN;
    if (landMask > 0.001)
        terrN = pl_terrainNormal(dir, h, octs, closeAmt,
                                 HEIGHT_STRENGTH * landMask, slope);

    float moisture = pl_fbm(dir * MOISTURE_SCALE + SEED * 5.7, 3);
    float clim     = pl_fbm(dir * 1.4 + SEED * 8.3, 2);
    float lat      = clamp(abs(dir.y) + (clim - 0.5) * 0.3, 0.0, 1.0);
    float freeze = lat * lat * LAT_COOLING
                 + relAlt * ALT_COOLING
                 + (0.5 - TEMPERATURE * 0.6)
                 + (clim - 0.5) * 0.1;
    float snowMask = smoothstep(SNOW_LINE, SNOW_LINE + 0.15, freeze);
    float iceMask  = smoothstep(SNOW_LINE + 0.15, SNOW_LINE + 0.26, freeze);

    vec3 veg     = mix(GRASS_COL, FOREST_COL, smoothstep(0.35, 0.7, moisture));
    vec3 landCol = mix(SAND_COL, veg, smoothstep(0.015, 0.08, relAlt));
    float rockAmt = max(smoothstep(0.4, 0.75, relAlt),
                        smoothstep(0.35, 0.7, slope));
    landCol = mix(landCol, ROCK_COL, rockAmt);

    if (closeAmt > 0.001 && landMask > 0.001)
    {
        float breakup = pl_fbm(dir * (CLOSE_SCALE * 1.9) + SEED * 6.1, 2);
        landCol *= mix(1.0, 0.55 + 0.9 * breakup, closeAmt * landMask);
    }
    landCol = mix(landCol, SNOW_COL, snowMask);

    float depth01  = clamp((SEA_LEVEL - h) / SEA_LEVEL * 9.0, 0.0, 1.0);
    vec3  waterCol = mix(SHALLOW_COL, OCEAN_COL, depth01);
    waterCol = mix(waterCol, SNOW_COL * 0.92, iceMask);

    float waterMask = (1.0 - landMask) * (1.0 - iceMask);
    vec3  albedo    = mix(waterCol, landCol, landMask) * tint;

    float waveFade = clamp((1.5 - relDist) * 2.0, 0.0, 1.0);
    vec3 waveN = waterMask > 0.001 ? pl_oceanNormal(dir, waveFade, time) : sphereN;
    vec3 N     = normalize(mix(waveN, terrN, landMask));

    vec3  L      = sunDir;
    vec3  sunCol = SUN_TINT * SUN_INTENSITY;
    float NdotLg = dot(sphereN, L);
    float NdotL  = clamp(dot(N, L), 0.0, 1.0);
    float day    = smoothstep(-TERMINATOR, TERMINATOR, NdotLg);
    float night  = 1.0 - day;

    float termBand = clamp(1.0 - abs(NdotLg) / TERMINATOR, 0.0, 1.0);
    vec3  warm     = mix(vec3(1.0), vec3(1.0, 0.55, 0.3), termBand * 0.65);

    float cShadow = pl_cloudShadow(pos, L, time);

    vec3 direct  = sunCol * warm * NdotL * day * cShadow;
    vec3 ambient = vec3(0.012, 0.016, 0.024) * AMBIENT
                 + vec3(0.004, 0.006, 0.012) * night * AMBIENT;
    vec3 col = albedo * (ambient + direct);

    float wetMask = max(waterMask, iceMask * 0.35);
    if (wetMask > 0.001)
    {
        float VdotN   = clamp(dot(V, sphereN), 0.0, 1.0);
        float fresnel = OCEAN_F0 + (1.0 - OCEAN_F0) * pow(1.0 - VdotN, 5.0);

        vec3  Hv      = normalize(L + V);
        float NdotH   = clamp(dot(N, Hv), 0.0, 1.0);
        float rough   = mix(OCEAN_ROUGH, 0.35, iceMask);
        float specPow = 2.0 / max(rough * rough, 1e-3);
        float spec    = pow(NdotH, specPow) * (specPow + 8.0) * 0.04973592;

        col += sunCol * spec * fresnel * OCEAN_SPEC
             * NdotL * day * cShadow * wetMask;

        col += vec3(0.10, 0.16, 0.28) * fresnel * wetMask
             * day * SUN_INTENSITY * 0.15;
    }

    float city = pl_cityMask(dir, landMask, snowMask, relAlt);
    col += CITY_COL * CITY_INTENSITY * city * smoothstep(0.15, 0.6, night);

    float mu   = clamp(dot(sphereN, V), 0.0, 1.0);
    float rim  = pow(1.0 - mu, 2.2);
    vec3  atmo = vec3(0.22, 0.45, 1.0);
    float dayRim = day * (0.3 + 0.7 * clamp(NdotLg, 0.0, 1.0));
    col = mix(col, atmo * dot(col, vec3(0.3, 0.59, 0.11)) * 2.0, rim * 0.15 * day);
    col += atmo * rim * dayRim * SUN_INTENSITY * 0.10;

    return col;
}

vec3 pl_renderClouds(vec3 ro, vec3 rd, vec3 sunDir, float tMax, float time,
                     float jitter, out float trans)
{
    trans = 1.0;
    vec2 o = pl_raySphere(ro, rd, R_CT);
    if (o.y <= 0.0 || o.x > o.y) return vec3(0.0);

    float t0 = max(o.x, 0.0);
    float t1 = min(o.y, tMax);
    if (t1 <= t0) return vec3(0.0);

    float dt = (t1 - t0) / float(CLOUD_STEPS);
    float t  = t0 + dt * jitter;

    float mu = dot(rd, sunDir);
    float ph = clamp(mix(pl_hg(mu, 0.65), pl_hg(mu, -0.25), 0.30), 0.0, 5.0);

    vec3 scat = vec3(0.0);
    for (int i = 0; i < CLOUD_STEPS; i++)
    {
        vec3  p = ro + rd * t;
        float d = pl_cloudDensity(p, time, 4);
        if (d > 0.003)
        {
            float r   = length(p);
            float hf  = clamp((r - R_CB) / (CLOUD_TOP - CLOUD_BOTTOM), 0.0, 1.0);
            vec3  pn  = p / r;
            float ndl = dot(pn, sunDir);
            float day = smoothstep(-TERMINATOR, TERMINATOR, ndl);

            float lt = pl_cloudLight(p, sunDir, time);
            float powder = 1.0 - exp(-d * 2.5);

            vec3 warm = mix(vec3(1.0), vec3(1.0, 0.52, 0.26),
                            smoothstep(0.30, 0.0, abs(ndl)) * 0.85);
            vec3 sun  = SUN_TINT * SUN_INTENSITY * ph * lt
                      * (0.30 + 0.70 * powder) * day * warm;
            vec3 amb  = mix(vec3(0.05, 0.07, 0.11), vec3(0.30, 0.42, 0.62), hf)
                      * AMBIENT * (0.25 + 0.75 * day);

            float sigma = d * CLOUD_EXTINCT;
            float st    = exp(-sigma * dt);
            scat  += trans * (sun + amb) * (1.0 - st);
            trans *= st;
            if (trans < 0.02) break;
        }
        t += dt;
    }
    return scat;
}

vec3 pl_aces(vec3 x)
{
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14),
                 0.0, 1.0);
}

mat3 pl_camBasis(vec3 ro, vec3 ta)
{
    vec3 w = normalize(ta - ro);
    vec3 u = normalize(cross(w, vec3(0.0, 1.0, 0.0)));
    vec3 v = cross(u, w);
    return mat3(u, v, w);
}

vec4 shadr_main(vec2 uvIn, float time, vec4 tint)
{
    vec3 rd = shadr_ray_dir();

    float placedScale = shadr_tint_scale(tint);
    vec3  tintRgb     = shadr_tint_rgb(tint);

    float planetR = shadr_quad_radius() * 0.45;

    vec3  ro   = shadr_ray_origin() / max(planetR, 1e-6);
    float dist = length(ro);

    float sa = shadr_phase(time, SHADR_TAU / 0.05) * SHADR_TAU + 1.0;
    vec3 sunDir = normalize(vec3(cos(sa), 0.22, sin(sa)));

    float spin = shadr_phase(time, SHADR_TAU / 0.03) * SHADR_TAU;
    float cs = cos(spin), sn = sin(spin);
    mat3 turn = mat3(cs, 0.0, -sn, 0.0, 1.0, 0.0, sn, 0.0, cs);
    ro = turn * ro;
    rd = normalize(turn * rd);
    sunDir = turn * sunDir;

    float altitude = max(dist - 1.0, 0.0);
    float closeAmt = 1.0 - smoothstep(0.0, CLOSE_RANGE, altitude);
    int   octs     = int(mix(float(MAX_OCT), float(MIN_OCT),
                             clamp(log2(1.0 + altitude * 2.0) / 3.5, 0.0, 1.0)));
    octs = clamp(octs + int(clamp(log2(max(placedScale, 1.0)) * 0.5, 0.0, 2.0)), MIN_OCT, MAX_OCT);

    const float RMAX = 1.0 + RELIEF;
    vec2 outer = pl_raySphere(ro, rd, RMAX);
    vec2 inner = pl_raySphere(ro, rd, 1.0);

    vec3  col      = vec3(0.0);
    bool  hit      = false;
    bool  oceanHit = false;
    float tHit     = 0.0;

    if (outer.y > 0.0 && outer.x <= outer.y)
    {
        float t     = max(outer.x, 0.0);
        bool  seaOK = (inner.y >= inner.x) && (inner.x > 0.0);
        float tEnd  = seaOK ? inner.x : outer.y;

        float tPrev = t;
        for (int i = 0; i < MARCH_STEPS; i++)
        {
            vec3  p = ro + rd * t;
            float d = length(p) - pl_surfaceR(normalize(p));
            if (d < 0.0) { hit = true; break; }
            tPrev = t;
            t += clamp(d * 0.55, 0.0012 + 0.0008 * t, 0.04);
            if (t > tEnd) break;
        }

        if (hit)
        {
            for (int r = 0; r < REFINE_STEPS; r++)
            {
                float tm = 0.5 * (tPrev + t);
                vec3  p  = ro + rd * tm;
                float d  = length(p) - pl_surfaceR(normalize(p));
                if (d > 0.0) tPrev = tm; else t = tm;
            }
            tHit = t;
        }
        else if (seaOK)
        {
            hit = true; oceanHit = true; tHit = inner.x;
        }
        else if (t <= tEnd)
        {
            hit = true; tHit = t;
        }
    }

    float alpha = 0.0;

    if (hit)
    {
        vec3 pos = ro + rd * tHit;
        col = pl_shadeSurface(pos, rd, sunDir, oceanHit, octs, closeAmt,
                              dist, time, tintRgb);
        alpha = 1.0;
    }
    else
    {
#if OPAQUE_SPACE
        col = pl_starField(rd, time);
        float b  = -dot(ro, rd);
        float d2 = dot(ro, ro) - b * b;
        if (b > 0.0)
        {
            float d    = sqrt(max(d2, 0.0));
            vec3  pca  = normalize(ro + rd * b);
            float lit  = clamp(dot(pca, sunDir) * 1.5 + 0.15, 0.0, 1.0);
            float glow = exp(-(d - 1.0) * 26.0);
            col += vec3(0.25, 0.48, 1.0) * glow * lit * 1.1;
            col *= 1.0 - glow * 0.8;
        }
        alpha = 1.0;
#else
        float b  = -dot(ro, rd);
        if (b > 0.0)
        {
            float d    = sqrt(max(dot(ro, ro) - b * b, 0.0));
            vec3  pca  = normalize(ro + rd * b);
            float lit  = clamp(dot(pca, sunDir) * 1.5 + 0.15, 0.0, 1.0);
            float glow = exp(-(d - 1.0) * 26.0);
            col   = vec3(0.25, 0.48, 1.0) * glow * lit * 1.1;
            alpha = clamp(glow * lit * 2.0, 0.0, 1.0);
        }
#endif
    }

#if CLOUDS
    {
        float jit = pl_hash13(vec3(uvIn * 4096.0, 7.13));
        float ctr;
        vec3  cscat = pl_renderClouds(ro, rd, sunDir, hit ? tHit : 1e9,
                                      time, jit, ctr);
        col = col * ctr + cscat;
        alpha = max(alpha, (1.0 - ctr) * clamp(shadr_luma(cscat) * 4.0 + 0.35, 0.0, 1.0));
    }
#endif

    col = pl_aces(col);
    return vec4(pow(col, vec3(1.0 / 2.2)), alpha * tint.a);
}
