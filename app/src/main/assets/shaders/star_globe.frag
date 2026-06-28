#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

// ── Camera ──
uniform mat4 uInverseProjection;
uniform mat4 uInverseView;
uniform vec3 uCameraPos;

// ── Star geometry ──
uniform float uStarRadius;          // km
uniform float uOblateness;          // 0 = sphere, >0 = oblate (brown dwarfs)

// ── Star appearance ──
uniform vec3 uStarColor;            // linear RGB from blackbody
uniform float uStarIntensity;       // emission multiplier
uniform float uLimbDarkeningCoeff;  // 0.0–1.0
uniform float uExposure;            // auto-exposure for tonemapper

// ── Noise animation ──
uniform float uTime;                // elapsed seconds
uniform float uSpinAngle;           // radians around Y axis; rapid for pulsars
uniform float uNoiseScale1;         // primary convection scale (~4.0)
uniform float uNoiseScale2;         // fine detail scale (~8.0)
uniform float uConvectionStrength;  // brightness variation amplitude
uniform vec3  uNoiseSeedOffset;     // per-star 3D offset so each star has a unique pattern

// ── Corona ──
uniform float uCoronaIntensity;
uniform float uCoronaExtent;        // radii (1.5–3.0)

// ── Atmospheric limb (brown dwarfs only) ──
// T/Y dwarfs have 250–1300 K photospheres and no coronal-heating
// mechanism, so the plasma-corona model doesn't fit them. Instead we
// gate the corona to zero (via uCoronaIntensity = 0 from the renderer)
// and render a separate, much tighter atmospheric-limb halo: a soft
// glow extending a small fraction of a star-radius past the photosphere,
// tinted by the dwarf's intrinsic atmospheric colour (one of the
// brown-dwarf bake palette's brighter band colours) multiplied by the
// photosphere's own light. uAtmosphereLimbExtent = 0 disables the term.
uniform float uAtmosphereLimbExtent;  // radii past photosphere (~0.1–0.2)
uniform vec3  uAtmosphereLimbColor;   // intrinsic atmosphere tint

// ── Brown dwarf texture path ──
uniform int uUseTexture;            // 0 = procedural, 1 = baked gas giant texture
uniform sampler2D uPlanetTexture;   // equirectangular baked surface

// ── L dwarf cellular surface ──
// L dwarfs share their cool-red colour with M main-sequence dwarfs, so
// without a distinct surface texture the two are visually identical in
// the globe view. L photospheres are cool enough for silicate / iron
// cloud condensation but still warm enough for visible convection — the
// pattern reads as discrete cellular structures (resembling the porous-
// regolith / vesicular look of a basalt) rather than the smooth blobby
// granulation that FBM produces on Sun-like stars. uIsLDwarf = 1
// switches the surface from FBM to a Voronoi-cell pattern that gives
// L dwarfs a unique visual identity from their M-dwarf neighbours.
uniform int uIsLDwarf;

// ── Reveal multiplier ──
// 0 hides the star entirely, 1 renders at full brightness. Renderer ramps
// 0→1 over the fade-in window so the star lights up from black instead of
// popping in with stale data.
uniform float uReveal;

// ── Post-process gating ──
// 0 = write sRGB + tonemapped to the default framebuffer (direct path)
// 1 = write linear HDR for the bloom pipeline; tonemap + gamma happen in the composite pass
uniform int uPostprocess;

// ── Constants ──
const float PI = 3.14159265359;

// ────────────────────────────────────────────────────────────
// ShaderToy-style lattice noise. Used for corona polar-coord
// filaments (where the mod() tiling reads as repeating wisps).
// Not suitable for 3D surface sampling — see noise3d below.
// ────────────────────────────────────────────────────────────
float snoise(vec3 uv, float res) {
    const vec3 s = vec3(1e0, 1e2, 1e4);
    uv *= res;
    vec3 uv0 = floor(mod(uv, res)) * s;
    vec3 uv1 = floor(mod(uv + vec3(1.0), res)) * s;
    vec3 f = fract(uv); f = f * f * (3.0 - 2.0 * f);
    vec4 v = vec4(uv0.x + uv0.y + uv0.z, uv1.x + uv0.y + uv0.z,
                  uv0.x + uv1.y + uv0.z, uv1.x + uv1.y + uv0.z);
    vec4 r = fract(sin(v * 1e-3) * 1e5);
    float r0 = mix(mix(r.x, r.y, f.x), mix(r.z, r.w, f.x), f.y);
    r = fract(sin((v + uv1.z - uv0.z) * 1e-3) * 1e5);
    float r1 = mix(mix(r.x, r.y, f.x), mix(r.z, r.w, f.x), f.y);
    return mix(r0, r1, f.z) * 2.0 - 1.0;
}

// ────────────────────────────────────────────────────────────
// IQ-style 3D value noise — rotationally well-behaved on a sphere.
// The asymmetric constant vec3(0.71, 0.113, 0.419) breaks the
// coordinate-swap symmetry that would otherwise produce diagonal
// mirror seams. Returns value in [-1, 1].
// ────────────────────────────────────────────────────────────
float hash3d(vec3 p) {
    p = 50.0 * fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419));
    return -1.0 + 2.0 * fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise3d(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = hash3d(i + vec3(0.0, 0.0, 0.0));
    float n100 = hash3d(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash3d(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash3d(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash3d(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash3d(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash3d(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash3d(i + vec3(1.0, 1.0, 1.0));
    float nx00 = mix(n000, n100, f.x);
    float nx10 = mix(n010, n110, f.x);
    float nx01 = mix(n001, n101, f.x);
    float nx11 = mix(n011, n111, f.x);
    float nxy0 = mix(nx00, nx10, f.y);
    float nxy1 = mix(nx01, nx11, f.y);
    return mix(nxy0, nxy1, f.z);
}

// 5-octave FBM for convection granulation
float fbm3d(vec3 p) {
    float f = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        f += a * noise3d(p);
        p = p * 2.03 + vec3(17.1, 31.7, 11.3);
        a *= 0.5;
    }
    return f;
}

// ────────────────────────────────────────────────────────────
// 3D Worley / Voronoi noise. Returns F1 (distance to nearest
// feature point) and F2 (distance to second-nearest), packed
// in xy. (F2 - F1) gives the visible cell-edge pattern that
// produces the polygonal "cracked basalt" / "regolith plate"
// look — used by the L dwarf surface path so L dwarfs visually
// distinguish from the FBM-granulated M dwarfs they overlap in
// colour. 3-channel hash; 3³ neighbour-cell scan; non-jittered
// feature-point positions are at integer lattice corners,
// jittered by the hash for irregular cell shapes.
// ────────────────────────────────────────────────────────────
vec3 voronoiHash3d(vec3 p) {
    p = vec3(
        dot(p, vec3(127.1, 311.7, 74.7)),
        dot(p, vec3(269.5, 183.3, 246.1)),
        dot(p, vec3(113.5, 271.9, 124.6)));
    return fract(sin(p) * 43758.5453);
}

vec2 voronoi3d(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    float f1 = 8.0;
    float f2 = 8.0;
    for (int z = -1; z <= 1; z++) {
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                vec3 b = vec3(float(x), float(y), float(z));
                vec3 r = b - f + voronoiHash3d(i + b);
                float d = dot(r, r);
                if (d < f1) { f2 = f1; f1 = d; }
                else if (d < f2) { f2 = d; }
            }
        }
    }
    return vec2(sqrt(f1), sqrt(f2));
}

// ────────────────────────────────────────────────────────────
// Ray-sphere (oblate) intersection
// Returns (tNear, tFar); tNear < 0 means miss
// ────────────────────────────────────────────────────────────
vec2 raySphereIntersect(vec3 ro, vec3 rd, float radius, float oblate) {
    // Scale Y by 1/(1-oblate) to turn oblate ellipsoid into unit sphere
    float yScale = 1.0 / (1.0 - oblate);
    vec3 ro2 = vec3(ro.x, ro.y * yScale, ro.z);
    vec3 rd2 = vec3(rd.x, rd.y * yScale, rd.z);

    float a = dot(rd2, rd2);
    float b = 2.0 * dot(ro2, rd2);
    float c = dot(ro2, ro2) - radius * radius;
    float disc = b * b - 4.0 * a * c;
    if (disc < 0.0) return vec2(-1.0);
    float sq = sqrt(disc);
    return vec2((-b - sq) / (2.0 * a), (-b + sq) / (2.0 * a));
}

// ────────────────────────────────────────────────────────────
// Rigid rotation of a point around the Y (spin) axis. Used to
// rotate the noise-sample point so the surface pattern appears
// to spin in place — proper rotation rather than the translation
// in noise-space that the per-axis time terms below produce
// (which read as radial flow toward an X pole, not rotation).
// ────────────────────────────────────────────────────────────
vec3 rotateY(vec3 p, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c);
}

// ────────────────────────────────────────────────────────────
// Reinhard luminance-preserving tone mapping
// ────────────────────────────────────────────────────────────
vec3 tonemap(vec3 c) {
    float maxC = max(c.r, max(c.g, c.b));
    if (maxC <= 0.0) return vec3(0.0);
    float mapped = maxC / (1.0 + maxC);
    return c * (mapped / maxC);
}

void main() {
    // ── Reconstruct world-space ray ──
    vec2 ndc = vUv * 2.0 - 1.0;
    vec4 clipPos = vec4(ndc, -1.0, 1.0);
    vec4 viewPos = uInverseProjection * clipPos;
    viewPos = vec4(viewPos.xyz / viewPos.w, 0.0);
    vec3 rd = normalize((uInverseView * viewPos).xyz);
    vec3 ro = uCameraPos;

    // ── Ray-sphere intersection ──
    vec2 t = raySphereIntersect(ro, rd, uStarRadius, uOblateness);
    bool hitSphere = t.x > 0.0;

    vec3 finalColor = vec3(0.0);

    if (hitSphere) {
        // ── Hit: surface shading ──
        vec3 hitPoint = ro + rd * t.x;

        // Compute normal (oblate sphere)
        float yScale = 1.0 / (1.0 - uOblateness);
        vec3 normal = normalize(vec3(hitPoint.x, hitPoint.y * yScale * yScale, hitPoint.z));
        vec3 viewDir = -rd;
        float mu = max(dot(normal, viewDir), 0.0);

        // Limb darkening (linear law)
        float limb = 1.0 - uLimbDarkeningCoeff * (1.0 - mu);

        vec3 surfaceColor;

        if (uUseTexture == 1) {
            // ── Brown dwarf: sample baked gas giant texture ──
            // Equirectangular from unit sphere normal (matches planet.frag)
            vec3 n = normalize(hitPoint);
            float ny = clamp(n.y, -1.0, 1.0);
            float u = 1.0 - (atan(n.z, n.x) + PI) / (2.0 * PI);
            float v = (asin(ny) + PI * 0.5) / PI;
            vec3 texColor = texture(uPlanetTexture, vec2(u, v)).rgb;
            // Decode sRGB to linear
            texColor = pow(texColor, vec3(2.2));

            // Light shimmer from convection noise (low amplitude on top of bands).
            // Rotate the sample point around the spin axis so the shimmer
            // pattern spins with the body rather than translating along X.
            vec3 surfPt = rotateY(hitPoint / uStarRadius, uSpinAngle);
            float n1 = noise3d(surfPt * uNoiseScale1 + uNoiseSeedOffset +
                               vec3(uTime * 0.15, 0.0, 0.0));
            float shimmer = n1 * uConvectionStrength;
            texColor *= (1.0 + shimmer);

            surfaceColor = texColor * uStarIntensity * limb;

            // Uniform emissive boost — applied equally to both band colours
            // so dark maroon lanes and bright salmon bands share the same
            // glow amplitude, each tinted by its own texture colour.
            surfaceColor += texColor * uStarIntensity * 0.2;
        } else {
            // ── Normal star: procedural convection noise ──
            // Per-star seed offset breaks the otherwise identical noise
            // lattice so no two stars share a pattern. The sample point is
            // rotated around the spin axis (uSpinAngle) so the surface
            // appears to physically rotate; the per-axis uTime translations
            // below provide secondary "slow boil" evolution on top.
            vec3 surfPt = rotateY(hitPoint / uStarRadius, uSpinAngle);
            vec3 seedOff = uNoiseSeedOffset;

            if (uIsLDwarf == 1) {
                // ── L dwarf Voronoi cellular surface ──
                // Two-octave Voronoi: a base octave at the granulation
                // scale gives the dominant cell pattern; a finer second
                // octave adds sub-cell detail inside each plate so the
                // cells don't read as flat coloured polygons. (F2 - F1)
                // gives the visible cell-edge crack — bright where the
                // boundary cuts between feature points, dark inside the
                // cells. Inverted so cell interiors are the "hot upwell"
                // and the cracks are the cool intergranular lanes,
                // matching the hot/cool convention used by the FBM path.
                //
                // Low-frequency FBM domain warp so the Voronoi lattice
                // deforms — cell boundaries curve organically instead of
                // running as the dead-straight perpendicular bisectors a
                // raw Voronoi gives. Warp magnitude is small (≈ 0.30 of
                // one cell width) so cells stay recognisable as cells
                // rather than smearing into FBM blobs, and is applied to
                // both octaves identically so the two scales remain
                // visually coherent.
                float vScale1 = uNoiseScale1 * 0.10;
                float vScale2 = uNoiseScale1 * 0.22;
                vec3 vp = surfPt + seedOff * 0.01 + vec3(uTime * 0.04, 0.0, 0.0);
                vec3 warpP = vp * (vScale1 * 0.6) + seedOff * 0.03;
                vec3 warp = vec3(
                    fbm3d(warpP),
                    fbm3d(warpP + vec3(5.2, 1.3, 7.7)),
                    fbm3d(warpP + vec3(11.3, 9.1, 3.5))
                ) * (0.30 / max(vScale1, 1e-4));
                vec2 v1 = voronoi3d((vp + warp) * vScale1);
                vec2 v2 = voronoi3d((vp + warp) * vScale2 + vec3(11.3, 7.7, 3.5));
                // Cell-edge crack pattern. clamp keeps it in [0, 1].
                float edge1 = clamp((v1.y - v1.x) * 1.8, 0.0, 1.0);
                float edge2 = clamp((v2.y - v2.x) * 1.5, 0.0, 1.0);
                // Interior brightness via F1 distance — closer to a
                // feature point reads as upwelling centre, far edges
                // (which the crack mask catches) read as cooler.
                float interior = 1.0 - smoothstep(0.0, 0.45, v1.x);
                float convection = interior * 0.9 - edge1 * 1.2 - edge2 * 0.3;

                vec3 hotTint = uStarColor * 1.5;
                vec3 coolTint = uStarColor * vec3(0.45, 0.30, 0.20);
                vec3 modulated = mix(coolTint, hotTint, clamp(convection * 0.5 + 0.5, 0.0, 1.0));
                surfaceColor = modulated * uStarIntensity * limb;
                surfaceColor *= (1.0 + convection * uConvectionStrength);

                // Emissive boost on cell interiors — bright "upwelling"
                // cores past the dark cracks, mirroring the FBM path's
                // hot-granulation pop.
                float emissiveMask = smoothstep(0.0, 0.6, interior * (1.0 - edge1));
                surfaceColor += hotTint * emissiveMask * uStarIntensity * 1.3;
            } else {
            // Supergranulation: very low-frequency brightness modulation,
            // simulating the Sun's ~30,000 km large-scale convection pattern
            float superGran = noise3d(surfPt * 2.2 + seedOff +
                                      vec3(uTime * 0.08, 0.0, 0.0));

            // Domain warp: advect the FBM input by a low-freq noise field.
            // Turns symmetric blobs into organic swirling flow shapes.
            vec3 warpP = surfPt * (uNoiseScale1 * 0.25) + seedOff;
            vec3 warp = vec3(
                noise3d(warpP + vec3(uTime * 0.20, 0.0, 0.0)),
                noise3d(warpP + vec3(5.2 + uTime * 0.20, 1.3, 0.0)),
                noise3d(warpP + vec3(uTime * 0.12, 7.1, 2.7))
            ) * 0.9;

            // FBM granulation (warped) + fine detail layer
            float n1 = fbm3d(surfPt * uNoiseScale1 + seedOff + warp +
                             vec3(uTime * 0.45, 0.0, 0.0));
            float n2 = noise3d(surfPt * uNoiseScale2 + seedOff +
                               vec3(uTime * 0.85, 0.0, uTime * 0.30));

            // Ridged filaments: 1 - |noise| on a stretched coord space gives
            // bright streaky structures suggesting chromospheric fibrils
            vec3 stretchP = vec3(surfPt.x * 2.5, surfPt.y, surfPt.z * 2.5) *
                            uNoiseScale1 * 0.7 + seedOff;
            float ridged = 1.0 - abs(noise3d(stretchP + warp * 0.5 +
                                             vec3(uTime * 0.10, 0.0, 0.0)));
            ridged = pow(ridged, 3.0);

            float convection = n1 * 0.55 + n2 * 0.25 + superGran * 0.35 + ridged * 0.25;

            // Map noise to hot/cool color variation — wide contrast so
            // bright granulation cells stand out against dark intergranular lanes
            vec3 hotTint = uStarColor * 1.5;
            vec3 coolTint = uStarColor * vec3(0.45, 0.35, 0.25);
            vec3 modulated = mix(coolTint, hotTint, convection * 0.5 + 0.5);

            surfaceColor = modulated * uStarIntensity * limb;
            surfaceColor *= (1.0 + convection * uConvectionStrength);

            // Emissive boost on hot granulation cells — uses the positive tail
            // of the convection noise as a mask so bright upwelling regions
            // "pop" past the tonemapper, analogous to lava glow in planet.frag.
            float emissiveMask = smoothstep(-0.35, 0.35, convection);
            surfaceColor += hotTint * emissiveMask * uStarIntensity * 2.0;

            // ── Discrete bright spots (faculae) ──
            // Two-layer structure so spots read as concentrated areas of
            // smaller granular cells rather than smooth blobs:
            //  (1) low-frequency mask (scale 1.5) defines WHERE the spots
            //      are; high threshold smoothstep(0.45 → 0.65) keeps only
            //      the rare positive tail so spot regions cover well under
            //      the dark intergranular lanes' surface area.
            //  (2) high-frequency cell layer (scale uNoiseScale1 * 2 —
            //      cells about half the size of the surrounding
            //      granulation) provides the granular detail INSIDE each
            //      spot. max(.., 0) clamps to positive contributions so
            //      the cells brighten within the mask, never darken.
            // Slow independent time drift on the mask (0.04) lets spot
            // regions evolve at their own pace; faster drift on the cells
            // (0.3) so the tiny granulation inside boils visibly.
            float spotRegion = noise3d(surfPt * 1.5 + seedOff +
                                       vec3(uTime * 0.04, 11.3, 7.7));
            float spotMask = smoothstep(0.45, 0.65, spotRegion);
            float spotCells = noise3d(surfPt * uNoiseScale1 * 2.0 + seedOff +
                                      vec3(uTime * 0.3, 5.2, 14.7));
            spotCells = max(spotCells, 0.0);
            vec3 spotTint = uStarColor * 2.0;
            surfaceColor += spotTint * spotMask * spotCells * uStarIntensity * 3.5;
            }  // end uIsLDwarf else
        }

        // Fresnel edge brightening — counteracts limb darkening at the very edge
        // to create a sharp photosphere boundary feeding into the corona
        float fresnel = pow(1.0 - mu, 2.0);
        surfaceColor += uStarColor * uStarIntensity * fresnel * 0.15;

        finalColor = surfaceColor;
    }

    // ── Corona / glow ──
    // Radial falloff measured in the SAME oblate-aware space the surface
    // intersection uses: scale Y by 1/(1−oblate) so the squashed star
    // becomes a sphere, find closest approach in that space, and convert
    // to star-radii units. The glow then flattens with the star at the
    // poles and bulges at the equator instead of staying circular.
    float yScaleC = 1.0 / (1.0 - uOblateness);
    vec3 roY = vec3(ro.x, ro.y * yScaleC, ro.z);
    vec3 rdY = vec3(rd.x, rd.y * yScaleC, rd.z);
    float rdYLenSq = max(dot(rdY, rdY), 1e-12);
    float tClosest = -dot(roY, rdY) / rdYLenSq;
    vec3 closestPt = roY + rdY * max(tClosest, 0.0);
    float dist = length(closestPt);
    float r = dist / uStarRadius; // in star radii (oblate-aware)

    // Two-term glow: tight inner halo + soft outer bloom.
    // Outer is a hard-clamped power falloff so it strictly vanishes at
    // (1 + coronaExtent) radii — no infinite exponential tail.
    float inner = exp(-(max(r - 1.0, 0.0)) * 8.0);
    float tOuter = clamp((r - 1.0) / max(uCoronaExtent, 0.1), 0.0, 1.0);
    float outer = pow(1.0 - tOuter, 3.0);
    float glow = inner * 0.7 + outer * 0.15;

    // Suppress glow inside the disk on hit rays (it's behind the surface)
    if (hitSphere) {
        glow = 0.0;
    }

    finalColor += uStarColor * uStarIntensity * glow * uCoronaIntensity * 0.8;

    // ── Atmospheric limb (brown dwarfs) ──
    // Tighter falloff than the corona — `pow(1-t, 2.5)` over a small
    // extent (~0.15 R★ in practice). The tint multiplies the atmosphere's
    // intrinsic colour by the photosphere's blackbody light: the limb
    // glow is the dwarf's own light passing through the atmospheric
    // scattering profile of its H₂/He/CH₄ envelope. Suppressed inside
    // the disc on hit rays — the photosphere itself already carries the
    // surface colour; the limb is the extension past the photosphere.
    if (uAtmosphereLimbExtent > 0.0) {
        float tLimb = clamp((r - 1.0) / uAtmosphereLimbExtent, 0.0, 1.0);
        float limbGlow = pow(1.0 - tLimb, 2.5);
        if (hitSphere) limbGlow = 0.0;
        finalColor += uAtmosphereLimbColor * uStarColor * uStarIntensity * limbGlow * 0.6;
    }

    // ── Reveal fade ──
    // Multiplied before exposure so corona, surface, and bloom all fade
    // together. Held at 0 by the renderer until the first frame after
    // navigation has settled with the correct star data, then ramped up
    // — hides the brief window where camera bounds and star uniforms
    // could be using stale values from a previous star.
    finalColor *= uReveal;

    // ── Exposure (always applied so bloom extract sees exposed HDR) ──
    finalColor *= uExposure;

    if (uPostprocess == 1) {
        // Linear HDR — composite pass handles tonemap + gamma
        fragColor = vec4(finalColor, 1.0);
    } else {
        // Direct path: Reinhard tonemap + sRGB encode
        finalColor = tonemap(finalColor);
        finalColor = pow(finalColor, vec3(1.0 / 2.2));
        fragColor = vec4(finalColor, 1.0);
    }
}
