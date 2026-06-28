#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

// ── Band colors (linear RGB 0-1) ──
uniform vec3 uColor1;   // Band A (main)
uniform vec3 uColor2;   // Band B (secondary)
uniform vec3 uColor3;   // Dark accents
uniform vec3 uColor4;   // Polar regions
uniform vec3 uColor5;   // Great storm color

// ── Band structure ──
uniform float uBands;        // 3-10
// When uUnbanded == 1, the band-pattern section below replaces the
// sin-driven latitudinal stripe pattern with a domain-warped 3D-FBM
// colour field. The curl-noise advection that runs above the colour
// stage is unchanged — it produces the underlying vortex flow regardless
// of which colour-mapping path is taken. Used for Class IV/V hot
// Jupiters and select ice giants per `applySwirl` in GasGiantGenerator.
uniform int uUnbanded;
// Venus-style differential-rotation jets, only meaningful in unbanded
// mode (a banded giant's per-band zonal rotation already imposes
// jets — this would compound chaotically). When 1, the advection loop
// adds three latitude-aligned zonal-flow contributions: an equatorial
// jet at lat = 0 plus two mid-latitude jets at lat ≈ ±0.55, with the
// equatorial jet running ~1.8× the mid-lat speed. The differential
// shears swirl-mode noise features into the Y/chevron pattern Venus's
// UV imagery shows, since features that span multiple latitudes get
// stretched east faster at the equator than at higher latitudes.
uniform int uChevronJets;
uniform float uBandBreakup;  // 0-1
uniform float uBandSoftness; // 0-1
uniform float uContrast;     // 0-2

// ── Detail ──
uniform float uMicroDetails; // 0-1
uniform float uStriations;   // 0-1
uniform float uTurbulence;   // 0-1.5

// ── Storms ──
uniform float uStormIntensity; // 0-1.5
uniform float uPoleSize;       // 0-1

// ── Scale ──
uniform float uNoiseScale;    // default 4.0

// ── Permutation texture (256×1 R8, NEAREST) ──
uniform sampler2D uPermTex;

// ── Macro storms (max 3) ──
uniform int uNumMacroStorms;
uniform vec4 uMacroStorm[3];     // xyz + radius
uniform vec2 uMacroStormProp[3]; // strength, unused

// ── Micro storms (max 20) ──
uniform int uNumMicroStorms;
uniform vec4 uMicroStorm[20];     // xyz + radius
uniform vec2 uMicroStormProp[20]; // strength, type (0=cyclone, 1=pearl)

// ── Constants ──
const float PI = 3.14159265;

// ─────────────────────────────────────────────
// Perlin noise via permutation texture
// ─────────────────────────────────────────────

int perm(int i) {
    return int(texelFetch(uPermTex, ivec2(i & 255, 0), 0).r * 255.0 + 0.5);
}

float fade(float t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); }

float grad(int hash, float x, float y, float z) {
    int h = hash & 15;
    float u = h < 8 ? x : y;
    float v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
    return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
}

float perlin3D(vec3 pos) {
    int X = int(floor(pos.x)) & 255;
    int Y = int(floor(pos.y)) & 255;
    int Z = int(floor(pos.z)) & 255;
    vec3 f = fract(pos);
    float u = fade(f.x);
    float v = fade(f.y);
    float w = fade(f.z);

    int A  = perm(X) + Y;
    int AA = perm(A) + Z;
    int AB = perm(A + 1) + Z;
    int B  = perm(X + 1) + Y;
    int BA = perm(B) + Z;
    int BB = perm(B + 1) + Z;

    return mix(
        mix(mix(grad(perm(AA),     f.x,       f.y,       f.z),
                grad(perm(BA),     f.x - 1.0, f.y,       f.z), u),
            mix(grad(perm(AB),     f.x,       f.y - 1.0, f.z),
                grad(perm(BB),     f.x - 1.0, f.y - 1.0, f.z), u), v),
        mix(mix(grad(perm(AA + 1), f.x,       f.y,       f.z - 1.0),
                grad(perm(BA + 1), f.x - 1.0, f.y,       f.z - 1.0), u),
            mix(grad(perm(AB + 1), f.x,       f.y - 1.0, f.z - 1.0),
                grad(perm(BB + 1), f.x - 1.0, f.y - 1.0, f.z - 1.0), u), v),
        w);
}

// FBM: 2 octaves for velocity fields (mobile optimization)
float fbm2(vec3 p) {
    float total = 0.0, maxVal = 0.0;
    float amp = 1.0, freq = 1.0;
    for (int i = 0; i < 2; i++) {
        total += perlin3D(p * freq) * amp;
        maxVal += amp;
        amp *= 0.5;
        freq *= 2.0;
    }
    return total / maxVal * 0.5 + 0.5;
}

// FBM: 3 octaves for detail
float fbm3(vec3 p) {
    float total = 0.0, maxVal = 0.0;
    float amp = 1.0, freq = 1.0;
    for (int i = 0; i < 3; i++) {
        total += perlin3D(p * freq) * amp;
        maxVal += amp;
        amp *= 0.5;
        freq *= 2.0;
    }
    return total / maxVal * 0.5 + 0.5;
}

// Ridged FBM for striations
float ridgedFBM3(vec3 p) {
    float total = 0.0, amp = 1.0, freq = 1.0, weight = 1.0;
    for (int i = 0; i < 3; i++) {
        float n = 1.0 - abs(perlin3D(p * freq));
        n *= n;
        n *= weight;
        weight = clamp(n * 2.0, 0.0, 1.0);
        total += n * amp;
        amp *= 0.5;
        freq *= 2.0;
    }
    return total;
}

float smoothstepCustom(float edge0, float edge1, float x) {
    float t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// ─────────────────────────────────────────────
// HSV utilities for hue rotation in color space
// ─────────────────────────────────────────────

vec3 rgbToHsv(vec3 c) {
    float maxC = max(c.r, max(c.g, c.b));
    float minC = min(c.r, min(c.g, c.b));
    float d = maxC - minC;
    float h = 0.0;
    if (d > 0.0001) {
        if (maxC == c.r)      h = mod((c.g - c.b) / d, 6.0);
        else if (maxC == c.g) h = (c.b - c.r) / d + 2.0;
        else                  h = (c.r - c.g) / d + 4.0;
        h /= 6.0;
        if (h < 0.0) h += 1.0;
    }
    float s = (maxC > 0.0001) ? d / maxC : 0.0;
    return vec3(h, s, maxC);
}

vec3 hsvToRgb(vec3 hsv) {
    float h = hsv.x * 6.0;
    float s = hsv.y;
    float v = hsv.z;
    float c = v * s;
    float x = c * (1.0 - abs(mod(h, 2.0) - 1.0));
    float m = v - c;
    vec3 rgb;
    if      (h < 1.0) rgb = vec3(c, x, 0.0);
    else if (h < 2.0) rgb = vec3(x, c, 0.0);
    else if (h < 3.0) rgb = vec3(0.0, c, x);
    else if (h < 4.0) rgb = vec3(0.0, x, c);
    else if (h < 5.0) rgb = vec3(x, 0.0, c);
    else              rgb = vec3(c, 0.0, x);
    return rgb + m;
}

// Shift hue by deltaNorm (0-1 = full cycle, e.g. 0.15 ≈ 54°)
vec3 shiftHue(vec3 color, float deltaNorm) {
    vec3 hsv = rgbToHsv(color);
    hsv.x = fract(hsv.x + deltaNorm);
    return hsvToRgb(hsv);
}

// Scale saturation by factor
vec3 scaleSaturation(vec3 color, float factor) {
    vec3 hsv = rgbToHsv(color);
    hsv.y = clamp(hsv.y * factor, 0.0, 1.0);
    return hsvToRgb(hsv);
}

// ─────────────────────────────────────────────
// Main
// ─────────────────────────────────────────────

void main() {
    // Equirectangular UV → sphere direction
    // Must match planet.frag lookup: u = 1 - (atan(z,x)+π)/(2π), v = (asin(y)+π/2)/π
    float phi = (1.0 - vUv.x) * 2.0 * PI - PI;
    float lat = (vUv.y - 0.5) * PI;
    float cosLat = cos(lat);
    vec3 dir = vec3(cosLat * cos(phi), sin(lat), cosLat * sin(phi));

    float scale = uNoiseScale;
    vec3 P = dir;

    // Effective parameters linked to turbulence (calm planets = smooth)
    float effectiveBreakup = uBandBreakup * min(1.0, uTurbulence * 1.5);
    float effectiveStriations = uStriations * min(1.0, uTurbulence * 1.5);

    // ══════════════════════════════════════════
    // FLUID ADVECTION (2 steps, reduced from 3)
    // ══════════════════════════════════════════
    for (int iter = 0; iter < 2; iter++) {
        float d = 0.02;
        vec3 sp = P * scale;

        // Storm clustering modulates local turbulence
        float stormCluster = fbm2(P * scale * 0.5);
        float dynamicTurbulence = uTurbulence * (stormCluster * 1.8 + 0.1);

        // Domain warp
        float warpScale = 3.5;
        float warpMag = 0.8 * uTurbulence;
        float wx = sp.x + (fbm2(sp * warpScale + vec3(12.3, 0.0, 0.0)) - 0.5) * warpMag;
        float wy = sp.y + (fbm2(sp * warpScale + vec3(0.0, 45.6, 0.0)) - 0.5) * warpMag;
        float wz = sp.z + (fbm2(sp * warpScale + vec3(0.0, 0.0, 78.9)) - 0.5) * warpMag;
        vec3 wp = vec3(wx, wy, wz);

        // Curl noise (finite differences)
        float n0x = fbm2(wp - vec3(d, 0.0, 0.0));
        float n1x = fbm2(wp + vec3(d, 0.0, 0.0));
        float n0y = fbm2(wp - vec3(0.0, d, 0.0));
        float n1y = fbm2(wp + vec3(0.0, d, 0.0));
        float n0z = fbm2(wp - vec3(0.0, 0.0, d));
        float n1z = fbm2(wp + vec3(0.0, 0.0, d));

        vec3 gradient = vec3(n1x - n0x, n1y - n0y, n1z - n0z) / (2.0 * d);

        // Project gradient to sphere tangent plane
        float dotGP = dot(gradient, P);
        vec3 tangentGrad = gradient - dotGP * P;

        // Curl on sphere
        vec3 curlV = cross(P, tangentGrad);

        // ── Macro storm vortices ──
        // Y-axis squish (5×) and reduced Y-velocity (×0.2) produce the
        // east-west elongated GRS shape that fits zonal flow. Swirl mode
        // disables both — macro storms still contribute vortex centres,
        // but they're round and free-flowing rather than band-anchored.
        vec3 stormV = vec3(0.0);
        if (uStormIntensity > 0.0) {
            float ySquish = (uUnbanded == 1) ? 1.0 : 5.0;
            float yVelScale = (uUnbanded == 1) ? 1.0 : 0.2;
            for (int s = 0; s < 3; s++) {
                if (s >= uNumMacroStorms) break;
                vec3 sPos = uMacroStorm[s].xyz;
                float sRad = uMacroStorm[s].w;
                float sStr = uMacroStormProp[s].x;

                vec3 dv = P - sPos;
                float distSq = dv.x * dv.x + dv.y * dv.y * ySquish + dv.z * dv.z;
                float effectRad = sRad * 1.5;

                if (distSq < effectRad * effectRad) {
                    float nDist = sqrt(distSq) / effectRad;
                    float intensity = sin(nDist * PI) * sStr * uStormIntensity * 0.7;
                    vec3 cv = cross(P, dv);
                    float clen = length(cv);
                    if (clen > 0.0) cv /= clen;
                    stormV += cv * vec3(intensity, intensity * yVelScale, intensity);
                }
            }
        }

        // ── Micro storm vortices ──
        // These produce the small round spots that read as "Jupiter
        // pearl-string storms" in banded mode but as visual "acne" in
        // swirl mode — skipped entirely when uUnbanded so the fluid
        // sim doesn't pick up dotted blemishes.
        vec3 microV = vec3(0.0);
        if (uMicroDetails > 0.0 && uTurbulence > 0.0 && uUnbanded == 0) {
            for (int s = 0; s < 20; s++) {
                if (s >= uNumMicroStorms) break;
                vec3 msPos = uMicroStorm[s].xyz;
                float msRad = uMicroStorm[s].w;
                float msStr = uMicroStormProp[s].x;

                vec3 dv = P - msPos;
                float distSq = dv.x * dv.x + dv.y * dv.y * 2.0 + dv.z * dv.z;
                float effectRadSq = (msRad * 1.5) * (msRad * 1.5);

                if (distSq < effectRadSq) {
                    float dist = sqrt(distSq);
                    float nDist = dist / (msRad * 1.5);
                    float intensity = sin(nDist * PI) * msStr * uMicroDetails * 0.6;
                    vec3 cv = cross(P, dv);
                    float clen = length(cv);
                    if (clen > 0.0) cv /= clen;
                    microV += cv * vec3(intensity, intensity * 0.5, intensity);
                }
            }
        }

        // Euler step. Coriolis damp normally suppresses Y-component to
        // 15 % (models how planetary rotation directs flow into zonal
        // jets). Swirl mode uses 1.0 — fully isotropic — so curl-noise
        // flow can run pole-to-pole freely.
        float coriolisDamp = (uUnbanded == 1) ? 1.0 : 0.15;
        vec3 vel = curlV * vec3(dynamicTurbulence * 0.6,
                                dynamicTurbulence * 0.6 * coriolisDamp,
                                dynamicTurbulence * 0.6)
                   + stormV + microV;

        P -= vel * 0.12;
        P = normalize(P);

        // Zonal advection (exact rotation around Y axis) — THE thing
        // that imposes visible jet streams on the flow. Swirl mode
        // skips this step entirely; the curl-noise + storm advection
        // above is already enough turbulent motion without a directed
        // zonal component on top.
        if (uUnbanded == 0) {
            float latWarp = perlin3D(vec3(10.123, P.y * uBands * 0.4, 10.123)) * 2.5 * uTurbulence;
            float yWarpAdvect = (fbm2(P * scale * 0.5) - 0.5) * effectiveBreakup * 0.25;
            float zonalPhase = (P.y + yWarpAdvect) * PI * uBands + latWarp;
            float zonalSpeed = sin(zonalPhase);
            float zonalAngle = zonalSpeed * 2.5 * 0.12;
            float cosA = cos(zonalAngle);
            float sinA = sin(zonalAngle);
            float px = P.x * cosA + P.z * sinA;
            float pz = -P.x * sinA + P.z * cosA;
            P.x = px;
            P.z = pz;
        } else if (uChevronJets == 1) {
            // ── Three-jet chevron shearing (Venus) ──
            // Equatorial jet (peak at lat = 0) gets the strongest eastward
            // rotation; two mid-latitude jets (peaks at lat = ±0.55) run at
            // ≈ 55 % of equatorial speed. The Gaussian falloff widths are
            // tuned so the three jets fade into each other across mid-lat
            // smoothly rather than reading as three discrete bands. The
            // shearing accumulates across the 2-iteration advection loop;
            // by the second iteration, swirl-mode noise features that span
            // multiple latitudes have been bent into the chevron Y-shape.
            // Peak angle of 1.20 rad ≈ 69° per iteration at the equator
            // (≈ 138° total — roughly a third-wrap around the planet),
            // calibrated to Venus's super-rotation regime where equatorial
            // cloud-top winds (~120 m/s) cover the full longitude in ~4
            // Earth days while the surface barely turns at all. The big
            // equator-vs-mid-lat differential (~60° per iter) is what
            // bends features into the visible Y-chevron rather than just
            // sliding them east as a whole.
            float latC = P.y;
            float eqJet  = exp(-latC * latC * 6.0);
            float midN   = exp(-(latC - 0.55) * (latC - 0.55) * 12.0) * 0.55;
            float midS   = exp(-(latC + 0.55) * (latC + 0.55) * 12.0) * 0.55;
            float chevronSpeed = eqJet + midN + midS;
            float chevronAngle = chevronSpeed * 1.20;
            float cosA = cos(chevronAngle);
            float sinA = sin(chevronAngle);
            float px = P.x * cosA + P.z * sinA;
            float pz = -P.x * sinA + P.z * cosA;
            P.x = px;
            P.z = pz;
        }
    }

    // ══════════════════════════════════════════
    // UPPER ATMOSPHERE (partial advection for mottling)
    // ══════════════════════════════════════════
    vec3 P_upper = normalize(mix(dir, P, 0.35));

    // ══════════════════════════════════════════
    // BAND / SWIRL PATTERN
    // ══════════════════════════════════════════
    // Two paths share the downstream colour mapping:
    //   • Banded (default): latitudinal sin-wave pattern + breakup noise,
    //     produces zonal stripes á la Jupiter / Saturn / standard Neptunes.
    //   • Swirl (uUnbanded == 1): pure-fluid look — domain-warped 3D FBM
    //     directly drives `t`, no sin, no preferred direction. The curl-
    //     noise advection that ran above the colour stage already laid
    //     down vortex structure in `P`; here we paint colour onto that
    //     flow via noise rather than imposing latitudinal stripes. Used
    //     for Class IV/V hot Jupiters and some ice giants.
    //
    // `finalBandPhase` is shared as a downstream scalar — in swirl mode
    // it carries the swirl noise field so the per-"band" hue variation
    // and striation mask code below pick up the swirl structure rather
    // than horizontal stripes.
    float t;
    float finalBandPhase;
    if (uUnbanded == 1) {
        vec3 warpQ = vec3(
            fbm3(P * scale * 0.8),
            fbm3(P * scale * 0.8 + vec3(5.2, 1.3, 2.1)),
            fbm3(P * scale * 0.8 + vec3(-3.4, 2.8, -4.2))
        ) - vec3(0.5);
        vec3 warpedP = P + warpQ * 1.4;
        float swirlBase = fbm3(warpedP * scale * 0.5);
        float swirlDetail = fbm3(warpedP * scale * 1.5);
        float rawT = swirlBase * 0.65 + swirlDetail * 0.35;

        // ── Wind streams ──
        // Derive the local flow tangent from the gradient of the
        // underlying swirl field: ∇swirl points uphill, so the contour
        // direction (perpendicular to ∇ on the sphere) IS the streamline
        // direction of the flow. Sample a high-frequency FBM with its
        // coordinate compressed by 88 % along that tangent — the noise
        // varies slowly along the flow but quickly across it, so the
        // resulting features elongate along streamlines as thin bright
        // lanes. Ridged via `1 − |2x − 1|` cubed to harden peaks into
        // visible streaks. Chevron mode (cloud overlay) gets the same
        // gradient-derived flow path — its directional bias comes from
        // the jet shearing applied during advection, not from the
        // colour-stage sampling.
        float eps = 0.012;
        vec3 cP = warpedP * scale * 0.5;
        vec3 grad = vec3(
            fbm3(cP + vec3(eps, 0.0, 0.0)) - fbm3(cP - vec3(eps, 0.0, 0.0)),
            fbm3(cP + vec3(0.0, eps, 0.0)) - fbm3(cP - vec3(0.0, eps, 0.0)),
            fbm3(cP + vec3(0.0, 0.0, eps)) - fbm3(cP - vec3(0.0, 0.0, eps))
        );
        vec3 tangentGrad = grad - dot(grad, P) * P;
        vec3 flowDir = cross(P, tangentGrad);
        flowDir = normalize(flowDir + vec3(1e-6));
        vec3 streakP = P * scale * 5.0;
        streakP -= flowDir * dot(streakP, flowDir) * 0.88;
        float windField = fbm3(streakP);
        float windRidged = 1.0 - abs(windField * 2.0 - 1.0);
        windRidged = windRidged * windRidged * windRidged;
        rawT += windRidged * 0.28 * uTurbulence;

        // Centred contrast stretch around 0.5. Value/Perlin noise
        // clusters near the midpoint (central-limit-of-many-octaves
        // effect), so a straight 0–1 noise map painted across the
        // palette produces mostly mid-palette colour with both
        // endpoints rarely reached. Banded mode escapes this because
        // `sin(finalBandPhase)` is bimodal at ±1; swirl mode needs to
        // stretch its noise distribution explicitly. 1.8× pushes the
        // mid-cluster outward without crushing too aggressively, then
        // the clamp pins the long tails to [0, 1] — net effect is the
        // dark-accent (uColor3) and band-B (uColor2) palette endpoints
        // get reached far more often, restoring the contrast banded
        // giants get for free.
        t = clamp((rawT - 0.5) * 1.8 + 0.5, 0.0, 1.0);
        // NO mix-toward-0.5 in swirl mode — that's banded mode's
        // softening of the sin wave's hard ±1 step. Applying it here
        // (with bandSoftness 0.75–0.95 for swirl giants) would pull
        // ~40 % back toward grey and undo the contrast stretch above.
        finalBandPhase = swirlBase * 8.0;
    } else {
        float yWarpFinal = (fbm2(P * scale * 0.4) - 0.5) * effectiveBreakup * 0.4;
        float boundaryNoise = (fbm3(P * scale * 2.0) - 0.5) * 0.8 * uTurbulence;

        // ── Variable band widths ──
        // Noise sampled at ~1/3 the band frequency produces a smooth, slowly-varying
        // phase offset. This compresses some bands and widens others, breaking the
        // perfectly regular sin() pattern. Scaled up for sharp bands (low softness).
        float bwVariance = (1.0 - uBandSoftness * 0.5) * 0.75;
        float bwNoise = perlin3D(vec3(P.x * scale * 0.2 + 3.7, P.y * uBands * 0.33, P.z * scale * 0.2 + 3.7));
        float phaseDistort = bwNoise * PI * bwVariance;

        finalBandPhase = (P.y + yWarpFinal) * PI * uBands + phaseDistort + boundaryNoise;

        // Shear noise
        float shearOffset = sin(finalBandPhase) * 2.0;
        vec3 shearCoord = vec3(P.x * scale + shearOffset, P.y * scale * 1.5, P.z * scale + shearOffset);
        float shearNoise = (fbm2(shearCoord) - 0.5) * 0.8 * (uTurbulence * effectiveBreakup);

        // Amplitude modulation
        float breakVisibility = fbm2(P * scale * 1.2);
        float bandAmp = mix(1.0, breakVisibility * 1.8, effectiveBreakup);

        float rawBand = sin(finalBandPhase);
        float bandPattern = rawBand * bandAmp + shearNoise;
        float normalizedPattern = clamp((bandPattern + 1.0) * 0.5, 0.0, 1.0);

        // Smoothstep → linear blend via bandSoftness
        float sharpT = normalizedPattern * normalizedPattern * (3.0 - 2.0 * normalizedPattern);
        t = mix(sharpT, normalizedPattern, uBandSoftness);
        t = mix(t, 0.5, uBandSoftness * 0.45);
    }

    // ══════════════════════════════════════════
    // COLOR MAPPING
    // ══════════════════════════════════════════

    // Per-band color variation: each band gets a hue + brightness offset
    // that evolves smoothly along the band phase axis. The original
    // implementation used `floor(finalBandPhase / PI)` to give each band
    // a discrete identity, but the per-band shift was applied AFTER the
    // smooth band blend — adjacent bands meet at Color1 (t=0.5) yet got
    // different hue/brightness offsets, producing a visible discontinuity
    // exactly where the underlying blend was smoothest. Sampling perlin
    // continuously along finalBandPhase at ~0.7× band frequency preserves
    // "each band has its own character" (adjacent bands sit far enough
    // apart in the noise lattice to look distinct) while keeping the
    // shift smoothly correlated across boundaries.
    float bandVariation = perlin3D(vec3(finalBandPhase / PI * 0.7 + 7.31, 13.17, 0.0));
    float bandHueDelta = bandVariation * 0.15 * uContrast; // ±54° at full contrast
    float bandBrightVar = 1.0 + bandVariation * 0.14;      // ±7% brightness per band

    vec3 finalColor;
    float darkAccentMask = 0.0;
    if (t < 0.5) {
        finalColor = mix(uColor3, uColor1, t * 2.0);
        darkAccentMask = 1.0 - t * 2.0;
    } else {
        finalColor = mix(uColor1, uColor2, (t - 0.5) * 2.0);
    }

    // Apply per-band hue rotation in HSV — safe for achromatic colors (ice giants, sub-Neptunes)
    finalColor = shiftHue(finalColor, bandHueDelta);
    finalColor = clamp(finalColor * bandBrightVar, 0.0, 1.0);

    // ── Polar regions ──
    float polarNoise = (fbm3(P * scale * 2.0) - 0.5) * 0.5 * uTurbulence;
    float poleStart = 1.0 - uPoleSize * 0.875;
    float poleEnd = poleStart + 0.3;
    float polarMask = smoothstepCustom(poleStart + polarNoise, poleEnd + polarNoise, abs(P.y));
    finalColor = mix(finalColor, uColor4, polarMask);
    darkAccentMask *= (1.0 - polarMask);

    // ── Great storm color ──
    float stormBlend = 0.0;
    if (uStormIntensity > 0.0) {
        for (int s = 0; s < 3; s++) {
            if (s >= uNumMacroStorms) break;
            vec3 sPos = uMacroStorm[s].xyz;
            float sRad = uMacroStorm[s].w;
            vec3 dv = P - sPos;
            float dist = sqrt(dv.x * dv.x + dv.y * dv.y * 5.0 + dv.z * dv.z);
            if (dist < sRad) {
                float sNoise = (fbm3(P * 15.0) - 0.5) * 0.25 * sRad * uTurbulence;
                float blend = smoothstepCustom(sRad, sRad * 0.4, dist + sNoise);
                stormBlend = max(stormBlend, blend);
            }
        }
    }
    float finalStormBlend = stormBlend * min(1.0, uStormIntensity * 2.0);
    finalColor = mix(finalColor, uColor5, finalStormBlend);
    darkAccentMask *= (1.0 - finalStormBlend);

    // ── Upper atmosphere chemical mottling ──
    // High-frequency `fbm3(P_upper * scale * 7.0)` with a smoothstep
    // threshold paints `uColor3` (dark accent) into the upper
    // atmosphere as discrete patches where the noise exceeds 0.4 —
    // reads as natural cloud chemistry against banded jets but as
    // dark spotty patches against a smooth swirl flow (third source
    // of "spot" artefacts after the two micro-storm passes). Skipped
    // in swirl mode for the same reason.
    if (uMicroDetails > 0.0 && uUnbanded == 0) {
        float chemNoise = fbm3(P_upper * scale * 7.0);
        float chemBlend = smoothstepCustom(0.4, 0.8, chemNoise) * uMicroDetails * max(0.1, uTurbulence);
        vec3 mottleColor = mix(uColor3, uColor2, 1.0 - darkAccentMask);
        finalColor = mix(finalColor, mottleColor, chemBlend * 0.65);
    }

    // ── Micro storm features (pearls and cyclones) ──
    // Second micro-storm pass — paints the small light/dark colour
    // spots that read as "Jupiter pearl-string storms" against bands
    // but show up as random dotted "acne" against a fluid swirl. Skip
    // entirely in swirl mode (matches the velocity-side gate above);
    // the macro-storm colour pass below still runs since those few
    // large vortex centres are part of a fluid look.
    float microLightBlend = 0.0;
    float microDarkBlend = 0.0;
    if (uMicroDetails > 0.0 && uUnbanded == 0) {
        for (int s = 0; s < 20; s++) {
            if (s >= uNumMicroStorms) break;
            vec3 msPos = uMicroStorm[s].xyz;
            float msRad = uMicroStorm[s].w;
            float msType = uMicroStormProp[s].y;

            vec3 dv = P_upper - msPos;
            float distSq = dv.x * dv.x + dv.y * dv.y * 2.0 + dv.z * dv.z;
            float effectRadSq = (msRad * 1.5) * (msRad * 1.5);

            if (distSq < effectRadSq) {
                float dist = sqrt(distSq);
                float sNoise = (fbm2(P_upper * 30.0) - 0.5) * 0.4 * msRad * uTurbulence;
                float blend = smoothstepCustom(msRad, msRad * 0.2, dist + sNoise);
                if (msType > 0.5) {
                    microLightBlend = max(microLightBlend, blend);
                } else {
                    microDarkBlend = max(microDarkBlend, blend);
                }
            }
        }
    }
    vec3 pearlColor = min(uColor1 * 1.2, vec3(1.0));
    finalColor = mix(finalColor, pearlColor, microLightBlend * uMicroDetails);
    vec3 deepDark = uColor3 * 0.5;
    finalColor = mix(finalColor, deepDark, microDarkBlend * uMicroDetails);

    // ══════════════════════════════════════════
    // DETAIL OVERLAY (RGB contrast, replaces HSV)
    // ══════════════════════════════════════════

    // Sub-band hue and saturation variation via HSV — safe for all palette types.
    // Swirl mode samples isotropic 3D noise so the variation follows the
    // vortex structure rather than running pole-to-pole.
    float bandHueNoise;
    float bandSatRawNoise;
    if (uUnbanded == 1) {
        bandHueNoise = perlin3D(P * scale * 0.5 + vec3(50.0, 50.0, 50.0)) * 0.06 * uContrast;
        bandSatRawNoise = perlin3D(P * scale * 0.5 + vec3(60.0, 60.0, 60.0));
    } else {
        bandHueNoise = perlin3D(vec3(50.123, P.y * uBands * 0.25, 50.123)) * 0.06 * uContrast;
        bandSatRawNoise = perlin3D(vec3(60.123, P.y * uBands * 0.25, 60.123));
    }
    float bandSatNoise = 1.0 + bandSatRawNoise * 0.4 * uContrast;
    finalColor = shiftHue(finalColor, bandHueNoise);
    finalColor = scaleSaturation(finalColor, bandSatNoise);
    finalColor = clamp(finalColor, 0.0, 1.0);

    // Soft detail FBM
    float cloudWeight = min(1.0, uTurbulence * 1.5);
    float softDetail = fbm3(P * scale * 3.0);

    // Striations (ridged FBM with extreme Y squish)
    float striations = 0.0;
    if (effectiveStriations > 0.001) {
        float sStretch = 0.05;
        float sSquish = 15.0;
        striations = ridgedFBM3(P * scale * 5.0 * vec3(sStretch, sSquish, sStretch));
    }

    // Striation masking. In swirl mode striations are deliberately
    // suppressed (uStriations should arrive as 0 from the generator,
    // but the mask is gated here too for safety) since pole-to-pole
    // streaks would re-introduce a preferred direction that the swirl
    // path is built to avoid.
    float breakNoise = fbm2(P * scale * vec3(8.0, 0.5, 8.0));
    float lineMask = smoothstepCustom(0.2, 0.8, breakNoise) * 1.5;
    float striationBandMask = (uUnbanded == 1)
        ? 0.0
        : smoothstepCustom(0.0, 0.8, sin(finalBandPhase + 1.5));
    float maskedStriations = striations * lineMask * striationBandMask;

    float combinedDetail = softDetail * 0.6 * cloudWeight + maskedStriations * effectiveStriations;

    // Apply contrast modulation
    float baseSat = mix(uContrast, 1.0, 0.85);
    float noiseImpact = 0.3 * uContrast;
    float satMod = baseSat + noiseImpact * combinedDetail;
    finalColor = clamp(finalColor * satMod, 0.0, 1.0);

    // Output sRGB (planet.frag decodes with pow(tex, 2.2))
    fragColor = vec4(pow(finalColor, vec3(1.0 / 2.2)), 1.0);
}
