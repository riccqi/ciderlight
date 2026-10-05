kernel void reflection_checks(constant float4x4 &vp [[buffer(0)]], device float4 *out [[buffer(1)]],
                              depth2d<float> depth [[texture(0)]]) {
    float3 origin = float3(0, -2, -8);
    float3 direction = normalize(float3(0, 0.4, -1));
    ReflectionHit hit = trace_reflection(depth, vp, origin, direction);
    out[0] = float4(hit.uv, hit.confidence, hit.distance);
    out[1] = float4(reflection_eye(vp), 1.0);
    // Control: the former condition accepts a foreground overlap as a reflection.
    constexpr sampler nearest(filter::nearest, address::clamp_to_edge);
    float stride = 0.3;
    float3 p = origin;
    float legacy = 0;
    for (int i = 0; i < 24; i++) {
        p += direction * stride;
        stride *= 1.22;
        float4 clip = vp * float4(p, 1);
        float3 ndc = clip.xyz / clip.w;
        float2 uv = ndc.xy * 0.5 + 0.5;
        if (clip.w <= 0 || any(uv < 0) || any(uv > 1)) break;
        float d = depth.sample(nearest, uv);
        if (d > ndc.z && d > 0) { legacy = 1; break; }
    }
    out[2] = float4(legacy, 0, 0, 0);
}
