
// Numerical checks for the water surface helpers (wave normals, rain ripples, glint, absorption).
kernel void water_surface_checks(device float4 *out [[buffer(0)]], uint id [[thread_position_in_grid]]) {
    if (id != 0) return;
    // 0: near waves (mean |slope|, max |slope|, mean variance, finite flag)
    // 1: distant waves, same layout
    // 2: x mean |slope change| between two times, y mean |slope| with rain, z mean ripple slope near, w ripple far
    float4 near = float4(0.0, 0.0, 0.0, 1.0), far = float4(0.0, 0.0, 0.0, 1.0), misc = float4(0.0);
    const int count = 256;
    for (int i = 0; i < count; i++) {
        float2 p = float2(float(i % 16) * 0.73 + 4900.0, float(i / 16) * 0.61 + 5100.0);
        WaterWaves a = water_waves(p, 12.0, 0.01, 0.0);
        WaterWaves b = water_waves(p, 13.0, 0.01, 0.0);
        WaterWaves c = water_waves(p, 12.0, 4.0, 0.0);
        WaterWaves r = water_waves(p, 12.0, 0.01, 1.0);
        float2 ripple = water_rain_ripples(p, 12.0, 0.01), rippleFar = water_rain_ripples(p, 12.0, 0.5);
        if (!all(isfinite(a.slope)) || !isfinite(a.variance)) near.w = 0.0;
        if (!all(isfinite(c.slope)) || !isfinite(c.variance)) far.w = 0.0;
        near.x += length(a.slope) / count; near.y = max(near.y, length(a.slope)); near.z += a.variance / count;
        far.x += length(c.slope) / count; far.y = max(far.y, length(c.slope)); far.z += c.variance / count;
        misc.x += length(a.slope - b.slope) / count;
        misc.y += length(r.slope + ripple) / count;
        misc.z += length(ripple) / count;
        misc.w += length(rippleFar) / count;
    }
    out[0] = near; out[1] = far; out[2] = misc;
    // 3: GGX: mirror direction (x), off-peak (y), light below the surface (z), rougher peak (w)
    float3 n = float3(0.0, 1.0, 0.0), v = normalize(float3(0.0, 0.3, 1.0));
    float3 mirror = reflect(-v, n);
    out[3] = float4(water_ggx(n, v, mirror, 0.05, 0.02), water_ggx(n, v, normalize(mirror + float3(0.4, 0.0, 0.0)), 0.05, 0.02),
                    water_ggx(n, v, float3(0.0, -1.0, 0.0), 0.05, 0.02), water_ggx(n, v, mirror, 0.3, 0.02));
    // 4: absorption of plains water (#3F76E4), 5: of a neutral colour
    out[4] = float4(water_absorption(float3(0.247, 0.463, 0.894)), 0.0);
    out[5] = float4(water_absorption(float3(0.8, 0.8, 0.8)), 0.0);
}
