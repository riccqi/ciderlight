// Original Metal implementation of single scattering and Beer-Lambert extinction.
// Bliss / Chocapic13's underwater appearance is the visual reference; no pack source is embedded.
static float3 water_extinction(constant FrameData &frame) {
    return float3(0.18, 0.075, 0.045) * max(frame.waterParams.w, 0.1);
}

static float3 water_light_direction(float3 light) {
    // Snell's law, air -> water (index 1.333). Return the direction towards the entry point.
    float2 horizontal = light.xz / 1.333;
    return float3(horizontal.x, sqrt(max(1.0 - dot(horizontal, horizontal), 0.0)), horizontal.y);
}

static float water_ray_length(float3 origin, float3 direction, float distance, float surface) {
    // Stop at the water/air boundary when looking up, including projection-space view bob.
    float length = max(distance, 0.0);
    if (direction.y > 0.0001) length = min(length, max(surface - origin.y, 0.0) / direction.y);
    return length;
}

static float3 water_interval(float3 extinction, float start, float width) {
    // Exact integral of view-path attenuation across a complete interval (including its final tail).
    return exp(-extinction * start) * (1.0 - exp(-extinction * width)) / extinction;
}

static float water_phase(float cosine) {
    const float g = 0.65;
    return 0.2 + 0.12 * (1.0 - g * g) / pow(max(1.0 + g * g - 2.0 * g * cosine, 0.01), 1.5);
}

static float water_caustics(float2 surfacePosition, float time, float depth) {
    // Intersecting wave ridges projected from the entry point. All inputs are world anchored;
    // only time moves the waves. A ridge is a soft core of light inside a wider, fainter glow, not a crisp line
    // (about as much light as a thin bright one would bring, spread out); deep water widens and fades the pattern.
    float2 p = surfacePosition;
    float a = sin(dot(p, float2(1.7, 0.8)) + time * 0.63 + sin(p.y * 0.7 - time * 0.31));
    float b = sin(dot(p, float2(-0.9, 1.9)) - time * 0.51 + sin(p.x * 0.6 + time * 0.27));
    float deep = max(depth, 0.0);
    float across = (a * 0.6 + b * 0.4) / mix(0.09, 0.22, saturate(deep / 12.0));
    float ridge = 0.65 * exp(-across * across) + 0.35 * exp(-across * across / 9.0);
    return 1.0 + (ridge * 1.9 - 0.36) * exp(-deep * 0.05);
}
