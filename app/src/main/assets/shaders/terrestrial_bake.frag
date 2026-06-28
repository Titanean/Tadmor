#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;

in vec2 vUv;
out vec4 fragColor;

// ── 6-stop terrain elevation palette (linear RGB 0-1) ──
// Fixed thresholds: 0.00, 0.30, 0.50, 0.75, 0.95, 1.00
// [0]=deep basins  [1]=lowlands  [2]=mid plains  [3]=highlands  [4]=peaks  [5]=summits
uniform vec3 uTerrain[6];

uniform vec3  uColorWater;          // ocean / ice / lava / hydrocarbon
uniform vec3  uColorPolar;          // polar cap material
uniform bool  uWaterIsIce;          // true = frozen sea (allow depth/topology), false = liquid (flat)

// ── Composition weights (for spatial variation) ──
uniform float uWaterFraction;
uniform float uCarbonFraction;
uniform float uTholinFraction;

// ── Surface context ──
uniform float uSeaLevel;            // [0,1] elevation threshold — below = ocean
uniform float uPolarCap;            // [0,1] extent
uniform float uRoughness;
uniform float uNoiseScale;          // planet-radius-derived scale [0.8, 4.0]
uniform float uCraterDensityField;  // Worley background crater density
uniform float uVolcanism;           // volcanic activity [0,1] — drives hotspots when >0.7
uniform int   uMoltenSurface;       // 1 = lava world (full molten paint), 0 = volcanic only (cooled basalt + glowing rim)
uniform bool  uTidallyLocked;
uniform vec3  uSubSolarDir;         // subsolar direction (eyeball tidal poles)

// ── Permutation texture (256×1 R8, NEAREST) ──
uniform sampler2D uPermTex;

// ── Large craters (max 150, CPU-generated power-law distribution) ──
uniform int  uNumCraters;
uniform vec4 uCrater[150];          // xyz = sphere position, w = radius
uniform vec2 uCraterProp[150];      // x = depth [0,1], y = degradation [0,1]

const float PI = 3.14159265;

// ─────────────────────────────────────────────
// Perlin noise via permutation texture
// (identical to gas_giant_bake.frag)
// ─────────────────────────────────────────────

int perm(int i) {
    return int(texelFetch(uPermTex, ivec2(i & 255, 0), 0).r * 255.0 + 0.5);
}

float fade(float t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); }

float grad(int hash, float x, float y, float z) {
    int h = hash & 15;
    float u = (h < 8) ? x : y;
    float v = (h < 4) ? y : (((h == 12) || (h == 14)) ? x : z);
    return (((h & 1) == 0) ? u : -u) + (((h & 2) == 0) ? v : -v);
}

float perlin3D(vec3 pos) {
    int X = int(floor(pos.x)) & 255;
    int Y = int(floor(pos.y)) & 255;
    int Z = int(floor(pos.z)) & 255;
    vec3 f = fract(pos);
    float u = fade(f.x), v = fade(f.y), w = fade(f.z);
    int A  = perm(X)   + Y, AA = perm(A)   + Z, AB = perm(A+1) + Z;
    int B  = perm(X+1) + Y, BA = perm(B)   + Z, BB = perm(B+1) + Z;
    return mix(
        mix(mix(grad(perm(AA),   f.x,       f.y,       f.z      ),
                grad(perm(BA),   f.x - 1.0, f.y,       f.z      ), u),
            mix(grad(perm(AB),   f.x,       f.y - 1.0, f.z      ),
                grad(perm(BB),   f.x - 1.0, f.y - 1.0, f.z      ), u), v),
        mix(mix(grad(perm(AA+1), f.x,       f.y,       f.z - 1.0),
                grad(perm(BA+1), f.x - 1.0, f.y,       f.z - 1.0), u),
            mix(grad(perm(AB+1), f.x,       f.y - 1.0, f.z - 1.0),
                grad(perm(BB+1), f.x - 1.0, f.y - 1.0, f.z - 1.0), u), v),
        w);
}

// ─────────────────────────────────────────────
// FBM matching terrestrial.html PoC:
//   frequency starts at `scale` (not 1.0), doubles each octave.
//   returns [0,1] via normalise + 0.5 shift.
// ─────────────────────────────────────────────

float fbm(vec3 p, int maxOct, float persistence, float scale) {
    float total = 0.0, amp = 1.0, freq = scale, maxVal = 0.0;
    for (int i = 0; i < 8; i++) {
        if (i >= maxOct) break;
        total  += perlin3D(p * freq) * amp;
        maxVal += amp;
        amp    *= persistence;
        freq   *= 2.0;
    }
    return clamp(total / maxVal + 0.5, 0.0, 1.0);
}

// Domain warp matching PoC:
//   samples three shifted FBMs to get a warp vector, then samples the final FBM
//   displaced by that vector. The offsets 5.2/1.3/2.1 and -3.4/2.8/-4.2 are the
//   same as in terrestrial.html, decorrelating the three warp components.
float domainWarp(vec3 p, int octaves, float persistence, float scale, float warpStrength) {
    float qx = fbm(p,                            octaves, persistence, scale) * 2.0 - 1.0;
    float qy = fbm(p + vec3( 5.2,  1.3,  2.1),  octaves, persistence, scale) * 2.0 - 1.0;
    float qz = fbm(p + vec3(-3.4,  2.8, -4.2),  octaves, persistence, scale) * 2.0 - 1.0;
    return fbm(p + vec3(qx, qy, qz) * warpStrength, octaves, persistence, scale);
}

// ─────────────────────────────────────────────
// Worley (cellular F1) for background crater field
// ─────────────────────────────────────────────

vec3 worleyHash(ivec3 c) {
    int h  = perm(perm(perm(c.x & 255) + (c.y & 255)) + (c.z & 255));
    int h2 = perm(h + 1);
    int h3 = perm(h + 2);
    return vec3(float(h  & 255), float(h2 & 255), float(h3 & 255)) / 255.0;
}

float worley3D(vec3 p) {
    ivec3 cell  = ivec3(floor(p));
    vec3  local = fract(p);
    float minD  = 9.0;
    for (int dz = -1; dz <= 1; dz++) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                ivec3 nb = cell + ivec3(dx, dy, dz);
                vec3  fp = vec3(nb) + worleyHash(nb);
                minD = min(minD, length(local - (fp - vec3(cell))));
            }
        }
    }
    return clamp(minD, 0.0, 1.0);
}

// ─────────────────────────────────────────────
// 6-stop elevation → color
// Direct port of PoC's getColorForElevation():
//   blendIntensity=0 → double-smoothstep (sharp terrace boundaries)
//   blendIntensity=1 → linear (smooth gradients)
// ─────────────────────────────────────────────

float blendedT(float t, float blend) {
    float sharp = t * t * (3.0 - 2.0 * t);
    sharp = sharp * sharp * (3.0 - 2.0 * sharp);  // double smoothstep
    return mix(sharp, t, blend);
}

// Thresholds: 0.00, 0.30, 0.50, 0.75, 0.95, 1.00
vec3 terrainColor(float e, float noiseInject, float blend) {
    // Tiny noise injection breaks up hard palette edges (PoC: ±0.02)
    e = clamp(e + (noiseInject - 0.5) * 0.04, 0.0, 1.0);
    if      (e < 0.30) return mix(uTerrain[0], uTerrain[1], blendedT( e         / 0.30, blend));
    else if (e < 0.50) return mix(uTerrain[1], uTerrain[2], blendedT((e - 0.30) / 0.20, blend));
    else if (e < 0.75) return mix(uTerrain[2], uTerrain[3], blendedT((e - 0.50) / 0.25, blend));
    else if (e < 0.95) return mix(uTerrain[3], uTerrain[4], blendedT((e - 0.75) / 0.20, blend));
    else               return mix(uTerrain[4], uTerrain[5], blendedT((e - 0.95) / 0.05, blend));
}

// ─────────────────────────────────────────────
// Main
// ─────────────────────────────────────────────

void main() {
    // ── Equirectangular UV → unit sphere direction ──
    float phi    = (1.0 - vUv.x) * 2.0 * PI - PI;
    float latRad = (vUv.y - 0.5) * PI;
    float cosLat = cos(latRad);
    vec3 P = vec3(cosLat * cos(phi), sin(latRad), cosLat * sin(phi));

    // Pre-divide by PI to match PoC's cx/cy/cz coordinate space:
    //   PoC uses cx = R * cos(theta)*cos(phi) where R = aspect/(2*PI) = 1/PI for 2:1 canvas.
    //   Our unit sphere P divided by PI gives the same spatial scale.
    vec3 C = P / PI;

    float scale = uNoiseScale;  // = PoC's scaleBase, range [0.8, 4.0]

    // ── Regional suppressor (continent-scale amplitude mask) ──
    // Creates varied regions: flat plains, rugged highlands, smooth basins.
    float rawSup = fbm(C + vec3(-77.7, 88.8, -99.9), 3, 0.5, scale * 0.15);
    float sup    = clamp((rawSup - 0.5) * 2.0 + 0.5, 0.0, 1.0);
    sup = sup * sup * (3.0 - 2.0 * sup);                  // smoothstep contrast boost
    float dynScale = scale * (0.6 - sup * 0.45);          // macro FBM scale varies with region
    // Domain warp strength is now a fixed 0.45 — uniform across regions
    // and planets. The warp's spatial size still scales with planet
    // radius because every domainWarp call's `scale` argument is derived
    // from `uNoiseScale`, which is computed from planet radius (see
    // buildTerrestrialBakeData in PlanetGlobeParams.kt).
    const float WARP_STRENGTH = 0.45;

    // ── Regional blend map (sharp vs smooth terrain transitions) ──
    float blendI = fbm(C + vec3(-33.3, 44.4, -55.5), 3, 0.5, scale * 0.15);

    // ── Macro terrain (continental shapes via domain warp) ──
    float macro   = domainWarp(C, 4, 0.5, dynScale, WARP_STRENGTH);
    float contI   = macro * macro * (3.0 - 2.0 * macro);  // smoothstep → sharp continent edges

    // ── Micro detail (fine surface texture) ──
    // PoC uses 12 octaves; we use 5 for mobile performance — same visual character.
    float micro   = domainWarp(C, 5, 0.5, scale * 1.2, WARP_STRENGTH);

    // ── Patch mask (blends macro structure with micro noise) ──
    float rawMask = fbm(C + vec3(42.1, 17.3, 11.2), 3, 0.5, scale * 0.25);
    float maskC   = clamp((rawMask - 0.5) * 2.5 + 0.5, 0.0, 1.0);
    float patchI  = maskC * maskC * (3.0 - 2.0 * maskC);

    // ── Combined elevation ──
    float structured = contI * 0.60 + micro * 0.40;
    float elev = structured * patchI + micro * (1.0 - patchI);

    // ── Texture noises for organic surface variation ──
    float smoothTex = domainWarp(C + vec3(12.3, 45.6, -14.2), 3, 0.4, scale * 0.8,  WARP_STRENGTH);
    float roughTex  = fbm      (C + vec3(-78.9, 12.3,  23.4), 5, 0.6, scale * 4.0          );
    float hueNoise  = domainWarp(C + vec3(  7.7, -8.8,   9.9), 3, 0.5, scale * 0.6,  WARP_STRENGTH);
    float surfTex   = smoothTex * (1.0 - elev) + roughTex * elev;

    // ── Large crater evaluation ──
    // Wobble the effective radius with low-octave noise so rims break up into natural
    // irregular shapes instead of perfect analytic circles. Softened rim falloff
    // (exp -6 vs -18) widens the rim so it matches the base terrain's frequency band.
    //
    // craterDelta is tracked SEPARATELY from baseElev so we can scale its
    // contribution to the bump-map heightmap independently from base terrain.
    // This keeps craters visible as tactile impressions without the diffuse
    // terrain becoming over-shaded by bump gradients.
    float craterDelta = 0.0;
    for (int i = 0; i < 150; i++) {
        if (i >= uNumCraters) break;
        vec3  cPos  = uCrater[i].xyz;
        float cRad  = uCrater[i].w;
        float cD    = uCraterProp[i].x;  // depth
        float cDeg  = uCraterProp[i].y;  // degradation

        float wobble = (perlin3D(P * 6.0 + cPos * 17.0) + 0.5 * perlin3D(P * 14.0 + cPos * 31.0)) * 0.15;
        float r = length(P - cPos) / max(cRad, 0.001) + wobble;
        if (r < 2.5) {
            float bowl   = -(1.0 - min(r * r, 1.0)) * cD * 0.70;
            float rim    = exp(-6.0 * (r - 1.0) * (r - 1.0)) * cD * 0.20;
            float ejecta = max(0.0, 1.5 - r) * cD * 0.09 * max(0.0, 1.5 - r);
            craterDelta += mix(bowl + rim + ejecta, (bowl + rim + ejecta) * 0.25, cDeg);
        }
    }

    // ── Worley background cratering (airless / inactive worlds) ──
    // Small background crater field contributes as part of the crater delta
    // (treated like the explicit crater impressions for bump purposes).
    if (uCraterDensityField > 0.1) {
        float w = worley3D(P * 9.0);
        float craterMask = 1.0 - smoothstep(0.0, 0.15, w);
        craterDelta -= craterMask * uCraterDensityField * 0.06;
    }

    // Combined elevation used for color / ocean cutoff (unchanged semantics).
    elev = clamp(elev + craterDelta, 0.0, 1.0);

    // ── Physical height for bump-map alpha ──
    // Terrain and crater contributions are scaled independently: terrain is
    // dampened (smooth diffuse surface) while craters remain prominent enough
    // to read as tactile indentations under the bump gradient in planet.frag.
    const float TERRAIN_BUMP_SCALE = 0.25;
    const float CRATER_BUMP_SCALE  = 0.32;
    float heightOut = clamp(
        0.5 +
        (structured * patchI + micro * (1.0 - patchI) - 0.5) * TERRAIN_BUMP_SCALE +
        craterDelta * CRATER_BUMP_SCALE +
        (roughTex - 0.5) * (0.05 + elev * 0.15) * TERRAIN_BUMP_SCALE,
        0.0, 1.0);

    // ── Sea level / ocean coverage mask ──
    // Smooth shoreline: elevations within SHORE_WIDTH of the sea level get
    // partial sea coverage, so beaches blend continuously instead of the
    // nearest-neighbour waterline the old boolean cutoff produced. Matches
    // how the volcanic hotspot painter blends into the surrounding terrain.
    const float SHORE_WIDTH = 0.012;
    float seaMask = (uSeaLevel > 0.0)
        ? smoothstep(uSeaLevel + SHORE_WIDTH, uSeaLevel - SHORE_WIDTH, elev)
        : 0.0;

    // Flatten the heightmap at the sea surface wherever the terrain sits below
    // sea level. Without this, submerged crater bowls and basins still produce
    // bump gradients through the water/lava — reading as shadowed pits under
    // painted-on fluid. We blend between the land bump height and the flat
    // sea-surface height by the same shoreline mask, so beach bump continuity
    // follows the same curve as the color blend.
    float seaHeight = 0.5 + (uSeaLevel - 0.5) * TERRAIN_BUMP_SCALE
                          + (roughTex - 0.5) * 0.008;
    heightOut = clamp(mix(heightOut, seaHeight, seaMask), 0.0, 1.0);

    // ── Base color: terrain palette always, sea color blended on top ──
    // Land palette is sampled unconditionally (clamped to 0 below sea level
    // so submerged pixels pick the lowest-elevation material as their "beach"
    // colour). The sea paint then crossfades in via seaMask.
    float landElev = (uSeaLevel < 1.0)
        ? (elev - uSeaLevel) / (1.0 - uSeaLevel)
        : elev;
    vec3 color = terrainColor(clamp(landElev, 0.0, 1.0), micro, blendI);

    if (seaMask > 0.0) {
        float depth = (uSeaLevel > 0.0) ? clamp((uSeaLevel - elev) / uSeaLevel, 0.0, 1.0) : 0.0;
        // Liquid water reads as smooth and flat (real-world bathymetry is
        // essentially invisible from orbit; the noise-derived basin depth
        // effect read as splotchy artefacts). Ice seas, however, are
        // solid surfaces that have had geological time for cracks,
        // ridges, pressure ridges, and refrozen leads — so we keep some
        // of the depth-derived darkening when uWaterIsIce is true to
        // suggest those topological features. The lava branch below
        // computes its own depth-driven shading regardless.
        float waterDepthAmount = uWaterIsIce ? 0.25 : 0.0;
        vec3 seaColor = uColorWater * (1.0 - depth * waterDepthAmount);

        // Lava detection: deep red water color (R≈1, G<0.4, B<0.15). Builds
        // a monotonic thermal gradient red→orange→yellow over the molten
        // surface so magma oceans read as glowing sheets rather than flat
        // red paint. Colours never reverse — yellow is always hotter than
        // orange, orange always hotter than red.
        float lavaFactor = smoothstep(0.85, 1.00, uColorWater.r) *
                           smoothstep(0.45, 0.20, uColorWater.g) *
                           smoothstep(0.20, 0.05, uColorWater.b);
        if (lavaFactor > 0.01) {
            // Fluid lava look: wide low-frequency warmth layered over a
            // medium-frequency flow field, very gentle plate/crack hint.
            float warmth = fbm(C + vec3( 14.2, -27.8,  63.1), 4, 0.55, scale * 1.2);
            float flow   = fbm(C + vec3(-44.5,  18.9, -31.6), 4, 0.55, scale * 3.5);
            float cracks = fbm(C + vec3(-17.0,   8.0,  51.2), 3, 0.60, scale * 6.5);

            float signal = (warmth - 0.5) * 0.85
                         + (flow   - 0.5) * 0.55
                         + (cracks - 0.5) * 0.35;
            // Remap signed signal to [0,1] thermal coordinate. Bias toward
            // the cool end so most pixels sit in the red→orange range,
            // with yellow reserved for the hottest channels.
            float heat = clamp(signal * 1.4 + 0.38, 0.0, 1.0);
            heat *= (1.0 - depth * 0.55);

            // Three-stop monotonic thermal gradient. The cool stop is
            // deep red (mostly darkened crust), mid is bright orange,
            // hot is near-white yellow. Always red→orange→yellow.
            // Channels are pushed hard toward pure hues — low G/B floor
            // on red, minimal B on orange, reduced B on yellow — so the
            // colours survive Reinhard tonemap compression and the warm
            // additive sunlight without washing out.
            vec3 lavaCool = vec3(0.55, 0.020, 0.005);  // deep saturated red
            vec3 lavaMid  = vec3(1.00, 0.26,  0.015);  // rich orange
            vec3 lavaHot  = vec3(1.00, 0.78,  0.08);   // saturated yellow
            vec3 lava = (heat < 0.55)
                ? mix(lavaCool, lavaMid, smoothstep(0.0, 0.55, heat))
                : mix(lavaMid,  lavaHot, smoothstep(0.55, 1.0, heat));
            seaColor = mix(seaColor, lava, lavaFactor);
        }

        color = mix(color, seaColor, seaMask);
    }

    // ── Volcanic hotspots (land only, extreme activity only) ──
    // Only worlds with >0.7 activity (Io-class) get surface lava patches.
    // Below that, volcanism stays subsurface and doesn't leave visible hot
    // features — the entire planet shouldn't light up just because it has
    // a mildly active mantle. Uses the exact same warmth/flow/cracks FBM
    // and red→orange→yellow three-stop gradient as lava seas so hotspots
    // read as the same molten material, not a separate shader effect.
    // Hotspots glow via the R-B lava mask in planet.frag.
    // Gate hotspots on dry worlds only. Any planet with surface oceans
    // (uSeaLevel > 0) would quench surface lava to basalt rapidly —
    // volcanism on wet worlds manifests as island arcs and cooled flows,
    // not glowing magma. Keeps hotspots confined to Io/Venus/lava-world
    // analogues.
    if (seaMask <= 0.0 && uVolcanism > 0.70 && uSeaLevel <= 0.0) {
        // Volcanic activity controls *coverage area* only, not patch opacity.
        // Hotspots are either fully present (at full lava color) or absent —
        // partial-opacity patches produce faint warm pixels that still trigger
        // lavaMask emission in planet.frag, creating ghostly glow on the night
        // side. By binarising opacity, cool edges simply aren't drawn.
        float vStrength = smoothstep(0.70, 0.95, uVolcanism);
        // Higher frequency than before: patches break into smaller, more
        // numerous features rather than smooth blobs. Stays below base
        // terrain's finest octaves so detail doesn't exceed landform scale.
        float hotspotNoise = fbm(C + vec3(7.3, 42.1, -18.6), 3, 0.55, scale * 3.0);
        // Couple to heightmap: lava preferentially breaches thin-crusted
        // lowlands and pools in basins, while highland plateaux stay mostly
        // cold. Biasing the noise by signed elevation gives patches organic
        // terrain-following boundaries instead of free-floating shapes.
        float terrainBias = (0.5 - elev) * 0.30;
        // Threshold tightens as activity drops, so 0.7 barely shows anything
        // and 1.0 gives Io-style speckled lava fields. This is the *only*
        // place vStrength is used — it shapes coverage, not intensity.
        float threshold = mix(0.90, 0.72, vStrength);
        // Narrow smoothstep gives sharp patch boundaries, keeping partial-
        // opacity pixels to a thin ring that always cools to deep red below.
        float hotMask = smoothstep(threshold, threshold + 0.04, hotspotNoise + terrainBias);
        if (hotMask > 0.0) {
            // Same molten signal as lava seas: 3-octave FBM combined into a
            // signed heat coordinate, biased cool so yellow-hot channels are rare.
            float warmth = fbm(C + vec3( 14.2, -27.8,  63.1), 4, 0.55, scale * 1.2);
            float flow   = fbm(C + vec3(-44.5,  18.9, -31.6), 4, 0.55, scale * 3.5);
            float cracks = fbm(C + vec3(-17.0,   8.0,  51.2), 3, 0.60, scale * 6.5);
            float signal = (warmth - 0.5) * 0.85
                         + (flow   - 0.5) * 0.55
                         + (cracks - 0.5) * 0.35;
            float heat = clamp(signal * 1.4 + 0.38, 0.0, 1.0);
            // Deeper pools (lowlands) burn hotter than lava draped over
            // ridges — matches real caldera / basin behaviour and ties the
            // thermal gradient to heightmap structure.
            heat *= mix(0.75, 1.15, clamp(1.0 - elev, 0.0, 1.0));
            // Cool the heat coordinate toward patch edges (low hotMask) so
            // partial-mix pixels always pick the deep-red end of the gradient
            // instead of bright yellow — eliminates the white/yellow halo
            // that the terrain↔lava blend used to produce under tonemap.
            heat *= hotMask;
            heat = clamp(heat, 0.0, 1.0);
            vec3 lavaCool = vec3(0.55, 0.020, 0.005);
            vec3 lavaMid  = vec3(1.00, 0.26,  0.015);
            vec3 lavaHot  = vec3(1.00, 0.78,  0.08);
            vec3 lava = (heat < 0.55)
                ? mix(lavaCool, lavaMid, smoothstep(0.0, 0.55, heat))
                : mix(lavaMid,  lavaHot, smoothstep(0.55, 1.0, heat));
            if (uMoltenSurface == 1) {
                // True lava world (surface T > 900 K): the whole patch is
                // exposed molten material, full lava colour across the
                // hotspot. hotMask used directly so every rendered pixel
                // is at full lava colour with no edge attenuation.
                color = mix(color, lava, hotMask);
            } else {
                // Volcanic world with a cool ambient surface — the lava
                // skinned over and froze almost immediately, leaving
                // only a thin glowing rim where molten material still
                // breaches the crust at the edge of the patch. The rim
                // band is the smoothstep transition zone of
                // `hotspotNoise + terrainBias` around `threshold`;
                // outside is unaffected, the deep interior is cooled
                // basalt, and only the narrow boundary is lava-coloured.
                // Cooled basalt blends a heavily-darkened version of the
                // underlying terrain colour with a near-black basalt
                // baseline, so the patch carries the regional palette
                // signature — a Mars-class iron-rich world's cooled lava
                // stays warm-dark, a sulfur world's stays yellow-dark,
                // a silicate world reads as charcoal-tan — instead of
                // reading as a universal black inclusion. Mix ratio
                // favours the basalt side enough that the patch still
                // identifies clearly as "burned" against the surround.
                vec3 cooledBasalt = mix(vec3(0.045, 0.040, 0.040), color * 0.40, 0.55);
                // Paint cooled basalt across the whole patch first.
                color = mix(color, cooledBasalt, hotMask);
                // Then overlay the rim. The rim mask is hotMask × (1 −
                // smoothstep that's 0 at the patch edge and 1 just past
                // it). Half-width 0.003 noise units — about 3 × the
                // bake's pixel quantisation so the rim is visible but
                // not perceptibly wider than a hairline. Any narrower
                // and the rim drops below the smoothstep's effective
                // resolution at the 1024 × 512 bake size; any wider
                // and it starts reading as a halo again.
                float rimMask = hotMask * (1.0 - smoothstep(
                    threshold + 0.04,
                    threshold + 0.043,
                    hotspotNoise + terrainBias));
                color = mix(color, lava, rimMask);
            }
        }
    }

    // ── Texture modulation (direct port of PoC's organic surface variation) ──
    // Brightness: surfTex modulates ±3–10% depending on elevation and blend region
    float baseTexI = 0.03 + pow(elev, 1.5) * 0.07;
    float texI     = baseTexI * (0.3 + blendI * 1.2);
    float texMod   = 1.0 + (surfTex - 0.5) * 2.0 * texI;

    // Hue: hueNoise drives a slight per-channel RGB shift (warm/cool variation)
    float hueZone  = hueNoise * hueNoise * (3.0 - 2.0 * hueNoise);  // smoothstep
    float hueI     = 0.03 * (0.2 + blendI * 1.5);
    float hDelta   = (hueZone - 0.5) * 2.0 * hueI;

    color = clamp(vec3(
        (color.r + hDelta * 0.9) * texMod,
        (color.g + hDelta * 1.1) * texMod,
        (color.b + hDelta * 0.6) * texMod
    ), 0.0, 1.0);

    // ── Carbon fraction: desaturate toward dark graphite ──
    if (uCarbonFraction > 0.05) {
        float grey = dot(color, vec3(0.299, 0.587, 0.114));
        color = mix(color, vec3(grey),               uCarbonFraction * 0.7);
        color = mix(color, vec3(0.18, 0.16, 0.14),  uCarbonFraction * 0.4);
    }

    // ── Tholin fraction: hue shift toward orange-brown (Titan-like) ──
    if (uTholinFraction > 0.05) {
        color = mix(color, vec3(0.55, 0.28, 0.08), uTholinFraction * 0.6);
    }

    // ── Polar caps ──
    if (uPolarCap > 0.0) {
        // Tidal lock: anti-stellar hemisphere freezes
        // Rotators: geographic poles
        // Tidal poleD is unclamped [-1,1] so sub-stellar points get negative
        // values that properly fall outside the ice transition zone.
        float poleD = uTidallyLocked
            ? -dot(P, uSubSolarDir)
            : abs(P.y);

        // Weather noise + altitude effect on cap boundary (PoC faithful)
        float wNoise  = fbm(C + vec3(0.0, 0.0, 99.0), 3, 0.5, scale * 2.0);
        float altEff  = (elev - 0.4) * 0.35;
        // Tidally locked: 2× multiplier so polarCapExtent maps linearly to
        // surface coverage fraction. Without this, 0.5 extent → only 25%
        // visible coverage because capThr sits in the cosine domain.
        float capBase = uTidallyLocked ? 2.0 * uPolarCap : uPolarCap;
        float capThr  = 1.0 - capBase - altEff - (wNoise * 0.1 - 0.05);

        // Double-smoothstep for crisp but natural cap edge (PoC: iceTransition = 0.04)
        float iceT = clamp((poleD - (capThr - 0.04)) / 0.08, 0.0, 1.0);
        iceT = iceT * iceT * (3.0 - 2.0 * iceT);
        iceT = iceT * iceT * (3.0 - 2.0 * iceT);

        float iceMod  = 1.0 + (roughTex - 0.5) * 0.08 + elev * 0.10;
        color    = mix(color, clamp(uColorPolar * iceMod, 0.0, 1.0), iceT);
        heightOut = mix(heightOut, heightOut + pow(iceT, 1.5) * 0.14, iceT);
    }

    // ── Output: gamma-encoded RGB + raw elevation in alpha ──
    // RGB encoded for planet.frag decode: pow(rgb, 2.2) in the atmosphere ray marcher.
    // Alpha: raw height [0,1], not gamma-encoded (scalar, not color).
    fragColor = vec4(pow(clamp(color, 0.0, 1.0), vec3(1.0 / 2.2)), clamp(heightOut, 0.0, 1.0));
}
