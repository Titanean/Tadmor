#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

// ── Camera ──
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform vec3 uCameraPos;

// ── Primary sun ──
uniform vec3 uSunDirection;
uniform float uSunIntensity;
uniform vec3 uSunColor;
uniform float uSunSize;
uniform float uSunDistanceAU;

// ── Secondary sun (binary / circumbinary systems only) ──
// uHasSecondarySun == 1: the planet orbits a binary; both stars are lit
// from a SMA-derived static angular separation around the barycenter
// direction (which is encoded in uSunDirection — they spread symmetrically
// around it). Every per-sun contribution — atmosphere in-scattering,
// surface Lambertian, sun disc, corona, diffraction spikes, ring shadows —
// is computed twice and summed. == 0: the second-sun branches are skipped
// and the cost is one uniform branch.
uniform int uHasSecondarySun;
uniform vec3 uSun2Direction;
uniform float uSun2Intensity;
uniform vec3 uSun2Color;
uniform float uSun2Size;
uniform float uSun2DistanceAU;

// ── Planet ──
uniform float uPlanetRadius;
uniform float uOblateness;
uniform vec3 uPlanetColor;
uniform sampler2D uPlanetTexture;
uniform bool uUseTexture;
uniform float uBumpStrength;  // 0 = no bump, >0 = perturb normal from texture alpha
uniform vec3 uThermalEmission; // blackbody emission × intensity for hot surfaces (>700 K)
uniform float uWaterSpecular;  // [0,1] ocean glint strength (0 = no specular)
uniform float uLavaEmission;   // [0,1] — gates the warm-pixel emissive mask so normal terrain can't glow
// Hapke / Lommel-Seeliger blend factor for airless rocky bodies (Moon analogues).
// 0 = pure Lambertian (current default; familiar limb darkening).
// 1 = full Lommel-Seeliger: brightness ∝ μ₀/(μ₀+μ), so the illuminated
// hemisphere reads almost uniformly bright out to the terminator — the
// "Moon at full phase" look. Set CPU-side for airless dry rocky worlds
// (no atmosphere, no water/ice, regolith-class surface).
uniform float uHapkeStrength;

// ── Atmosphere extent ──
uniform float uAtmosphereThickness;

// ── Rayleigh ──
uniform vec3 uRayleighScattering;
uniform float uRayleighScaleHeight;

// ── Mie ──
uniform vec3 uMieScattering;
uniform vec3 uMieAbsorption;
uniform float uMieScaleHeight;
uniform float uMiePhaseG;
uniform float uMiePhaseG2;
uniform float uMiePhaseBlend;
uniform float uMieDirtiness;

// ── Absorption band (ozone / CH4 / H2S) ──
uniform vec3 uOzoneAbsorption;
uniform float uOzoneCenter;
uniform float uOzoneWidth;

// ── Fog / multiple scattering ──
uniform vec3 uFogColor;
uniform float uFogDensity;
uniform float uFogScaleHeight;
uniform float uFogPatchiness;

// ── Clouds ──
uniform vec3 uCloudColor;
uniform float uCloudCoverage;
uniform float uCloudDensity;
uniform float uCloudAltitude;
uniform float uCloudBumpiness;
uniform sampler2D uCloudNoiseMap;
uniform bool uHasCloudTexture;
// Cloud overlay texture (Venus-class opaque-deck terrestrials only).
// When uHasCloudOverlay == 1, the cloud lighting block samples this for
// the per-pixel cloud tint instead of using the flat uCloudColor. The
// texture is produced by the gas-giant bake shader in swirl mode with a
// palette derived from uCloudColor — so the deck still reads as the
// planet's intrinsic cloud colour, but with swirling banded structure
// painted across it. sRGB-encoded; decode with pow(., 2.2) on sample.
uniform sampler2D uCloudOverlayTexture;
uniform int uHasCloudOverlay;

// ── Rings ──
uniform bool uHasRings;
uniform float uRingInner;       // planet radii (e.g. 1.3)
uniform float uRingOuter;       // planet radii (e.g. 2.5)
uniform float uRingOpacity;     // [0,1]
uniform vec3 uRingColors[5];    // linear RGB
uniform int uRingColorCount;
uniform int uRingGapCount;      // 0–4
uniform float uRingDustiness;   // [0,1] thin/wispy vs solid/opaque
uniform float uRingSeed;

// ── Rendering ──
uniform float uCameraExposure;
uniform float uTime;
uniform float uCloudDriftSpeed;  // radians/sec — rotation rate of the cloud layer
uniform int uTidallyLocked;      // 1 → substellar-aligned bullseye cloud frame; 0 → standard zonal
uniform float uAmbientLight;     // [0, 1] flat baseline lighting (debug/inspection toggle).
                                 // Adds an unshaded multiplier of the surface and cloud
                                 // colour so the night side reads uniformly without
                                 // casting shadows. 0 = off (production default).

// ── Post-process gating ──
// 0 = write sRGB + tonemapped to the default framebuffer (direct path)
// 1 = write linear HDR for the bloom pipeline; tonemap + gamma happen in the composite pass
uniform int uPostprocess;

// ── Ray march steps (uniforms so the GLSL compiler can't unroll the loops;
// non-constant bounds turn the break statement at the end of the view-march
// into a real per-wavefront early exit). Driven from PlanetRenderer based on
// atmosphere thickness — thin envelopes use fewer steps, thick sub-Neptunes
// keep 16 to avoid banding. ──
uniform int uISteps;
uniform int uJSteps;

// ── Reveal multiplier. 0 hides the planet entirely (used while the bake is
// pending and on the bake frame itself); 1 renders at full brightness. The
// renderer ramps this 0→1 over 200ms once the bake completes so the planet
// fades up from black instead of popping in untextured. ──
uniform float uReveal;

// ────────────────────────────────────────────────────────────────────────────
// Noise functions
// ────────────────────────────────────────────────────────────────────────────

float hash3(vec3 p) {
    vec3 q = fract(p * vec3(0.1031, 0.1030, 0.0973));
    q += dot(q, q.yxz + 33.33);
    return fract((q.x + q.y) * q.z);
}

float noise(vec3 x) {
    vec3 p = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash3(p);
    float n100 = hash3(p + vec3(1.0, 0.0, 0.0));
    float n010 = hash3(p + vec3(0.0, 1.0, 0.0));
    float n110 = hash3(p + vec3(1.0, 1.0, 0.0));
    float n001 = hash3(p + vec3(0.0, 0.0, 1.0));
    float n101 = hash3(p + vec3(1.0, 0.0, 1.0));
    float n011 = hash3(p + vec3(0.0, 1.0, 1.0));
    float n111 = hash3(p + vec3(1.0, 1.0, 1.0));
    return mix(
        mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
        mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y),
        f.z
    );
}

mat3 m3 = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);

float fbm(vec3 p) {
    float f = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 3; i++) {
        f += amp * noise(p);
        p = m3 * p * 2.0;
        amp *= 0.5;
    }
    return f;
}

// ────────────────────────────────────────────────────────────────────────────
// Fog patchiness
// ────────────────────────────────────────────────────────────────────────────

float getFogMask(vec3 p, float time, float patchiness) {
    if (patchiness <= 0.0) return 1.0;
    vec3 unitP = normalize(p);
    float fNoise = fbm(unitP * 2.5 + vec3(time * 0.02));
    float threshold = patchiness * 0.65;
    float mask = smoothstep(threshold, threshold + 0.15, fNoise);
    float boost = 1.0 + patchiness * 1.5;
    return mask * boost;
}

// ────────────────────────────────────────────────────────────────────────────
// Baked cloud noise sampling (equirectangular texture lookup)
// ────────────────────────────────────────────────────────────────────────────

float sampleCloudNoise(vec3 dir) {
    // Rotate sampling direction for zonal cloud drift, then equirectangular
    // texture lookup. The baked texture is static; rotating the lookup
    // direction makes clouds drift without re-baking.
    //
    // Standard mode: rotate around the world Y axis (zonal banding around
    // the spin pole — Earth/Jupiter convention).
    //
    // Tidally-locked mode: rotate around the substellar axis (== uSunDirection
    // for a locked planet). Before the texture lookup, we transform the
    // sample direction into a substellar-aligned frame so the texture's V
    // coordinate (its "latitude" axis) maps to the angle from the substellar
    // point. The bake's polar regions then read at sub-/anti-stellar; its
    // equatorial bands read at the terminator ring.
    vec3 d;
    if (uTidallyLocked == 1) {
        // Orthonormal basis with Y == substellar direction.
        vec3 yAxis = uSunDirection;
        vec3 ref = abs(yAxis.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        vec3 xAxis = normalize(cross(ref, yAxis));
        vec3 zAxis = cross(yAxis, xAxis);
        vec3 dLocal = vec3(dot(dir, xAxis), dot(dir, yAxis), dot(dir, zAxis));
        // Drift around the substellar axis (== Y in the local frame).
        float angle = uTime * uCloudDriftSpeed;
        float ca = cos(angle);
        float sa = sin(angle);
        d = vec3(dLocal.x * ca + dLocal.z * sa, dLocal.y, -dLocal.x * sa + dLocal.z * ca);
    } else {
        float angle = uTime * uCloudDriftSpeed;
        float ca = cos(angle);
        float sa = sin(angle);
        d = vec3(dir.x * ca + dir.z * sa, dir.y, -dir.x * sa + dir.z * ca);
    }
    float u = 1.0 - (atan(d.z, d.x) + 3.14159265) / (2.0 * 3.14159265);
    float v = (asin(clamp(d.y, -1.0, 1.0)) + 1.57079632) / 3.14159265;
    return texture(uCloudNoiseMap, vec2(u, v)).r;
}

// Cloud overlay sampling. Mirrors sampleCloudNoise's UV transform —
// including the substellar-aligned frame for tidally-locked worlds and
// the cloud-drift rotation — so the overlay tint moves with the cloud
// noise pattern. Decodes sRGB to linear (the bake writes pow(rgb, 1/2.2))
// so the result composes correctly with the rest of planet.frag's linear
// HDR pipeline.
vec3 sampleCloudOverlay(vec3 dir) {
    vec3 d;
    if (uTidallyLocked == 1) {
        vec3 yAxis = uSunDirection;
        vec3 ref = abs(yAxis.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        vec3 xAxis = normalize(cross(ref, yAxis));
        vec3 zAxis = cross(yAxis, xAxis);
        vec3 dLocal = vec3(dot(dir, xAxis), dot(dir, yAxis), dot(dir, zAxis));
        float angle = uTime * uCloudDriftSpeed;
        float ca = cos(angle);
        float sa = sin(angle);
        d = vec3(dLocal.x * ca + dLocal.z * sa, dLocal.y, -dLocal.x * sa + dLocal.z * ca);
    } else {
        float angle = uTime * uCloudDriftSpeed;
        float ca = cos(angle);
        float sa = sin(angle);
        d = vec3(dir.x * ca + dir.z * sa, dir.y, -dir.x * sa + dir.z * ca);
    }
    float u = 1.0 - (atan(d.z, d.x) + 3.14159265) / (2.0 * 3.14159265);
    float v = (asin(clamp(d.y, -1.0, 1.0)) + 1.57079632) / 3.14159265;
    return pow(texture(uCloudOverlayTexture, vec2(u, v)).rgb, vec3(2.2));
}

// ────────────────────────────────────────────────────────────────────────────
// Ring system helpers
// ────────────────────────────────────────────────────────────────────────────

// Procedural ring density at radial distance r (in planet radii).
// Multi-frequency noise creates natural band structure; gapCount carves
// Cassini-like divisions. dustiness controls whether the threshold yields
// solid Saturn-style bands (low) or thin Uranus-style ringlets (high).
float getRingDensity(float r) {
    float t = (r - uRingInner) / (uRingOuter - uRingInner);
    float s = uRingSeed;
    // Multi-scale radial structure
    float n1 = noise(vec3(t * 40.0, s * 7.3, 0.0));          // fine bands
    float n2 = noise(vec3(t * 15.0, s * 3.1, 1.0));          // coarse envelope
    float n3 = noise(vec3(t * 80.0 + s * 11.0, 0.0, 2.0));   // very fine detail
    float n4 = noise(vec3(t * 200.0 + s * 19.7, 0.0, 3.0));  // micro ringlets
    float coarse = n2 * 0.45 + n1 * 0.35;
    float micro = (n3 - 0.5) * 0.4 + (n4 - 0.5) * 0.3;  // signed detail carves into coarse
    float structure = clamp(coarse + micro, 0.0, 1.0);
    // Deterministic gaps — each gap gets a random width from narrow Cassini-like
    // divisions (2%) up to large breakup gaps (18%) that visually split the ring.
    for (int i = 0; i < 4; i++) {
        if (i >= uRingGapCount) break;
        float gapCenter = hash3(vec3(float(i) * 17.3 + s, s * 2.1, 0.0));
        float gapWidth = 0.02 + 0.16 * pow(hash3(vec3(float(i) * 31.7 + s, s * 4.7, 1.0)), 1.5);
        structure *= smoothstep(0.0, gapWidth * 0.5, abs(t - gapCenter));
    }
    // Fade at inner/outer edges
    structure *= smoothstep(0.0, 0.06, t) * smoothstep(1.0, 0.94, t);
    // Dustiness raises the density threshold: low = solid bands, high = thin ringlets
    float threshold = mix(0.15, 0.45, uRingDustiness);
    return smoothstep(threshold, threshold + 0.15, structure) * uRingOpacity;
}

// Interpolate ring color palette at radial position with micro variation
vec3 getRingColor(float r) {
    if (uRingColorCount <= 1) return uRingColors[0];
    float t = (r - uRingInner) / (uRingOuter - uRingInner);
    float ci = t * float(uRingColorCount - 1);
    int i0 = clamp(int(floor(ci)), 0, uRingColorCount - 2);
    vec3 base = mix(uRingColors[i0], uRingColors[i0 + 1], fract(ci));
    // High-frequency brightness/hue variation matching density micro detail
    float v1 = noise(vec3(t * 80.0 + uRingSeed * 11.0, 0.0, 4.0));
    float v2 = noise(vec3(t * 200.0 + uRingSeed * 19.7, 0.0, 5.0));
    float brightness = 1.0 + (v1 - 0.5) * 0.45 + (v2 - 0.5) * 0.3;
    return base * brightness;
}

// Ring shadow on planet surface: trace sun ray from surface point to Y=0
// ring plane. Returns 1.0 (no shadow) or attenuated value. Takes the sun
// direction explicitly so circumbinary planets can compose shadows from
// both stars by multiplying the two returned factors.
float getRingShadow(vec3 surfPosKm, vec3 sunDir) {
    if (!uHasRings || abs(sunDir.y) < 1e-6) return 1.0;
    float tSun = -surfPosKm.y / sunDir.y;
    if (tSun <= 0.0) return 1.0;
    vec3 shadowHit = surfPosKm + sunDir * tSun;
    float shadowR = length(shadowHit.xz) / uPlanetRadius;
    if (shadowR < uRingInner || shadowR > uRingOuter) return 1.0;
    return 1.0 - getRingDensity(shadowR);
}

// Planet shadow on ring: does the sun ray from a ring point hit the sphere?
bool ringInPlanetShadow(vec3 ringPosKm, vec3 sunDir) {
    float b = dot(ringPosKm, sunDir);
    float c = dot(ringPosKm, ringPosKm) - uPlanetRadius * uPlanetRadius;
    float disc = b * b - c;
    return disc >= 0.0 && (-b - sqrt(disc)) > 0.0;
}

// Perceptual ring opacity from raw density. Linear HDR mix lets bright
// sources (the star disc, exposure-boosted glow) bleed through moderate
// `ringDens` because attenuating an HDR value of e.g. 50 by 50% still
// saturates after Reinhard. Steepening to 1 − (1 − d)^k keeps thin
// sections translucent while making dense sections properly opaque.
// k = 2.5 maps:  0.1 → 0.23  •  0.4 → 0.72  •  0.7 → 0.95  •  0.9 → 0.997
// Matches real ring optical depth (Saturn B-ring τ ≈ 1.5 → ~78% opacity).
float ringOpacity(float ringDens) {
    return 1.0 - pow(1.0 - clamp(ringDens, 0.0, 1.0), 2.5);
}

// Chandrasekhar single-scattering reflectance for an optically-finite
// ring layer — a closed-form solution to volumetric light transport
// through a horizontal scattering slab, derived for thin rings by
// Cuzzi et al. for the Cassini imaging team. Replaces the previous
// flat `|sun.y|` cosine, which gave rings identical brightness at every
// view angle and read as static / painted-on compared to a real ring
// system.
//
//   I/F ∝ (μ₀ / (μ₀ + μ)) × (1 − exp(−τ × (1/μ₀ + 1/μ)))
//
// where μ₀ = sin(solar elevation above ring plane), μ = sin(viewer
// elevation), τ = optical depth at normal incidence. The path-length
// factor `(1/μ₀ + 1/μ)` is what makes the ring brighten near edge-on
// viewing: as the viewer drops toward the ring plane, the line of
// sight passes through 1/μ × more material per pixel, so an optically
// thin ring effectively integrates many particles and saturates toward
// its color. Optically thick portions saturate at the
// `μ₀ / (μ₀ + μ)` prefactor (which is essentially the same
// Lommel-Seeliger term we use for airless surfaces, since the same
// physics — first-order reflection from a scattering medium — applies).
//
// μ₀ and μ are floored at 0.05 to keep the 1/μ term finite at exact
// edge-on. `tau` here is `ringDens × 1.5` so a dense `ringDens = 1.0`
// patch lands at Saturn-B-ring τ ≈ 1.5; thin Uranus-style sections at
// ringDens ≈ 0.1 land at τ ≈ 0.15, low enough that the shallow-view
// brightening is visually dramatic.
float ringPhotometric(vec3 sunDir, vec3 viewDir, float ringDens) {
    float mu0 = max(abs(sunDir.y), 0.05);
    float mu = max(abs(viewDir.y), 0.05);
    float tau = clamp(ringDens, 0.0, 1.0) * 1.5;
    float saturation = 1.0 - exp(-tau * (1.0 / mu0 + 1.0 / mu));
    return saturation * mu0 / (mu0 + mu);
}

// ────────────────────────────────────────────────────────────────────────────
// Ray-sphere intersection (numerically stable)
// ────────────────────────────────────────────────────────────────────────────

vec2 rsi_stable(vec3 pDir, float hPos, vec3 rd, float targetAlt) {
    float R = uPlanetRadius;
    float R0 = R + hPos;
    float cosTheta = dot(pDir, rd);
    float b = R0 * cosTheta;
    float C = 2.0 * R * (hPos - targetAlt) + (hPos * hPos) - (targetAlt * targetAlt);
    float D = b * b - C;
    if (D < 0.0) return vec2(-1.0, -1.0);
    float sqrtD = sqrt(D);
    float q = (b < 0.0) ? (-b + sqrtD) : (-b - sqrtD);
    float t0 = q;
    float t1 = (abs(q) > 1e-7) ? (C / q) : 0.0;
    return vec2(min(t0, t1), max(t0, t1));
}

// ────────────────────────────────────────────────────────────────────────────
// Altitude from ray parameter
// ────────────────────────────────────────────────────────────────────────────

float getAltitude(float t, float startAlt, vec3 pDir, vec3 rayDir) {
    float R0 = uPlanetRadius + startAlt;
    float cosTheta = dot(pDir, rayDir);
    float X = 2.0 * uPlanetRadius * startAlt + startAlt * startAlt + t * t + 2.0 * R0 * t * cosTheta;
    float R = uPlanetRadius;
    return X / (sqrt(max(R * R + X, 0.0)) + R);
}

// ────────────────────────────────────────────────────────────────────────────
// ACES tone mapping
// ────────────────────────────────────────────────────────────────────────────

vec3 tonemap(vec3 x) {
    // Reinhard on max channel — preserves color ratios exactly.
    // Neutral: no S-curve, no desaturation, no shoulder artifacts.
    float maxC = max(max(x.r, x.g), x.b);
    if (maxC <= 0.0) return vec3(0.0);
    float mapped = maxC / (1.0 + maxC);
    return x * (mapped / maxC);
}

// Procedural diffraction spikes — sum of very soft sin pulses at
// irregular frequencies and phases. The pow exponents are intentionally
// low to maximize blur: rays read as wide diffuse smears with overlap
// between neighbours, building up a hazy glow with subtle directional
// structure rather than discrete lines.
float starSpikeIntensity(float theta) {
    float s = 0.0;
    // Cores — extra-soft. Exponents lowered another step so even the
    // sharpest contributors read as a wide diffuse smear rather than a
    // defined line. Lower `pow` exponent on `|sin|` widens the ray and
    // softens its falloff toward the inter-ray gaps.
    s += pow(abs(sin(theta * 11.0 + 0.7)),  3.0) * 1.0;
    s += pow(abs(sin(theta * 17.0 + 2.3)),  4.0) * 0.7;
    s += pow(abs(sin(theta * 23.0 + 4.1)),  2.5) * 0.5;
    s += pow(abs(sin(theta * 31.0 + 1.5)),  4.5) * 0.4;
    s += pow(abs(sin(theta *  7.0 + 5.2)),  2.0) * 0.6;
    s += pow(abs(sin(theta * 41.0 + 3.4)),  5.0) * 0.3;
    // Halos — near-uniform diffuse fill, only the faintest directional bias.
    s += pow(abs(sin(theta * 11.0 + 0.7)),  1.0) * 0.50;
    s += pow(abs(sin(theta *  7.0 + 5.2)),  0.7) * 0.55;
    s += pow(abs(sin(theta * 17.0 + 2.3)),  1.2) * 0.45;
    return s;
}

// Returns 0 (fully occluded by planet) → 1 (fully visible) for the sun
// direction relative to the planet sphere. Closest-approach test of the
// camera→sun line against the planet centre, with the smoothstep range
// scaled to match the actual angular size of the disc — close-orbiting
// systems where the disc is huge (Janssen at 0.015 AU sees a ~17° disc)
// keep spikes alive until the disc is truly fully behind the limb;
// distant systems with a pinprick disc transition sharply right at the
// geometric limb. Without this scaling, a fixed range like (0.90, 1.10)
// either fades too early on giant discs (spikes gone while the disc is
// still mostly visible) or extends fade past the disc on tiny ones
// (spikes linger after the disc is long since covered).
float computeSunOcclusion(vec3 sunDir, float sunSize, float sunDistanceAU) {
    float tClosest = -dot(uCameraPos, sunDir);
    if (tClosest <= 0.0) return 1.0;
    vec3 closestPt = uCameraPos + tClosest * sunDir;
    float ratio = length(closestPt) / max(uPlanetRadius, 1.0);
    // Sun angular radius in `ratio` units. Derivation: for small angles,
    // angular separation α ≈ closestApproach / cameraDist, and planet
    // angular radius ≈ uPlanetRadius / cameraDist. Their ratio is just
    // `closestApproach / uPlanetRadius` — exactly our `ratio`. So the
    // disc's angular radius in the same units is
    //   sunInRatioUnits = (sunAngularRad × cameraDist) / uPlanetRadius
    float sunAngularRad = (0.00465 * sunSize) / max(sunDistanceAU, 0.001);
    float cameraDist = max(length(uCameraPos), uPlanetRadius);
    float sunInRatioUnits = (sunAngularRad * cameraDist) / max(uPlanetRadius, 1.0);
    // Disc fully covered: ratio < 1 - sunInRatioUnits
    // Disc fully visible: ratio > 1 + sunInRatioUnits
    float lower = 1.0 - sunInRatioUnits;
    float upper = 1.0 + sunInRatioUnits;
    return smoothstep(lower, upper, ratio);
}

// Returns 1.0 when the sun is comfortably inside the camera frustum and
// fades to 0 as it slips off the screen edge. Treats the camera frame as
// an additional occluder — without this, when the disc moves off-screen
// the still-on-screen spike rays would emanate from "nowhere". Smoothstep
// range scales with the disc's NDC half-radius so a giant close-orbit
// disc keeps spikes alive until it's mostly off-screen, while a tiny
// distant disc transitions sharply right at the frame edge.
float computeScreenEdgeFade(vec3 sunDir, float sunSize, float sunDistanceAU) {
    // Camera basis from the inverse view matrix (its columns are the
    // world-space camera right / up / -forward / position vectors).
    vec3 camRight = uInverseView[0].xyz;
    vec3 camUp    = uInverseView[1].xyz;
    vec3 camFwd   = -uInverseView[2].xyz;
    float depth = dot(sunDir, camFwd);
    if (depth <= 0.0) return 0.0;  // sun is behind the camera
    float vx = dot(sunDir, camRight);
    float vy = dot(sunDir, camUp);
    // uInverseProjection's diagonal carries tan(half-fov) for x/y, so
    // dividing recovers NDC-style screen coordinates.
    float ndcX = (vx / depth) / uInverseProjection[0][0];
    float ndcY = (vy / depth) / uInverseProjection[1][1];
    float dEdge = max(abs(ndcX), abs(ndcY));
    // Sun NDC half-radius. An angular extent θ projects to NDC as
    // `tan(θ) / tan(half_fov)`; for small θ that's `θ / invProj[i][i]`.
    // Use the smaller invProj diagonal (= tighter axis, larger NDC
    // coverage) so the worst-case disc extent is captured.
    float sunAngularRad = (0.00465 * sunSize) / max(sunDistanceAU, 0.001);
    float invProjMin = max(min(uInverseProjection[0][0], uInverseProjection[1][1]), 0.0001);
    float sunNdcRadius = sunAngularRad / invProjMin;
    // Disc fully on-screen: dEdge < 1 - sunNdcRadius
    // Disc fully off-screen: dEdge > 1 + sunNdcRadius
    float lower = 1.0 - sunNdcRadius;
    float upper = 1.0 + sunNdcRadius;
    return 1.0 - smoothstep(lower, upper, dEdge);
}

// Builds spike contribution for a given view ray and angular distance
// from sun. `haloAtten` is the existing apparent-brightness scalar
// (0 = dim/distant, 1 = Sun-at-1AU). Spikes peak at moderate apparent
// brightness — bright suns wash them out, very dim suns don't carry
// enough flux to trigger lens artifacts at all. Length shortens as the
// star is occluded by either the planet or the camera frame edge.
vec3 computeStarSpikes(
    vec3 rayDir,
    vec3 sunDir,
    vec3 sunColor,
    float sunIntensity,
    float sunSize,
    float sunDistanceAU,
    float haloAtten
) {
    float sunOcc = computeSunOcclusion(sunDir, sunSize, sunDistanceAU) *
                   computeScreenEdgeFade(sunDir, sunSize, sunDistanceAU);
    if (sunOcc <= 0.001) return vec3(0.0);

    float angle = length(cross(rayDir, sunDir));

    // Distance envelope. Everything from habitable-zone-equivalent
    // brightness (Earth / TRAPPIST-1 e / outer-K-dwarf HZ) through
    // Mars-like apparent flux holds full burst. The TRAPPIST-1
    // system's outer planets — TRAPPIST-1 h at dimness ≈ 0.86 — sit
    // at ~60 % strength so the spikes still read clearly. Past the
    // smoothstep upper bound the drop is fast, hitting ≈ 1 % at
    // Jupiter and effectively zero by Lipperhey-distance.
    //
    // The smoothstep moves the transition; squaring makes the post-
    // threshold drop quadratic so the far end falls off harshly while
    // habitable-zone-equivalent worlds are untouched. The upper bound
    // landed at 0.8 (was briefly 0.7, but that gave only ~20 % at
    // TRAPPIST-1 h — too dim for what's still apparent-bright enough
    // to drive visible diffraction artifacts). Squaring the raw
    // `(1 - dimness)` (no smoothstep) halved the spike contribution
    // for TRAPPIST-1 e and obliterated it for Mars-distance, treating
    // dim-star habitable planets as if they were outer solar system.
    // This formulation keeps them lit.
    float dimness = clamp(1.0 - haloAtten, 0.0, 1.0);
    float distanceFade = pow(1.0 - smoothstep(0.8, 1.0, dimness), 2.0);
    float intensityMod = 0.15 * distanceFade;
    float lengthMod    = 0.72 * distanceFade;
    // Dark-end falloff: at very low apparent flux the eye/lens has too
    // little signal to bloom. Smoothly fades from 0 (sub-threshold) up to
    // full visibility around sunIntensity ≈ 0.1.
    float dimFade = smoothstep(0.005, 0.10, sunIntensity);
    intensityMod *= dimFade;
    lengthMod *= mix(0.4, 1.0, dimFade);

    vec3 axisHelper = abs(sunDir.y) > 0.99 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    vec3 sunRight = normalize(cross(sunDir, axisHelper));
    vec3 sunUp = cross(sunRight, sunDir);
    vec3 perp = rayDir - dot(rayDir, sunDir) * sunDir;
    float theta = atan(dot(perp, sunUp), dot(perp, sunRight));

    // Per-ray length modulation: each ray's reach scales with its own
    // intensity at this θ, so bright pencil cores extend further while
    // dim halo wisps terminate sooner. Cumulative max ≈ 3.0 across the
    // 9 noise terms; normalise to ~0..1.
    float S = starSpikeIntensity(theta);
    float Snorm = clamp(S / 3.0, 0.0, 1.0);
    float perRayLength = 0.40 + 0.60 * Snorm;

    // Max spike length: 0.18 rad (~10°) at full strength × occlusion ×
    // brightness-driven length mod × per-ray length factor.
    float maxAngle = 0.18 * sunOcc * lengthMod * perRayLength;
    float t = clamp(1.0 - angle / max(maxAngle, 0.001), 0.0, 1.0);
    float radial = t * t;

    // Spike brightness drops with sunOcc on a steeper-than-linear curve
    // so brightness fades faster than length. At sunOcc = 0.5, spikes
    // are at ~50% length but only ~17% brightness — the rays fade away
    // visibly while the haze remains, instead of just shrinking uniformly.
    float occBrightness = pow(sunOcc, 2.5);
    float spike = S * radial * intensityMod * occBrightness;

    // Hazy circular halo at the convergence point. Constant angular
    // radius — does NOT shrink with occlusion — and uses linear sunOcc
    // (not the steeper curve) so it stays prominent while the spikes
    // dim, becoming the dominant visible feature during partial cover.
    float hazeT = clamp(1.0 - angle / 0.025, 0.0, 1.0);
    float haze = hazeT * hazeT * 0.6 * intensityMod * sunOcc;

    float total = spike + haze;
    // Blown-out look: bright contributions desaturate toward white the
    // same way the disc does. Cap reduced so dim/distant suns don't go
    // full white.
    vec3 spikeColor = mix(sunColor, vec3(1.0), clamp(total * 0.45, 0.0, 0.65));
    // Pre-exposure HDR — scale by sunIntensity so writeFinal's exposure
    // multiply (≈20/totalIntensity) leaves a normalized post-tonemap value.
    return total * spikeColor * sunIntensity * 0.01;
}

// Star disc — geometry-bound bright source in the sky. Lives in the sky
// paths (airless + atmosphere) so it's occluded by the planet silhouette
// and dimmed by atmospheric optical depth, same as any sky element.
// Limb darkening applies to brightness; chromaticity is held at the
// saturated near-white tint across the whole disc because real eyes
// and cameras saturate the entire luminance-overflowing surface to
// near-white. The outer-edge saturation blowout is a separate optical
// effect — see [computeStarBlowout], rendered in [writeFinal] above
// everything.
vec3 computeStarDisc(
    vec3 rayDir,
    vec3 sunDir,
    vec3 sunColor,
    float sunIntensity,
    float sunSize,
    float sunDistanceAU
) {
    float sunDot = dot(rayDir, sunDir);
    if (sunDot <= 0.0) return vec3(0.0);
    float angle = length(cross(rayDir, sunDir));
    float angularRadius = (0.00465 * sunSize) / max(sunDistanceAU, 0.001);
    float drawRadius = max(angularRadius, 0.0008);

    float edge = drawRadius * 0.15;
    float disk = smoothstep(drawRadius + edge, drawRadius - edge, angle);
    float limbFactor = clamp(angle / drawRadius, 0.0, 1.0);
    vec3 saturatedTint = mix(sunColor, vec3(1.0), 0.95);
    vec3 starDiskColor = saturatedTint * (1.0 - 0.3 * limbFactor * limbFactor);
    vec3 starLight = sunColor * sunIntensity;
    return disk * starLight * 2.0 * starDiskColor;
}

// Outer-edge saturation blowout. Camera/eye sensor saturation around
// very bright point sources produces a soft halo that bleeds the disc
// brightness into the immediately-surrounding sky — visually fills the
// gap between the geometric disc and the broader [computeStarCorona]
// glow.
//
// Rendered as a foreground optical effect in [writeFinal] above
// everything (atmosphere, planet silhouette, rings), same as the
// diffraction spikes — it represents a camera/eye response to the
// star's apparent brightness, not a sky element. `computeSunOcclusion`
// + screen-edge fade gate it so when the planet fully occludes the
// sun (or the sun is off-screen) the blowout vanishes alongside the
// corona/spikes.
//
// Extent: exp falloff with characteristic distance `0.6 × drawRadius`
// — tightly hugs the disc edge so the visible halo only extends ~2× the
// disc radius past the limb. Strength fades to zero as `drawRadius`
// grows past ~3° because at that point the disc fills the frame and
// there isn't an "outside" for the blowout to bloom into.
vec3 computeStarBlowout(
    vec3 rayDir,
    vec3 sunDir,
    vec3 sunColor,
    float sunIntensity,
    float sunSize,
    float sunDistanceAU
) {
    float sunDot = dot(rayDir, sunDir);
    if (sunDot <= 0.0) return vec3(0.0);

    float sunOcc = computeSunOcclusion(sunDir, sunSize, sunDistanceAU) *
                   computeScreenEdgeFade(sunDir, sunSize, sunDistanceAU);
    if (sunOcc <= 0.001) return vec3(0.0);

    float angle = length(cross(rayDir, sunDir));
    float angularRadius = (0.00465 * sunSize) / max(sunDistanceAU, 0.001);
    float drawRadius = max(angularRadius, 0.0008);

    // Tight exponential halo: extent scales with disc radius but stays
    // close-in (decay distance 0.6 × drawRadius). `pastEdge` is clamped
    // to zero inside the disc, so the blowout sits at full strength
    // across the entire disc interior and only begins decaying past
    // the limb.
    //
    // The blowout is INTENTIONALLY NOT masked away inside the geometric
    // disc — the disc itself (rendered in the sky path) is occluded by
    // the planet during partial/total eclipse, and without a foreground
    // glare term inside the disc area you'd see a planet-shaped black
    // "hole" where the sun direction is. With the blowout filling the
    // disc region, partial occlusion shows a bright glare smeared
    // through the silhouette that fades naturally via `occBrightness`
    // as the planet covers more of the sun. When the disc is fully
    // visible, the additional brightness at the disc centre tonemaps
    // to the same near-white that the disc itself already produced —
    // no visible change to an un-occluded sun.
    float blowoutScale = drawRadius * 0.6;
    float pastEdge = max(angle - drawRadius, 0.0);
    float blowout = exp(-pastEdge / blowoutScale);

    // Close-in fade: kicks in much earlier than before. Habitable-zone
    // geometries (drawRadius < ≈ 0.6°) get the full halo; hot-orbit
    // worlds (1° onwards) fade rapidly; lava worlds (3°+) show no
    // surrounding blowout at all.
    float blowoutStrength = 1.0 - smoothstep(0.01, 0.05, drawRadius);

    // Sub-threshold dim fade and occlusion mirror the spike/corona
    // envelopes so all three optical effects transition together.
    float dimFade = smoothstep(0.005, 0.10, sunIntensity);
    float occBrightness = pow(sunOcc, 2.5);

    vec3 saturatedTint = mix(sunColor, vec3(1.0), 0.95);
    vec3 starLight = sunColor * sunIntensity;
    return blowout * starLight * saturatedTint
         * 0.4 * blowoutStrength * dimFade * occBrightness;
}

// Star corona / glow contribution applied AFTER the planet has been
// composed — sits in front of the planet surface (and rings, atmosphere)
// the same way the diffraction spikes do. Uses the exact same
// `sunOcc = computeSunOcclusion() * computeScreenEdgeFade()` thresholds
// as the spike helper so corona, spikes, and edge-fade all transition
// together when the sun goes behind the planet limb or off-screen.
//
// As `sunOcc` drops:
//   • brightness fades on `pow(sunOcc, 2.5)` (matches spike brightness curve)
//   • visible angular extent shrinks — the exponential falloff spread is
//     divided by `sunOcc`, so the glow doesn't just dim, it also pulls
//     in tighter to the disc, mirroring spike length scaling.
vec3 computeStarCorona(
    vec3 rayDir,
    vec3 sunDir,
    vec3 sunColor,
    float sunIntensity,
    float sunSize,
    float sunDistanceAU,
    float haloAtten
) {
    float sunDot = dot(rayDir, sunDir);
    if (sunDot <= 0.0) return vec3(0.0);
    float sunOcc = computeSunOcclusion(sunDir, sunSize, sunDistanceAU) *
                   computeScreenEdgeFade(sunDir, sunSize, sunDistanceAU);
    if (sunOcc <= 0.001) return vec3(0.0);

    float angle = length(cross(rayDir, sunDir));
    float glowSpread = 150.0 * max(1.0, sqrt(sunDistanceAU));
    // Sub-1.0 sunOcc divides the spread → glow tightens to the disc.
    float occInv = 1.0 / max(sunOcc, 0.05);
    // Angle-based exponential (was distance-from-disc-edge) — gives a
    // smooth Gaussian-like peak at the sun direction that fades naturally
    // outward through the halo region. The earlier `max(0, angle -
    // drawRadius)` clipping created a flat plateau across the disc area,
    // which read as a hard-edged solid disc when the planet occluded the
    // sun (corona is now in front of the planet, so any plateau leaks
    // through the silhouette). Without the clip, brightness peaks at the
    // sun centre and decays smoothly — the corona reads as a soft point
    // light during partial occlusion instead of a hollow ring.
    float glow = exp(-angle * (glowSpread * occInv));
    float opticalFlare = exp(-angle * (300.0 * occInv)) * 0.5
                       + exp(-angle * (50.0 * occInv)) * 0.1;

    float occBrightness = pow(sunOcc, 2.5);

    vec3 starLight = sunColor * sunIntensity;
    vec3 contrib = vec3(0.0);
    contrib += glow * starLight * 0.15 * haloAtten * occBrightness;
    contrib += opticalFlare * sunColor * sunIntensity * 0.5 * haloAtten * occBrightness;
    return contrib;
}

// Hapke / Lommel-Seeliger diffuse factor for airless rocky bodies.
// Lambert reflectance is proportional to μ₀ = N·L. That gives a smooth
// brightness fall-off from subsolar point to terminator, which reads
// correctly for atmospheric worlds but wrong for the Moon: a real lunar
// disc looks almost uniformly bright out to the terminator because
// regolith back-scatters more strongly toward the light source.
// Lommel-Seeliger captures the dominant first-order regolith effect:
//   I/F ∝ μ₀ / (μ₀ + μ)     where μ = N·V (cos viewing angle).
// At opposition (subsolar = subobserver) this gives 0.5 — matching
// Lambert × 0.5 — so we multiply by 2 to keep the subsolar brightness
// at the Lambert level. The result is a much flatter disc.
// Returns a multiplier intended to replace the raw Lambertian factor.
float hapkeDiffuse(float nDotL, float nDotV) {
    float lambert = nDotL;
    float ls = 2.0 * nDotL / max(nDotL + nDotV, 1e-4);
    return mix(lambert, ls, uHapkeStrength);
}

// Opposition surge — the sharp brightness spike a regolith body shows
// near phase angle 0 (full-Moon configuration: observer between sun
// and planet). Lommel-Seeliger alone doesn't capture this; real
// regolith has a coherent-back-scatter peak from shadow-hiding and
// constructive wave interference near anti-illumination geometry,
// adding ~25–60 % brightness at exact opposition over a narrow ~5°
// FWHM. Approximated as an exponential decay in phase angle.
// Gated by uHapkeStrength so atmospheric worlds receive no surge —
// the effect only makes physical sense for the same regolith bodies
// that get the Hapke diffuse term in the first place.
//   sunDir, viewDir: unit vectors from surface point toward sun /
//                    observer respectively.
// Returns a multiplier ≥ 1.0 to apply to the diffuse term.
float oppositionSurge(vec3 sunDir, vec3 viewDir) {
    if (uHapkeStrength <= 0.0) return 1.0;
    float cosAlpha = clamp(dot(sunDir, viewDir), -1.0, 1.0);
    float alpha = acos(cosAlpha);
    // Peak amplitude 0.5 (50 % brighter at exact opposition); width
    // 0.10 rad ≈ 5.7° so the surge is invisible past mid-quarter
    // phase, matching the lunar opposition-effect FWHM. Both scaled
    // by uHapkeStrength so the surge fades in with the Hapke diffuse
    // path rather than appearing as a discontinuous boost.
    return 1.0 + uHapkeStrength * 0.5 * exp(-alpha / 0.10);
}

// Surface specular contribution — the concentrated star reflection users
// see on seas as "sun glint". Auto-detects surface type from base colour:
//   • Water: blue-dominant pixels. Tight bright glint, fresnel-modulated.
//   • Ice  : bright near-white pixels. Slightly broader, brighter head-on.
//   • Rock : everything else lit. Broad, weak diffuse gloss.
// Each material type has its own Phong exponent and intensity so the same
// scene can show all three side-by-side (mixed worlds: ice caps + ocean
// + continents). Returns pre-exposure HDR contribution; caller adds it to
// the surface colour before tonemap.
vec3 surfaceSpecular(
    vec3 baseColor,
    vec3 surfaceNormal,
    vec3 viewDir,
    vec3 sunDir,
    float nDotL,
    vec3 sunLight,
    vec3 sunAtten
) {
    if (nDotL <= 0.0 || uWaterSpecular <= 0.0) return vec3(0.0);

    vec3 halfVec = normalize(viewDir + sunDir);
    float nDotH = max(dot(surfaceNormal, halfVec), 0.0);
    float nDotV = max(dot(surfaceNormal, viewDir), 0.0);

    // Surface-type masks. Water and ice are mutually suppressed; rock is
    // whatever remains of lit, non-dark surfaces.
    float waterMask = smoothstep(0.05, 0.22, baseColor.b - baseColor.r);
    float minC = min(min(baseColor.r, baseColor.g), baseColor.b);
    float iceMask = smoothstep(0.45, 0.65, minC) * (1.0 - waterMask);
    // Rock: lit, not water, not ice, not lava-dark. Suppress on very dark
    // surfaces (basalt, lava crust) where soft gloss would read wrong.
    float rockMask = (1.0 - waterMask) * (1.0 - iceMask) *
                     smoothstep(0.10, 0.25, minC);

    vec3 spec = vec3(0.0);

    // Water sun glint: tight highlight (Phong 80), Fresnel-biased so the
    // reflection brightens at grazing angles, with a meaningful head-on
    // component (0.10 base) so the glint is visible from any viewing
    // angle, not just terminator. Multiplied 8× because the underlying
    // surfaceSunLight is small (uSunIntensity × 0.05).
    if (waterMask > 0.0) {
        float fresnel = 0.10 + 0.90 * pow(1.0 - nDotV, 4.0);
        float w = pow(nDotH, 80.0) * fresnel;
        spec += sunLight * sunAtten * w * waterMask * nDotL * 8.0;
    }

    // Ice highlights: a touch broader than water (Phong 50) since ice
    // surfaces aren't perfectly smooth, with a stronger head-on Fresnel
    // base because ice reflects more diffusely than mirror-flat water.
    if (iceMask > 0.0) {
        float fresnel = 0.25 + 0.75 * pow(1.0 - nDotV, 3.0);
        float i = pow(nDotH, 50.0) * fresnel;
        spec += sunLight * sunAtten * i * iceMask * nDotL * 6.0;
    }

    // Rock gloss: very broad lobe (Phong 12), no Fresnel, low strength.
    // Reads as a soft sheen on the lit hemisphere rather than a discrete
    // highlight — captures real rocky-world reflection (e.g. damp stone,
    // mineral facets) without claiming a mirror finish.
    if (rockMask > 0.0) {
        float r = pow(nDotH, 12.0);
        spec += sunLight * sunAtten * r * rockMask * nDotL * 0.10;
    }

    // uWaterSpecular acts as the global on/off gate plus per-world
    // strength scaler (preset screens dial it for tweaking).
    return spec * uWaterSpecular;
}

// Computes the pre-exposure HDR value to inject so that, after
// uCameraExposure × Reinhard tonemap + gamma, the screen-space output
// is approximately `baseColor × uAmbientLight` for pixels whose only
// contribution is this term — i.e. consistent visible lift in display
// space regardless of `uCameraExposure`. The user's intuition was right:
// targeting the post-tonemap pixel directly is the only way to match
// brightness across worlds whose `uCameraExposure` ranges over 8+ orders
// of magnitude (KOI-55 b ≈ 1e-6, GJ 680 b ≈ 5e3). For pixels with other
// lighting, the Reinhard non-linearity naturally compresses the ambient
// addition — bright day-side pixels barely shift, dark night-side
// pixels lift up to ≈ baseColor in display space, and the terminator
// fades smoothly between the two without an explicit mask.
vec3 hdrAmbient(vec3 baseColor) {
    if (uAmbientLight <= 0.0) return vec3(0.0);
    // Peak ambient = half the day-side intensity. Stronger than that
    // overloads the Reinhard tonemap and washes out the directional
    // Rayleigh scatter at the terminator. The 0.5 factor is applied
    // inside the gamma decode so it's perceptual-linear (display-space
    // half), not photometric half — matters because Reinhard outputs
    // are perceived non-linearly.
    float perceptual = uAmbientLight * 0.5;
    // Target post-tonemap (linear LDR) value: gamma-decode baseColor and
    // gamma-decode the ambient strength.
    vec3 linearLDR = baseColor * pow(perceptual, 2.2);
    // Cap below 1 so the inverse Reinhard doesn't explode.
    linearLDR = min(linearLDR, vec3(0.97));
    // Inverse Reinhard: V/(1+V) = linearLDR  ⟹  V = LDR/(1−LDR).
    vec3 V = linearLDR / max(vec3(1.0) - linearLDR, vec3(0.03));
    // Pre-divide by exposure so writeFinal's `col *= uCameraExposure`
    // restores V before tonemap.
    return V / max(uCameraExposure, 1e-6);
}

// Final output helper. Applies exposure, then either writes linear HDR
// (uPostprocess == 1, for the bloom pipeline) or tonemap + sRGB (direct path).
// Diffraction spikes are added LAST, in front of everything (including
// the planet surface) — they're an optical/lens artifact, not a sky
// element, so they should overlay the entire frame.
void writeFinal(vec3 col, vec3 rayDir, float ringStarOcclusion) {
    // Ring occlusion of the star's foreground glare: when a visible ring
    // sits between this pixel's view ray and the (effectively at-infinity)
    // star, the corona and diffraction spikes get dimmed by the ring's
    // opacity. The star disc itself is already mixed with the ring via the
    // existing `mix(col, ringLit, ringOpacity)` blends in the sky paths,
    // so we only need to attenuate the corona/spikes which are added here.
    // Ring is translucent in most bands, so this is a soft dim rather than
    // a hard cut — passes match real Saturnian ring transit visuals where
    // the sun's glare softens as the rings cross in front but never
    // vanishes entirely except through the densest bands.
    float ringAtten = 1.0 - ringStarOcclusion;

    float haloAtten1 = clamp(uSunIntensity / 40.0, 0.0, 1.0);
    col += computeStarCorona(rayDir, uSunDirection, uSunColor, uSunIntensity,
                             uSunSize, uSunDistanceAU, haloAtten1) * ringAtten;
    col += computeStarBlowout(rayDir, uSunDirection, uSunColor, uSunIntensity,
                              uSunSize, uSunDistanceAU) * ringAtten;
    col += computeStarSpikes(rayDir, uSunDirection, uSunColor, uSunIntensity,
                             uSunSize, uSunDistanceAU, haloAtten1) * ringAtten;

    // Secondary star (circumbinary planets): same overlays at the second
    // sun's apparent position. Each star carries its own corona/blowout/
    // spike pattern in its own colour and intensity — the user sees two
    // distinct glares with the SMA-derived angular separation, matching
    // the rest of the lighting model.
    if (uHasSecondarySun == 1) {
        float haloAtten2 = clamp(uSun2Intensity / 40.0, 0.0, 1.0);
        col += computeStarCorona(rayDir, uSun2Direction, uSun2Color, uSun2Intensity,
                                 uSun2Size, uSun2DistanceAU, haloAtten2) * ringAtten;
        col += computeStarBlowout(rayDir, uSun2Direction, uSun2Color, uSun2Intensity,
                                  uSun2Size, uSun2DistanceAU) * ringAtten;
        col += computeStarSpikes(rayDir, uSun2Direction, uSun2Color, uSun2Intensity,
                                 uSun2Size, uSun2DistanceAU, haloAtten2) * ringAtten;
    }

    col *= uReveal;
    col *= uCameraExposure;
    if (uPostprocess == 1) {
        fragColor = vec4(col, 1.0);
    } else {
        col = tonemap(col);
        col = pow(col, vec3(1.0 / 2.2));
        fragColor = vec4(col, 1.0);
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Ozone / absorption band density (asymmetric Gaussian)
// ────────────────────────────────────────────────────────────────────────────

float getOzoneDensity(float h, float center, float width) {
    float dd = h - center;
    float sigma = (dd < 0.0) ? (width * 0.3) : (width * 0.5);
    sigma = max(sigma, 0.001);
    return exp(-0.5 * (dd * dd) / (sigma * sigma));
}

// ────────────────────────────────────────────────────────────────────────────
// Cornette-Shanks phase function
// ────────────────────────────────────────────────────────────────────────────

float CSPhase(float g, float mu) {
    return 3.0 / (8.0 * 3.14159) * ((1.0 - g * g) * (1.0 + mu * mu)) /
           ((2.0 + g * g) * pow(1.0 + g * g - 2.0 * g * mu, 1.5));
}

// ────────────────────────────────────────────────────────────────────────────
// Main
// ────────────────────────────────────────────────────────────────────────────

void main() {
    // ── Oblateness scaling ──
    vec3 S = vec3(1.0, 1.0 / (1.0 - uOblateness), 1.0);

    vec3 camPos_s = uCameraPos * S;
    float cam_len_s = length(camPos_s);
    float uCameraAlt_s = cam_len_s - uPlanetRadius;
    vec3 uCameraDir_s = camPos_s / cam_len_s;

    // ── Ray direction from UV ──
    vec2 ndc = vUv * 2.0 - 1.0;
    vec4 clipPos = vec4(ndc, -1.0, 1.0);
    vec4 viewPos = uInverseProjection * clipPos;
    vec3 viewRay = normalize(viewPos.xyz / viewPos.w);
    vec3 rayDir = normalize((uInverseView * vec4(viewRay, 0.0)).xyz);

    vec3 rayDir_scaled = rayDir * S;
    float rd_s_len = length(rayDir_scaled);
    vec3 dir_s = rayDir_scaled / rd_s_len;

    vec3 sunDir_scaled = uSunDirection * S;
    float sun_s_len = length(sunDir_scaled);
    vec3 sunDir_s = sunDir_scaled / sun_s_len;

    // Secondary sun in scaled (oblateness-corrected) ray-march space.
    // Always computed so downstream code can branch on uHasSecondarySun
    // without re-doing the basis transform.
    vec3 sun2Dir_scaled = uSun2Direction * S;
    float sun2_s_len = max(length(sun2Dir_scaled), 1e-6);
    vec3 sun2Dir_s = sun2Dir_scaled / sun2_s_len;

    float maxScaleHeight = max(uRayleighScaleHeight, uMieScaleHeight);
    float effectiveThickness = min(uAtmosphereThickness, maxScaleHeight * 25.0);

    vec2 pInter = rsi_stable(uCameraDir_s, uCameraAlt_s, dir_s, effectiveThickness);
    vec2 pPlanet = rsi_stable(uCameraDir_s, uCameraAlt_s, dir_s, 0.0);

    // ── Ring intersection (equatorial Y=0 plane, scaled space) ──
    // Computed once here; both airless and atmosphere paths use the results.
    // tRing_s is directly comparable to pPlanet.x (both in scaled parametric space).
    // XZ distance is identical in physical and scaled space (S.x = S.z = 1).
    float tRing_s = -1.0;
    float ringDens = 0.0;
    vec3 ringCol = vec3(0.0);
    vec3 ringHitPhys = vec3(0.0);
    if (uHasRings && abs(dir_s.y) > 1e-6) {
        float t = -camPos_s.y / dir_s.y;
        if (t > 0.0) {
            vec3 hit_s = camPos_s + dir_s * t;
            float r = length(hit_s.xz) / uPlanetRadius;
            if (r >= uRingInner && r <= uRingOuter) {
                tRing_s = t;
                ringDens = getRingDensity(r);
                ringCol = getRingColor(r);
                ringHitPhys = vec3(hit_s.x, 0.0, hit_s.z); // physical km
            }
        }
    }
    // Ring is "in front" if it's closer than the planet surface (or no surface hit)
    bool ringInFront = ringDens > 0.001 &&
        (pPlanet.x <= 0.0 || tRing_s < pPlanet.x);

    // ── Airless early-out ──
    if (uAtmosphereThickness <= 0.0) {
        if (pPlanet.x > 0.0) {
            vec3 hitPoint_s = uCameraDir_s * cam_len_s + dir_s * pPlanet.x;
            vec3 surfNorm = normalize(normalize(hitPoint_s) * S);

            vec3 baseSurfaceColor = uPlanetColor;
            if (uUseTexture) {
                vec3 physical_p = normalize(hitPoint_s / S);
                float ny = clamp(physical_p.y, -1.0, 1.0);
                float u = 1.0 - (atan(physical_p.z, physical_p.x) + 3.14159265) / (2.0 * 3.14159265);
                float v = (asin(ny) + 1.57079632) / 3.14159265;
                vec4 texSample = texture(uPlanetTexture, vec2(u, v));
                baseSurfaceColor = pow(texSample.rgb, vec3(2.2));
                if (uBumpStrength > 0.0) {
                    // Tangent-space bump: build east/north basis from surface normal,
                    // perturb in that basis, then transform back to world space.
                    float eps = 0.004;
                    float hC = texSample.a;
                    float hU = texture(uPlanetTexture, vec2(u + eps, v)).a;
                    float hV = texture(uPlanetTexture, vec2(u, v + eps)).a;
                    vec3 upRef = abs(surfNorm.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                    vec3 T = normalize(cross(upRef, surfNorm));  // east
                    vec3 B = cross(surfNorm, T);                  // north
                    // Surface normal tilts OPPOSITE to height gradient: higher terrain
                // to the east means this point's face points west. Sign flipped vs
                // the gradient so craters dip instead of bulging out.
                vec3 bump = T * (hC - hU) + B * (hC - hV);
                    surfNorm = normalize(surfNorm + uBumpStrength * bump * 4.0);
                }
            }

            // Primary star surface lighting. Lambertian by default; mixes
            // in Lommel-Seeliger when uHapkeStrength > 0 to give airless
            // regolith bodies (Moon analogues) a flat, evenly-lit disc.
            // Opposition surge layered on top so the same bodies brighten
            // sharply near full phase (Moon-at-opposition look).
            float nDotL = max(dot(surfNorm, uSunDirection), 0.0);
            float nDotV = max(dot(surfNorm, -rayDir), 0.0);
            float surge = oppositionSurge(uSunDirection, -rayDir);
            float diffuse = hapkeDiffuse(nDotL, nDotV) * surge;
            vec3 surfPosPhys = normalize(hitPoint_s / S) * uPlanetRadius;
            float ringShadow = getRingShadow(surfPosPhys, uSunDirection);
            vec3 surfaceSunLight = uSunColor * (uSunIntensity * 0.05);
            vec3 col = baseSurfaceColor * surfaceSunLight * diffuse * ringShadow;
            // Surface specular (water glint, ice highlights, rock gloss).
            // No atmosphere on this branch, so sunAtten = 1.
            col += surfaceSpecular(
                baseSurfaceColor, surfNorm, -rayDir, uSunDirection, nDotL,
                surfaceSunLight, vec3(1.0));

            // Secondary star surface lighting (circumbinary planets).
            // Each star contributes its own Lambertian (or Hapke) + specular
            // term, shadowed independently by the ring system. Combined the
            // surface reads as lit by both stars from their SMA-derived
            // angular separation around the barycenter direction.
            if (uHasSecondarySun == 1) {
                float nDotL2 = max(dot(surfNorm, uSun2Direction), 0.0);
                float surge2 = oppositionSurge(uSun2Direction, -rayDir);
                float diffuse2 = hapkeDiffuse(nDotL2, nDotV) * surge2;
                float ringShadow2 = getRingShadow(surfPosPhys, uSun2Direction);
                vec3 surfaceSunLight2 = uSun2Color * (uSun2Intensity * 0.05);
                col += baseSurfaceColor * surfaceSunLight2 * diffuse2 * ringShadow2;
                col += surfaceSpecular(
                    baseSurfaceColor, surfNorm, -rayDir, uSun2Direction, nDotL2,
                    surfaceSunLight2, vec3(1.0));
            }
            // Flat ambient (toggle): full-globe illumination via screen-
            // space inverse-tonemap injection. Not multiplied by
            // ringShadow — flat ambient comes from "everywhere" and isn't
            // blocked by the rings, so shadowed regions still receive the
            // lift.
            col += hdrAmbient(baseSurfaceColor);
            // Emissive lava: detects warm thermal-gradient pixels (red→orange→yellow)
            // via the R>B margin so dark-red crust, orange flows, and yellow hot
            // channels all glow as unlit emission through the terminator and night side.
            // Gated on uLavaEmission so warm-toned terrain (tan, rust, iron-oxide)
            // on non-lava worlds can't falsely glow.
            // Lava's red→orange→yellow gradient sits entirely below B=0.10
            // (deepest channel: lavaCool B=0.005, lavaHot B=0.08). Warm
            // terrain — silicate tan, iron oxide rust, volcanic ash — always
            // carries B > 0.15. Keying off low B directly isolates thermal
            // pixels without catching desert surfaces. The old R-B margin
            // test caught warm terrain because R-B of tan/rust sits around
            // 0.20-0.30, overlapping the threshold.
            float lavaMask = smoothstep(0.40, 0.75, baseSurfaceColor.r) *
                             smoothstep(0.15, 0.05, baseSurfaceColor.b);
            col += baseSurfaceColor * lavaMask * 9.0 * uLavaEmission;
            // Blackbody thermal emission: hot rock glows independently of sunlight.
            // Modulated by the base surface albedo so dark basalt radiates less
            // than light silicate, matching real emissivity variation.
            col += uThermalEmission * baseSurfaceColor * 4.0;
            // Ring compositing: blend lit ring over surface when ring is in front.
            // Both stars contribute Lambertian-style lighting through the ring;
            // each casts its own planet-shadow on the disc.
            if (ringInFront) {
                float ringPhoto = ringPhotometric(uSunDirection, rayDir, ringDens);
                float pShad = ringInPlanetShadow(ringHitPhys, uSunDirection) ? 0.0 : 1.0;
                vec3 ringLit = ringCol * surfaceSunLight * ringPhoto * pShad;
                if (uHasSecondarySun == 1) {
                    vec3 surfaceSunLight2 = uSun2Color * (uSun2Intensity * 0.05);
                    float ringPhoto2 = ringPhotometric(uSun2Direction, rayDir, ringDens);
                    float pShad2 = ringInPlanetShadow(ringHitPhys, uSun2Direction) ? 0.0 : 1.0;
                    ringLit += ringCol * surfaceSunLight2 * ringPhoto2 * pShad2;
                }
                // Ambient on rings: bypasses pShad so the part of the
                // ring in the planet's umbra still lifts under ambient.
                ringLit += hdrAmbient(ringCol);
                col = mix(col, ringLit, ringOpacity(ringDens));
            }
            writeFinal(col, rayDir, ringInFront ? ringOpacity(ringDens) : 0.0);
        } else {
            // Star disk + saturation blowout in empty space around the
            // airless planet. Helper renders the disc plus its outer-
            // edge blowout halo; secondary added when present so a
            // circumbinary planet shows both suns side by side.
            vec3 col = computeStarDisc(rayDir, uSunDirection, uSunColor,
                uSunIntensity, uSunSize, uSunDistanceAU);
            if (uHasSecondarySun == 1) {
                col += computeStarDisc(rayDir, uSun2Direction, uSun2Color,
                    uSun2Intensity, uSun2Size, uSun2DistanceAU);
            }
            // Ring in empty space (no planet hit) — both stars light it.
            if (ringDens > 0.001) {
                vec3 ringSunLight = uSunColor * (uSunIntensity * 0.05);
                float ringPhoto = ringPhotometric(uSunDirection, rayDir, ringDens);
                float pShad = ringInPlanetShadow(ringHitPhys, uSunDirection) ? 0.0 : 1.0;
                vec3 ringLit = ringCol * ringSunLight * ringPhoto * pShad;
                if (uHasSecondarySun == 1) {
                    vec3 ringSunLight2 = uSun2Color * (uSun2Intensity * 0.05);
                    float ringPhoto2 = ringPhotometric(uSun2Direction, rayDir, ringDens);
                    float pShad2 = ringInPlanetShadow(ringHitPhys, uSun2Direction) ? 0.0 : 1.0;
                    ringLit += ringCol * ringSunLight2 * ringPhoto2 * pShad2;
                }
                ringLit += hdrAmbient(ringCol);
                col = mix(col, ringLit, ringOpacity(ringDens));
            }
            writeFinal(col, rayDir, ringInFront ? ringOpacity(ringDens) : 0.0);
        }
        return;
    }

    if (uCameraAlt_s < -0.1) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    // ── Lighting setup ──
    vec3 atmSunLight = uSunColor * uSunIntensity;
    vec3 surfaceSunLight = uSunColor * (uSunIntensity * 0.05);
    // No dark-side ambient — night side is pure black
    float exposure = uCameraExposure;

    // Guard fog scale height against zero to prevent NaN from exp(-h / 0)
    float safeFogSH = max(uFogScaleHeight, 0.001);

    vec3 fogScatCoeff = uFogColor * uFogDensity;
    vec3 fogAbsCoeff = max(vec3(1.0) - uFogColor, vec3(0.01)) * uFogDensity * 2.0;
    vec3 fogExtCoeff = fogScatCoeff + fogAbsCoeff;

    vec3 mieExtinction = uMieScattering + uMieAbsorption;
    float jitter = 0.5;

    // ── Sun disk(s) ──
    // Both stars contribute their own disc + limb-darkened tint + outer-
    // edge saturation blowout (see [computeStarDisc]). The disc/blowout
    // is geometry-bound to the sky behind the atmosphere; the broader
    // corona and diffraction spikes are added later in writeFinal so
    // they overlay the entire frame.
    vec3 skyColor = computeStarDisc(rayDir, uSunDirection, uSunColor,
        uSunIntensity, uSunSize, uSunDistanceAU);
    if (uHasSecondarySun == 1) {
        skyColor += computeStarDisc(rayDir, uSun2Direction, uSun2Color,
            uSun2Intensity, uSun2Size, uSun2DistanceAU);
    }

    // ── No atmosphere intersection → just sky ──
    if (pInter.x > pInter.y || pInter.y < 0.0) {
        vec3 bgFinal = skyColor;
        // Ring in empty space (outside atmosphere) — both stars light it.
        if (ringDens > 0.001) {
            vec3 ringSunLight = uSunColor * (uSunIntensity * 0.05);
            float ringPhoto = ringPhotometric(uSunDirection, rayDir, ringDens);
            float pShad = ringInPlanetShadow(ringHitPhys, uSunDirection) ? 0.0 : 1.0;
            vec3 ringLit = ringCol * ringSunLight * ringPhoto * pShad;
            if (uHasSecondarySun == 1) {
                vec3 ringSunLight2 = uSun2Color * (uSun2Intensity * 0.05);
                float ringPhoto2 = ringPhotometric(uSun2Direction, rayDir, ringDens);
                float pShad2 = ringInPlanetShadow(ringHitPhys, uSun2Direction) ? 0.0 : 1.0;
                ringLit += ringCol * ringSunLight2 * ringPhoto2 * pShad2;
            }
            // Ambient bypasses pShad so the umbra-shadowed ring still lifts.
            ringLit += hdrAmbient(ringCol);
            bgFinal = mix(bgFinal, ringLit, ringOpacity(ringDens));
        }
        writeFinal(bgFinal, rayDir, ringInFront ? ringOpacity(ringDens) : 0.0);
        return;
    }

    float tMin = max(0.0, pInter.x);
    float tMax = pInter.y;
    bool hitGround = (pPlanet.x > 0.0 && pPlanet.x < pInter.y);
    if (hitGround) tMax = pPlanet.x;

    // ── Cloud shell intersection ──
    float tC = -1.0;
    float cAlpha = 0.0;
    vec3 cloudColorFinal = vec3(0.0);
    float cloudDiffuse = 0.0;
    vec3 cloudSunAtten = vec3(1.0);

    if (uCloudCoverage > 0.0 && uHasCloudTexture) {
        vec2 pCloudShell = rsi_stable(uCameraDir_s, uCameraAlt_s, dir_s, uCloudAltitude);
        tC = (pCloudShell.x > 0.0) ? pCloudShell.x : pCloudShell.y;

        if (tC > 0.0 && tC < tMax) {
            vec3 pC_s = normalize(uCameraDir_s * cam_len_s + dir_s * tC);
            vec3 pC_phys = normalize(pC_s / S);
            float n = sampleCloudNoise(pC_phys);

            // For tidally-locked planets, transform the engine's globally
            // generated cloud cover into a substellar-concentrated vortex
            // with a sparse halo fading toward the terminator and clear
            // skies past it. Real GCM circulation: rising air at the
            // substellar point produces a thick convective deck, that
            // outflow drops most of its moisture before reaching the
            // terminator, and the antistellar side has too little water
            // vapour aloft to form anything more than the occasional thin
            // stratus.
            //
            // Profile (mu = cos(angleFromSubstellar)):
            //   • `dayPresence` — smoothstep(-0.15, 0.3, mu) gates the
            //     day side cleanly: full strength once we're sun-side
            //     of the terminator, fading to zero just past it. Keeps
            //     the night side reliably clear without abrupt cutoff.
            //   • `profile`     — `(mu*0.5 + 0.5)^1.2` gentle bell that
            //     biases the dayside coverage slightly toward substellar
            //     while keeping mid-day still well-covered.
            //   • `halo`        — `dayPresence × profile`, full coverage
            //     bell on the day side, hard zero on the night side.
            //   • `vortex`      — `mu^5 × 0.7`, sharp peak only at the
            //     substellar hotspot. Adds the concentrated convective
            //     cap on top of the halo without leaking out to the
            //     dayside halo.
            //
            // Net factor profile (input coverage 0.5):
            //   substellar (vortex): ~0.85 (saturating, dense)
            //   mid-day (mu = 0.7):  ~0.47 (broad cloud cover)
            //   mu = 0.5:            ~0.36 (moderate)
            //   mu = 0.3:            ~0.30 (visible)
            //   mu = 0.1:            ~0.09 (sparse)
            //   terminator:          ~0.06 (very sparse)
            //   antistellar:         0 (clear)
            // Reads as a coherent cloudy planet with a substellar
            // cyclone, rather than the previous "one big cloud on the
            // face" appearance.
            float effectiveCoverage = uCloudCoverage;
            if (uTidallyLocked == 1) {
                float mu = dot(pC_phys, uSunDirection);
                float dayPresence = smoothstep(-0.15, 0.3, mu);
                float profile = pow(max(0.0, mu * 0.5 + 0.5), 1.2);
                float halo = dayPresence * profile;
                float vortex = pow(max(0.0, mu), 5.0) * 0.7;
                float factor = halo + vortex;
                effectiveCoverage = clamp(uCloudCoverage * factor, 0.0, 1.0);
            }

            float threshold = 1.0 - effectiveCoverage;
            float cloudDepth = max(0.0, n - threshold);
            float softness = mix(0.4, 0.01, pow(effectiveCoverage, 2.0));
            cAlpha = smoothstep(0.0, softness, cloudDepth) * uCloudDensity;
            cAlpha = clamp(cAlpha, 0.0, 1.0);

            if (cAlpha > 0.001) {
                vec3 surfaceNormal = normalize(pC_s * S);

                // Cloud normal from baked texture gradient (3 texture lookups)
                float eps = 0.01;
                float nx = sampleCloudNoise(normalize(pC_phys + vec3(eps, 0.0, 0.0)));
                float ny_c = sampleCloudNoise(normalize(pC_phys + vec3(0.0, eps, 0.0)));
                float nz = sampleCloudNoise(normalize(pC_phys + vec3(0.0, 0.0, eps)));
                vec3 grad = vec3(nx - n, ny_c - n, nz - n) / eps;
                vec3 cloudNormal = normalize(surfaceNormal - grad * uCloudBumpiness);
                float opacityFactor = clamp(uCloudDensity, 0.0, 1.0);

                // Per-pixel cloud tint. On Venus-class opaque-deck worlds
                // this is the gas-giant-bake swirl overlay (sampled in
                // linear RGB); on every other planet it's the flat
                // uCloudColor from the atmosphere optics deriver.
                vec3 cloudTint = uHasCloudOverlay == 1
                    ? sampleCloudOverlay(pC_phys)
                    : uCloudColor;

                // Per-sun cloud lighting: each star illuminates the cloud
                // top with its own atmosphere-transmittance + Lambertian
                // diffuse. Sum gives the combined cloud colour. cloudDiffuse
                // tracks the combined Lambertian factor for downstream
                // surface cloud-shadow attenuation (kept outside this block
                // so the surface path can read the combined coverage).
                cloudDiffuse = 0.0;
                cloudSunAtten = vec3(1.0);

                // ── Primary sun ──
                {
                    float sphereNDotL = dot(surfaceNormal, uSunDirection);
                    vec3 atten = vec3(1.0);
                    vec2 atmIntersect = rsi_stable(pC_s, uCloudAltitude, sunDir_s, effectiveThickness);
                    if (atmIntersect.y > 0.0) {
                        float stepSize_s = atmIntersect.y / float(uJSteps);
                        float physStepSize = stepSize_s / sun_s_len;
                        float tr = 0.0, tm = 0.0, tf = 0.0, to = 0.0;
                        for (int k = 0; k < uJSteps; k++) {
                            float st = (float(k) + jitter) * stepSize_s;
                            float sh = max(getAltitude(st, uCloudAltitude, pC_s, sunDir_s), 0.0);
                            tr += exp(-sh / uRayleighScaleHeight) * physStepSize;
                            tm += exp(-sh / uMieScaleHeight) * physStepSize;
                            tf += exp(-sh / safeFogSH) * physStepSize;
                            to += getOzoneDensity(sh, uOzoneCenter, uOzoneWidth) * physStepSize;
                        }
                        atten = exp(-(uRayleighScattering * tr + mieExtinction * tm + fogExtCoeff * tf + uOzoneAbsorption * to));
                    }
                    cloudSunAtten = atten;  // primary's atten — used by surface cloud-shadow path below

                    float cNDotL = max(dot(cloudNormal, uSunDirection), 0.0);
                    float bumpedDiffuse = smoothstep(0.0, 0.6, cNDotL);
                    float scatterDiffuse = smoothstep(-0.2, 0.2, sphereNDotL);
                    float diffuse = mix(scatterDiffuse, bumpedDiffuse, opacityFactor);
                    diffuse *= smoothstep(-0.05, 0.1, sphereNDotL);
                    cloudDiffuse += diffuse;
                    cloudColorFinal += cloudTint * diffuse * surfaceSunLight * atten;
                }

                // ── Secondary sun (circumbinary) ──
                if (uHasSecondarySun == 1) {
                    vec3 surfaceSunLight2 = uSun2Color * (uSun2Intensity * 0.05);
                    float sphereNDotL2 = dot(surfaceNormal, uSun2Direction);
                    vec3 atten2 = vec3(1.0);
                    vec2 atmIntersect2 = rsi_stable(pC_s, uCloudAltitude, sun2Dir_s, effectiveThickness);
                    if (atmIntersect2.y > 0.0) {
                        float stepSize_s = atmIntersect2.y / float(uJSteps);
                        float physStepSize = stepSize_s / sun2_s_len;
                        float tr = 0.0, tm = 0.0, tf = 0.0, to = 0.0;
                        for (int k = 0; k < uJSteps; k++) {
                            float st = (float(k) + jitter) * stepSize_s;
                            float sh = max(getAltitude(st, uCloudAltitude, pC_s, sun2Dir_s), 0.0);
                            tr += exp(-sh / uRayleighScaleHeight) * physStepSize;
                            tm += exp(-sh / uMieScaleHeight) * physStepSize;
                            tf += exp(-sh / safeFogSH) * physStepSize;
                            to += getOzoneDensity(sh, uOzoneCenter, uOzoneWidth) * physStepSize;
                        }
                        atten2 = exp(-(uRayleighScattering * tr + mieExtinction * tm + fogExtCoeff * tf + uOzoneAbsorption * to));
                    }
                    float cNDotL2 = max(dot(cloudNormal, uSun2Direction), 0.0);
                    float bumpedDiffuse2 = smoothstep(0.0, 0.6, cNDotL2);
                    float scatterDiffuse2 = smoothstep(-0.2, 0.2, sphereNDotL2);
                    float diffuse2 = mix(scatterDiffuse2, bumpedDiffuse2, opacityFactor);
                    diffuse2 *= smoothstep(-0.05, 0.1, sphereNDotL2);
                    cloudDiffuse += diffuse2;
                    cloudColorFinal += cloudTint * diffuse2 * surfaceSunLight2 * atten2;
                }

                // Flat ambient (toggle): full-globe screen-space inverse-
                // tonemap injection so cloud tops lift consistently with
                // the surface ambient.
                cloudColorFinal += hdrAmbient(cloudTint);
            }
        } else {
            cAlpha = 0.0;
        }
    }

    // ── Ray march through atmosphere ──
    float rayLength_s = tMax - tMin;
    float stepSize_s = rayLength_s / float(uISteps);
    float physStepSize = stepSize_s / rd_s_len;

    float opticalDepthR = 0.0;
    float opticalDepthM = 0.0;
    float opticalDepthF = 0.0;
    float opticalDepthO = 0.0;
    // Per-sun in-scattered light, summed at compositing. Optical depths along
    // the view ray are sun-independent and accumulate once.
    vec3 totalScattering = vec3(0.0);
    vec3 totalScattering2 = vec3(0.0);

    // Phase functions are direction-dependent (mu = dot(rayDir, sunDir)) so
    // each sun gets its own set. CSPhase is cheap but mu changes per sun.
    float mu = dot(rayDir, uSunDirection);
    float phaseR = 3.0 / (16.0 * 3.14159) * (1.0 + mu * mu);
    float phaseM = mix(CSPhase(uMiePhaseG, mu), CSPhase(uMiePhaseG2, mu), uMiePhaseBlend);
    float gFog = 0.5;
    float phaseF = CSPhase(gFog, mu);

    float mu2 = dot(rayDir, uSun2Direction);
    float phaseR2 = 3.0 / (16.0 * 3.14159) * (1.0 + mu2 * mu2);
    float phaseM2 = mix(CSPhase(uMiePhaseG, mu2), CSPhase(uMiePhaseG2, mu2), uMiePhaseBlend);
    float phaseF2 = CSPhase(gFog, mu2);

    // Pre-sample fog and Mie dirtiness once at ray midpoint (not per-step)
    float fogMaskOnce = 1.0;
    float dirtFactorOnce = 1.0;
    {
        float tMid = (tMin + tMax) * 0.5;
        vec3 pMidDir_s = normalize(uCameraDir_s * cam_len_s + dir_s * tMid);
        vec3 pMid_phys = normalize(pMidDir_s / S);
        if (uFogPatchiness > 0.0) {
            fogMaskOnce = getFogMask(pMid_phys, uTime, uFogPatchiness);
        }
        if (uMieDirtiness > 0.0) {
            float hMid = max(getAltitude(tMid, uCameraAlt_s, uCameraDir_s, dir_s), 0.0);
            float dirtNoise = fbm(pMid_phys * 15.0 + vec3(0.0, hMid * 0.1, uTime * 0.02));
            dirtFactorOnce = 1.0 + uMieDirtiness * (dirtNoise - 0.5);
        }
    }

    // Cloud front/back splitting — separate per-sun trackers because in-
    // scattered light from each star compositesthrough the cloud
    // independently. tau front values are sun-independent.
    vec3 scatter_front = vec3(0.0);
    vec3 scatter_front2 = vec3(0.0);
    float tauR_front = 0.0;
    float tauM_front = 0.0;
    float tauF_front = 0.0;
    float tauO_front = 0.0;
    bool passedCloud = false;

    for (int i = 0; i < uISteps; i++) {
        float t0 = tMin + float(i) * stepSize_s;
        float tSample = t0 + jitter * stepSize_s;
        float hSample_s = max(getAltitude(tSample, uCameraAlt_s, uCameraDir_s, dir_s), 0.0);

        vec3 pSampleDir_s = normalize(uCameraDir_s * cam_len_s + dir_s * tSample);

        float rhoR = exp(-hSample_s / uRayleighScaleHeight) * physStepSize;
        float rhoM = exp(-hSample_s / uMieScaleHeight) * physStepSize * dirtFactorOnce;

        float rhoF = exp(-hSample_s / safeFogSH) * physStepSize * fogMaskOnce;
        float rhoO = getOzoneDensity(hSample_s, uOzoneCenter, uOzoneWidth) * physStepSize;

        vec3 stepExtinctionDepth = uRayleighScattering * rhoR +
                                   mieExtinction * rhoM +
                                   fogExtCoeff * rhoF +
                                   uOzoneAbsorption * rhoO;
        vec3 stepExtinctionDepth_safe = max(stepExtinctionDepth, vec3(1e-6));

        vec3 viewTau = uRayleighScattering * opticalDepthR +
                       mieExtinction * opticalDepthM +
                       fogExtCoeff * opticalDepthF +
                       uOzoneAbsorption * opticalDepthO;
        vec3 viewTransmittance = exp(-viewTau);
        vec3 stepViewIntegral = (1.0 - exp(-stepExtinctionDepth)) / stepExtinctionDepth_safe;

        // ── Primary sun in-scattering ──
        {
            float sunShadow = 1.0;
            float R_sample = uPlanetRadius;
            float R0_sample = R_sample + hSample_s;
            float cosTheta_sample = dot(pSampleDir_s, sunDir_s);
            float b_sample = R0_sample * cosTheta_sample;
            float C_sample = 2.0 * R_sample * hSample_s + hSample_s * hSample_s;
            float D_sample = b_sample * b_sample - C_sample;
            if (b_sample < 0.0) {
                float minRadius = sqrt(max(R_sample * R_sample - D_sample, 0.0));
                float minAlt_s = minRadius - R_sample;
                float penumbra = max((-b_sample) * (0.00465 * uSunSize / uSunDistanceAU), 0.01);
                sunShadow = smoothstep(0.0, penumbra, minAlt_s / sun_s_len);
            }
            vec3 sunTransmittance = vec3(0.0);
            if (sunShadow > 0.0) {
                vec2 atmIntersectSun = rsi_stable(pSampleDir_s, hSample_s, sunDir_s, effectiveThickness);
                float sunStepSize_s = atmIntersectSun.y / float(uJSteps);
                float physSunStepSize = sunStepSize_s / sun_s_len;
                float sunOptDepthR = 0.0;
                float sunOptDepthM = 0.0;
                float sunOptDepthF = 0.0;
                float sunOptDepthO = 0.0;
                for (int j = 0; j < uJSteps; j++) {
                    float st = (float(j) + jitter) * sunStepSize_s;
                    float sh = max(getAltitude(st, hSample_s, pSampleDir_s, sunDir_s), 0.0);
                    sunOptDepthR += exp(-sh / uRayleighScaleHeight) * physSunStepSize;
                    sunOptDepthM += exp(-sh / uMieScaleHeight) * physSunStepSize;
                    sunOptDepthF += exp(-sh / safeFogSH) * physSunStepSize * fogMaskOnce;
                    sunOptDepthO += getOzoneDensity(sh, uOzoneCenter, uOzoneWidth) * physSunStepSize;
                }
                vec3 sunTau = uRayleighScattering * sunOptDepthR +
                              mieExtinction * sunOptDepthM +
                              fogExtCoeff * sunOptDepthF +
                              uOzoneAbsorption * sunOptDepthO;
                sunTransmittance = exp(-sunTau) * sunShadow;
            }
            vec3 S_dx = rhoR * uRayleighScattering * sunTransmittance * phaseR +
                        rhoM * uMieScattering * sunTransmittance * phaseM +
                        rhoF * fogScatCoeff * sunTransmittance * phaseF;
            totalScattering += viewTransmittance * S_dx * stepViewIntegral;
        }

        // ── Secondary sun in-scattering (circumbinary) ──
        // Each star scatters independently along the view ray; the optical
        // depth of the atmosphere (extinction) is shared. Final compositing
        // multiplies each sun's accumulator by that sun's colour/intensity.
        if (uHasSecondarySun == 1) {
            float sunShadow2 = 1.0;
            float R_sample = uPlanetRadius;
            float R0_sample = R_sample + hSample_s;
            float cosTheta_sample = dot(pSampleDir_s, sun2Dir_s);
            float b_sample = R0_sample * cosTheta_sample;
            float C_sample = 2.0 * R_sample * hSample_s + hSample_s * hSample_s;
            float D_sample = b_sample * b_sample - C_sample;
            if (b_sample < 0.0) {
                float minRadius = sqrt(max(R_sample * R_sample - D_sample, 0.0));
                float minAlt_s = minRadius - R_sample;
                float penumbra = max((-b_sample) * (0.00465 * uSun2Size / uSun2DistanceAU), 0.01);
                sunShadow2 = smoothstep(0.0, penumbra, minAlt_s / sun2_s_len);
            }
            vec3 sunTransmittance2 = vec3(0.0);
            if (sunShadow2 > 0.0) {
                vec2 atmIntersectSun2 = rsi_stable(pSampleDir_s, hSample_s, sun2Dir_s, effectiveThickness);
                float sunStepSize2_s = atmIntersectSun2.y / float(uJSteps);
                float physSunStepSize2 = sunStepSize2_s / sun2_s_len;
                float sunOptDepthR = 0.0;
                float sunOptDepthM = 0.0;
                float sunOptDepthF = 0.0;
                float sunOptDepthO = 0.0;
                for (int j = 0; j < uJSteps; j++) {
                    float st = (float(j) + jitter) * sunStepSize2_s;
                    float sh = max(getAltitude(st, hSample_s, pSampleDir_s, sun2Dir_s), 0.0);
                    sunOptDepthR += exp(-sh / uRayleighScaleHeight) * physSunStepSize2;
                    sunOptDepthM += exp(-sh / uMieScaleHeight) * physSunStepSize2;
                    sunOptDepthF += exp(-sh / safeFogSH) * physSunStepSize2 * fogMaskOnce;
                    sunOptDepthO += getOzoneDensity(sh, uOzoneCenter, uOzoneWidth) * physSunStepSize2;
                }
                vec3 sunTau2 = uRayleighScattering * sunOptDepthR +
                               mieExtinction * sunOptDepthM +
                               fogExtCoeff * sunOptDepthF +
                               uOzoneAbsorption * sunOptDepthO;
                sunTransmittance2 = exp(-sunTau2) * sunShadow2;
            }
            vec3 S_dx2 = rhoR * uRayleighScattering * sunTransmittance2 * phaseR2 +
                         rhoM * uMieScattering * sunTransmittance2 * phaseM2 +
                         rhoF * fogScatCoeff * sunTransmittance2 * phaseF2;
            totalScattering2 += viewTransmittance * S_dx2 * stepViewIntegral;
        }

        // ── Cloud front/back tracking ──
        if (cAlpha > 0.001 && !passedCloud && tSample >= tC) {
            passedCloud = true;
            float fraction = clamp((tC - t0) / stepSize_s, 0.0, 1.0);
            scatter_front = totalScattering;
            scatter_front2 = totalScattering2;
            tauR_front = opticalDepthR + rhoR * fraction;
            tauM_front = opticalDepthM + rhoM * fraction;
            tauF_front = opticalDepthF + rhoF * fraction;
            tauO_front = opticalDepthO + rhoO * fraction;
        }

        opticalDepthR += rhoR;
        opticalDepthM += rhoM;
        opticalDepthF += rhoF;
        opticalDepthO += rhoO;

        // Early termination — only when the view ray hits the planet.
        // Sky/limb rays need the full integration so the sunset glow at the
        // terminator stays bright; clipping limb pixels was what caused the
        // early-Phase-7 terminator blackout. Ground-hit rays converge to
        // surface lighting that dominates the final pixel, so cutting the
        // residual atmospheric contribution at 3% transmittance saves
        // mid-march inner-loop work without visible loss.
        if (hitGround && (viewTransmittance.r + viewTransmittance.g + viewTransmittance.b) < 0.03) break;
    }

    if (cAlpha > 0.001 && !passedCloud) {
        scatter_front = totalScattering;
        scatter_front2 = totalScattering2;
        tauR_front = opticalDepthR;
        tauM_front = opticalDepthM;
        tauF_front = opticalDepthF;
        tauO_front = opticalDepthO;
    }

    // ── Compositing ──
    // Each star's accumulated scattering carries its own colour/intensity.
    // The two atmospheres composite additively — same wavelength-scaled
    // Rayleigh / Mie / fog coefficients, but two distinct illuminations.
    vec3 atmSunLight2 = uSun2Color * uSun2Intensity;
    vec3 atmosphereColorFront = scatter_front * atmSunLight;
    vec3 atmosphereColorBack = (totalScattering * atmSunLight) - atmosphereColorFront;
    vec3 skyIrradianceTotal = totalScattering * atmSunLight;
    if (uHasSecondarySun == 1) {
        atmosphereColorFront += scatter_front2 * atmSunLight2;
        atmosphereColorBack += (totalScattering2 * atmSunLight2) - scatter_front2 * atmSunLight2;
        skyIrradianceTotal += totalScattering2 * atmSunLight2;
    }

    vec3 tauView = uRayleighScattering * opticalDepthR + mieExtinction * opticalDepthM + fogExtCoeff * opticalDepthF + uOzoneAbsorption * opticalDepthO;
    vec3 tauFront = uRayleighScattering * tauR_front + mieExtinction * tauM_front + fogExtCoeff * tauF_front + uOzoneAbsorption * tauO_front;
    vec3 tauBack = max(vec3(0.0), tauView - tauFront);

    vec3 color = skyColor;

    // Ring behind the atmosphere on limb rays: inject as background so the
    // full tauBack + tauFront extinction dims it through the atmospheric limb.
    // Only applies when the ring is farther than the atmosphere entry (tMin).
    // Rings closer than tMin are in front and composited after atmosphere.
    // Both stars light the ring (when present).
    bool ringBehindAtm = !hitGround && ringDens > 0.001 && tRing_s >= tMin;
    if (ringBehindAtm) {
        vec3 ringSunLight = uSunColor * (uSunIntensity * 0.05);
        float ringPhoto = ringPhotometric(uSunDirection, rayDir, ringDens);
        float pShad = ringInPlanetShadow(ringHitPhys, uSunDirection) ? 0.0 : 1.0;
        vec3 ringLit = ringCol * ringSunLight * ringPhoto * pShad;
        if (uHasSecondarySun == 1) {
            vec3 ringSunLight2 = uSun2Color * (uSun2Intensity * 0.05);
            float ringPhoto2 = ringPhotometric(uSun2Direction, rayDir, ringDens);
            float pShad2 = ringInPlanetShadow(ringHitPhys, uSun2Direction) ? 0.0 : 1.0;
            ringLit += ringCol * ringSunLight2 * ringPhoto2 * pShad2;
        }
        // Ambient bypasses pShad so the umbra-shadowed ring still lifts.
        ringLit += hdrAmbient(ringCol);
        color = mix(color, ringLit, ringOpacity(ringDens));
    }

    if (hitGround) {
        vec3 pPlanet_s = uCameraDir_s * cam_len_s + dir_s * tMax;
        vec3 surfaceNormal_s = normalize(pPlanet_s);
        vec3 physical_p = normalize(pPlanet_s / S);
        vec3 surfaceNormal = normalize(surfaceNormal_s * S);

        // Primary star surface lighting. Lambertian + atmosphere transmittance
        // is per-sun. nDotL is recomputed after bump-mapping (below).
        float nDotL = max(dot(surfaceNormal, uSunDirection), 0.0);

        // Sun attenuation through atmosphere to surface (primary)
        vec3 sunAtten = vec3(1.0);
        {
            vec2 planetBlock = rsi_stable(surfaceNormal_s, 0.0, sunDir_s, 0.0);
            if (planetBlock.x > 0.0) {
                sunAtten = vec3(0.0);
            } else {
                vec2 atmIntersect = rsi_stable(surfaceNormal_s, 0.0, sunDir_s, effectiveThickness);
                if (atmIntersect.y > 0.0) {
                    float stepSz = atmIntersect.y / float(uJSteps);
                    float physSz = stepSz / sun_s_len;
                    float tr = 0.0, tm = 0.0, tf = 0.0, to = 0.0;
                    for (int k = 0; k < uJSteps; k++) {
                        float st = (float(k) + jitter) * stepSz;
                        float sh = max(getAltitude(st, 0.0, surfaceNormal_s, sunDir_s), 0.0);
                        tr += exp(-sh / uRayleighScaleHeight) * physSz;
                        tm += exp(-sh / uMieScaleHeight) * physSz;
                        tf += exp(-sh / safeFogSH) * physSz * fogMaskOnce;
                        to += getOzoneDensity(sh, uOzoneCenter, uOzoneWidth) * physSz;
                    }
                    sunAtten = exp(-(uRayleighScattering * tr + mieExtinction * tm + fogExtCoeff * tf + uOzoneAbsorption * to));
                }
            }
        }

        // Secondary star surface lighting setup. Computed unconditionally
        // (cheap when uHasSecondarySun == 0 because nDotL2 will be 0 from
        // bogus uSun2Direction; we also gate the heavy J-step loop below).
        float nDotL2 = (uHasSecondarySun == 1)
            ? max(dot(surfaceNormal, uSun2Direction), 0.0) : 0.0;
        vec3 sunAtten2 = vec3(1.0);
        if (uHasSecondarySun == 1) {
            vec2 planetBlock2 = rsi_stable(surfaceNormal_s, 0.0, sun2Dir_s, 0.0);
            if (planetBlock2.x > 0.0) {
                sunAtten2 = vec3(0.0);
            } else {
                vec2 atmIntersect2 = rsi_stable(surfaceNormal_s, 0.0, sun2Dir_s, effectiveThickness);
                if (atmIntersect2.y > 0.0) {
                    float stepSz = atmIntersect2.y / float(uJSteps);
                    float physSz = stepSz / sun2_s_len;
                    float tr = 0.0, tm = 0.0, tf = 0.0, to = 0.0;
                    for (int k = 0; k < uJSteps; k++) {
                        float st = (float(k) + jitter) * stepSz;
                        float sh = max(getAltitude(st, 0.0, surfaceNormal_s, sun2Dir_s), 0.0);
                        tr += exp(-sh / uRayleighScaleHeight) * physSz;
                        tm += exp(-sh / uMieScaleHeight) * physSz;
                        tf += exp(-sh / safeFogSH) * physSz * fogMaskOnce;
                        to += getOzoneDensity(sh, uOzoneCenter, uOzoneWidth) * physSz;
                    }
                    sunAtten2 = exp(-(uRayleighScattering * tr + mieExtinction * tm + fogExtCoeff * tf + uOzoneAbsorption * to));
                }
            }
        }

        // Cloud shadows on surface. Each star casts its own cloud shadow;
        // the per-sun shadow factor multiplies into that sun's attenuation.
        // Tidally-locked bullseye uses uSunDirection for the substellar
        // axis (the primary star drives the convection regime — see
        // CloudType.WATER gating in AtmosphereOpticsDeriver).
        if (uCloudCoverage > 0.0 && uHasCloudTexture) {
            if (nDotL > 0.0) {
                vec2 shadowShell = rsi_stable(surfaceNormal_s, 0.0, sunDir_s, uCloudAltitude);
                if (shadowShell.y > 0.0) {
                    vec3 pShadow_s = normalize(surfaceNormal_s * uPlanetRadius + sunDir_s * shadowShell.y);
                    vec3 pShadow_phys = normalize(pShadow_s / S);
                    float sn = sampleCloudNoise(pShadow_phys);
                    float shadowEffectiveCoverage = uCloudCoverage;
                    if (uTidallyLocked == 1) {
                        float muS = dot(pShadow_phys, uSunDirection);
                        float dayPresenceS = smoothstep(-0.15, 0.3, muS);
                        float profileS = pow(max(0.0, muS * 0.5 + 0.5), 1.2);
                        float haloS = dayPresenceS * profileS;
                        float vortexS = pow(max(0.0, muS), 5.0) * 0.7;
                        float factorS = haloS + vortexS;
                        shadowEffectiveCoverage = clamp(uCloudCoverage * factorS, 0.0, 1.0);
                    }
                    float sThreshold = 1.0 - shadowEffectiveCoverage;
                    float sDepth = max(0.0, sn - sThreshold);
                    float sSoftness = mix(0.4, 0.01, pow(shadowEffectiveCoverage, 2.0));
                    float sAlpha = smoothstep(0.0, sSoftness, sDepth) * uCloudDensity;
                    sAlpha = clamp(sAlpha, 0.0, 1.0);
                    if (sAlpha > 0.01) {
                        sunAtten *= (1.0 - sAlpha * 0.85);
                    }
                }
            }
            if (uHasSecondarySun == 1 && nDotL2 > 0.0) {
                vec2 shadowShell2 = rsi_stable(surfaceNormal_s, 0.0, sun2Dir_s, uCloudAltitude);
                if (shadowShell2.y > 0.0) {
                    vec3 pShadow_s2 = normalize(surfaceNormal_s * uPlanetRadius + sun2Dir_s * shadowShell2.y);
                    vec3 pShadow_phys2 = normalize(pShadow_s2 / S);
                    float sn2 = sampleCloudNoise(pShadow_phys2);
                    float shadowEffectiveCoverage2 = uCloudCoverage;
                    if (uTidallyLocked == 1) {
                        float muS2 = dot(pShadow_phys2, uSunDirection);
                        float dayPresenceS2 = smoothstep(-0.15, 0.3, muS2);
                        float profileS2 = pow(max(0.0, muS2 * 0.5 + 0.5), 1.2);
                        float haloS2 = dayPresenceS2 * profileS2;
                        float vortexS2 = pow(max(0.0, muS2), 5.0) * 0.7;
                        float factorS2 = haloS2 + vortexS2;
                        shadowEffectiveCoverage2 = clamp(uCloudCoverage * factorS2, 0.0, 1.0);
                    }
                    float sThreshold2 = 1.0 - shadowEffectiveCoverage2;
                    float sDepth2 = max(0.0, sn2 - sThreshold2);
                    float sSoftness2 = mix(0.4, 0.01, pow(shadowEffectiveCoverage2, 2.0));
                    float sAlpha2 = smoothstep(0.0, sSoftness2, sDepth2) * uCloudDensity;
                    sAlpha2 = clamp(sAlpha2, 0.0, 1.0);
                    if (sAlpha2 > 0.01) {
                        sunAtten2 *= (1.0 - sAlpha2 * 0.85);
                    }
                }
            }
        }

        // Surface color
        vec3 baseSurfaceColor = uPlanetColor;
        if (uUseTexture) {
            float ny_coord = clamp(physical_p.y, -1.0, 1.0);
            float u = 1.0 - (atan(physical_p.z, physical_p.x) + 3.14159265) / (2.0 * 3.14159265);
            float v = (asin(ny_coord) + 1.57079632) / 3.14159265;
            vec4 texSample = texture(uPlanetTexture, vec2(u, v));
            baseSurfaceColor = pow(texSample.rgb, vec3(2.2));
            if (uBumpStrength > 0.0) {
                // Tangent-space bump: build east/north basis from surface normal,
                // perturb in that basis, then transform back to world space.
                float eps = 0.004;
                float hC = texSample.a;
                float hU = texture(uPlanetTexture, vec2(u + eps, v)).a;
                float hV = texture(uPlanetTexture, vec2(u, v + eps)).a;
                vec3 upRef = abs(surfaceNormal.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                vec3 T = normalize(cross(upRef, surfaceNormal));  // east
                vec3 B = cross(surfaceNormal, T);                  // north
                // Surface normal tilts OPPOSITE to height gradient: higher terrain
                // to the east means this point's face points west. Sign flipped vs
                // the gradient so craters dip instead of bulging out.
                vec3 bump = T * (hC - hU) + B * (hC - hV);
                surfaceNormal = normalize(surfaceNormal + uBumpStrength * bump * 4.0);
                nDotL = max(dot(surfaceNormal, uSunDirection), 0.0);
                if (uHasSecondarySun == 1) {
                    nDotL2 = max(dot(surfaceNormal, uSun2Direction), 0.0);
                }
            }
        }

        // Sky irradiance: atmosphere scatters light back down onto the surface.
        // Creates blue-tinted shadows on Earth-likes, smooth terminator gradients.
        // skyIrradianceTotal already includes both stars' atmospheric scatter.
        vec3 surfaceAmbient = skyIrradianceTotal * 1.5;

        // Per-sun ring shadow: applied to both direct Lambertian and the
        // sun-driven specular highlight so a ring sweeping in front of one
        // star casts a coherent shadow band across that star's lighting
        // (but not the other's). Skyfill ambient bypasses ring shadows —
        // it represents diffuse sky bounce, not direct sun light.
        vec3 surfacePosKm = physical_p * uPlanetRadius;
        float ringShadow = getRingShadow(surfacePosKm, uSunDirection);

        // Primary star direct + specular
        vec3 directLight = surfaceSunLight * sunAtten * nDotL * ringShadow;
        vec3 specLight = surfaceSpecular(
            baseSurfaceColor, surfaceNormal, -rayDir, uSunDirection, nDotL,
            surfaceSunLight, sunAtten) * ringShadow;

        // Secondary star direct + specular
        if (uHasSecondarySun == 1) {
            vec3 surfaceSunLight2 = uSun2Color * (uSun2Intensity * 0.05);
            float ringShadow2 = getRingShadow(surfacePosKm, uSun2Direction);
            directLight += surfaceSunLight2 * sunAtten2 * nDotL2 * ringShadow2;
            specLight += surfaceSpecular(
                baseSurfaceColor, surfaceNormal, -rayDir, uSun2Direction, nDotL2,
                surfaceSunLight2, sunAtten2) * ringShadow2;
        }

        color = baseSurfaceColor * (directLight + surfaceAmbient) + specLight;
        // Emissive lava: detect warm thermal-gradient pixels (red→orange→yellow)
        // via the R>B margin so dark-red crust, orange flows, and yellow hot
        // channels all glow through the terminator, night side, and fog.
        // Gated on uLavaEmission so warm-toned terrain (tan, rust, iron-oxide)
        // on non-lava worlds can't falsely glow.
        float lavaMask = smoothstep(0.40, 0.75, baseSurfaceColor.r) *
                         smoothstep(0.15, 0.05, baseSurfaceColor.b);
        color += baseSurfaceColor * lavaMask * 9.0 * uLavaEmission;
        // Blackbody thermal emission: hot rock glows independently of sunlight,
        // visible through the atmosphere even on the night side of lava worlds.
        color += uThermalEmission * baseSurfaceColor * 4.0;

        // Flat ambient (toggle): full-globe screen-space inverse-tonemap
        // injection. Added AFTER the ring-shadow multiply so shadowed
        // regions still receive the lift.
        color += hdrAmbient(baseSurfaceColor);
    }

    // ── Back layer (surface/sky + back atmosphere) ──
    vec3 bgAttenuated = color * exp(-tauBack);
    vec3 backLayer = bgAttenuated + atmosphereColorBack;

    // ── Cloud compositing ──
    vec3 colorWithCloud = backLayer;
    if (cAlpha > 0.001) {
        colorWithCloud = mix(backLayer, cloudColorFinal, cAlpha);
    }

    // ── Front atmosphere ──
    vec3 frontBgAttenuated = colorWithCloud * exp(-tauFront);
    vec3 finalColor = frontBgAttenuated + atmosphereColorFront;

    // ── Ambient atmosphere fill (toggle) ──
    // Lifts the atmosphere in its base scattering colour so the limb glows
    // under ambient — but skips the directional phase functions entirely,
    // so the sun-driven Mie/Rayleigh scattering at the terminator (sunset
    // glow on the day-side limb, blue crepuscular edge) remains visually
    // distinct on top. Full-globe (no night mask): the day-side direct
    // scatter is already saturated post-exposure, so the ambient injection
    // adds proportionally less to it after the Reinhard tonemap. The
    // limb at the night side reads as a soft base-tint glow.
    if (uAmbientLight > 0.0) {
        // Base atmosphere colour: rayleigh + mie scattering coefficients,
        // normalised to a [0, 1] hue (Earth → blue, Mars → tan, Titan → orange).
        vec3 atmRaw = uRayleighScattering + uMieScattering;
        float atmMax = max(max(atmRaw.r, atmRaw.g), max(atmRaw.b, 1e-5));
        vec3 atmBaseTint = atmRaw / atmMax;
        // Scalar atmosphere thickness along the view ray (averaged across
        // channels). Higher near the limb where the ray traverses more
        // atmosphere; zero where there's no atmosphere along the ray.
        float atmThickness = clamp(1.0 - (exp(-tauView.r) + exp(-tauView.g) + exp(-tauView.b)) / 3.0, 0.0, 1.0);
        // 0.6 keeps the atmospheric ambient visually subordinate to the
        // surface lift so the planet still reads as the dominant subject.
        finalColor += hdrAmbient(atmBaseTint) * atmThickness * 0.6;
    }

    // ── Ring in front of atmosphere ──
    // Covers two cases: (1) ring between camera and planet surface (hitGround
    // && ringInFront), and (2) ring closer than the atmospheric limb on a
    // miss ray (!hitGround && tRing_s < tMin). Both composite on top after
    // all atmosphere processing. Both stars light the ring; each casts its
    // own planet shadow on the disc.
    bool ringInFrontOfAtm = ringDens > 0.001 &&
        ((hitGround && ringInFront) || (!hitGround && !ringBehindAtm));
    if (ringInFrontOfAtm) {
        vec3 ringSunLight = uSunColor * (uSunIntensity * 0.05);
        float ringPhoto = ringPhotometric(uSunDirection, rayDir, ringDens);
        float pShad = ringInPlanetShadow(ringHitPhys, uSunDirection) ? 0.0 : 1.0;
        vec3 ringLit = ringCol * ringSunLight * ringPhoto * pShad;
        if (uHasSecondarySun == 1) {
            vec3 ringSunLight2 = uSun2Color * (uSun2Intensity * 0.05);
            float ringPhoto2 = ringPhotometric(uSun2Direction, rayDir, ringDens);
            float pShad2 = ringInPlanetShadow(ringHitPhys, uSun2Direction) ? 0.0 : 1.0;
            ringLit += ringCol * ringSunLight2 * ringPhoto2 * pShad2;
        }
        // Ambient bypasses pShad so the umbra-shadowed ring still lifts.
        ringLit += hdrAmbient(ringCol);
        finalColor = mix(finalColor, ringLit, ringOpacity(ringDens));
    }

    // ── Tone mapping + gamma (deferred to composite pass when uPostprocess == 1) ──
    writeFinal(finalColor, rayDir, ringInFront ? ringOpacity(ringDens) : 0.0);
}
