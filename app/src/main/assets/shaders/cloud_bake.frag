#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform float uCloudSize;
uniform float uCloudDistortion;
uniform float uCloudBanding;
uniform float uTime;

// ── Noise (duplicated from planet.frag — runs once per planet, not per frame) ──

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

// Full 5-octave FBM for bake pass (runs once, not per frame)
float fbm(vec3 p) {
    float f = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 5; i++) {
        f += amp * noise(p);
        p = m3 * p * 2.0;
        amp *= 0.5;
    }
    return f;
}

vec3 warpCloudCoord(vec3 p) {
    vec3 wp = p;
    float timeAngle = uTime * 0.02;
    float st = sin(timeAngle);
    float ct = cos(timeAngle);
    wp.xz = mat2(ct, -st, st, ct) * wp.xz;
    wp.y *= (1.0 + 0.15 * uCloudBanding);
    float twist = wp.y * 1.5 * uCloudBanding;
    float s = sin(twist);
    float c = cos(twist);
    wp.xz = mat2(c, -s, s, c) * wp.xz;
    wp += vec3(uTime * 0.015, uTime * 0.005, uTime * 0.01);
    if (uCloudDistortion > 0.0) {
        float dwX = fbm(wp * 2.5 + vec3(uTime * 0.01));
        float dwZ = fbm(wp * 2.5 + vec3(14.5, 2.3, 7.8) - vec3(uTime * 0.01));
        wp.x += (dwX - 0.5) * uCloudDistortion * 0.4;
        wp.z += (dwZ - 0.5) * uCloudDistortion * 0.4;
    }
    return wp;
}

void main() {
    // Equirectangular UV → sphere direction
    float phi = (1.0 - vUv.x) * 2.0 * 3.14159265 - 3.14159265;
    float lat = (vUv.y - 0.5) * 3.14159265;
    float cosLat = cos(lat);
    vec3 dir = vec3(cosLat * cos(phi), sin(lat), cosLat * sin(phi));

    float freq = 12.0 / uCloudSize;
    float n = fbm(warpCloudCoord(dir) * freq);
    fragColor = vec4(n, 0.0, 0.0, 1.0);
}
