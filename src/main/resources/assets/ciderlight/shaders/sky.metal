#include <metal_stdlib>
using namespace metal;
#include "frame.metal"
#include "atmosphere.metal"

struct SkyOut {
    float4 position [[position]];
    float2 uv;
};

vertex SkyOut sky_vertex(uint vid [[vertex_id]]) {
    float2 p = float2((vid << 1) & 2, vid & 2);
    return {float4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0), p};
}

fragment float4 sky_fragment(SkyOut in [[stage_in]], constant FrameData &frame [[buffer(0)]]) {
    float4 eye = frame.invViewProj * float4(0,0,1,0);
    float4 nearPoint = frame.invViewProj * float4(in.uv * 2.0 - 1.0, 1.0, 1.0);
    float3 direction = normalize(nearPoint.xyz / nearPoint.w - eye.xyz / eye.w);
    // A lightning flash lights the clouded sky from within.
    return float4(atmosphere_sky(frame, direction) + float3(0.20, 0.23, 0.32) * frame.lightning.w, 1.0);
}
