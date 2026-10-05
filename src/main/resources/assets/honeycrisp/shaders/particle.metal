// Honeycrisp opaque particle shaders (block and item debris, and the particle-atlas sprites drawn the same way).
// Vanilla multiplies particles by its own lightmap and the composite pass then shades them like mobs, which leaves
// debris much darker and duller than the block it came from. Here they are lit with terrain's light instead: sky light
// shaped by the time of day and shadowed by the sun or moon, warm torch light, and the cool ambient floor.
// Prepended by MetalShaders.java: IDX_TRANSFORMS, IDX_PROJECTION, IDX_ATLAS, IDX_LIGHTMAP.

#include <metal_stdlib>
using namespace metal;

#define IDX_FRAME 14
#define IDX_SHADOW 14
#define IDX_SHADOW_COLOR 11
#define IDX_CLOUD_SHADOW 8
#define IDX_FRAME_CONSTANTS 7

struct DynamicTransforms {
    float4x4 ModelViewMat;
    float4x4 TextureMat;
    float4 ColorModulator;
    packed_float3 ModelOffset;
};

struct Projection {
    float4x4 ProjMat;
};

#include "frame.metal"
#include "shadow.metal"

struct ParticleIn {
    float3 Position [[attribute(0)]];
    float2 UV0 [[attribute(1)]];
    float4 Color [[attribute(2)]];
    int2 UV2 [[attribute(3)]];
};

struct ParticleOut {
    float4 position [[position]];
    float2 uv0;
    float4 color;
    float2 light;     // block, sky lightmap coordinates (0..240)
    float3 worldPos;  // camera-relative
};

vertex ParticleOut particle_vertex(ParticleIn in [[stage_in]],
                                   constant DynamicTransforms &dyn [[buffer(IDX_TRANSFORMS)]],
                                   constant Projection &proj [[buffer(IDX_PROJECTION)]]) {
    ParticleOut out;
    out.position = proj.ProjMat * dyn.ModelViewMat * float4(in.Position, 1.0);
    out.position.y = -out.position.y; // match the backend's GL-style render target layout
    out.uv0 = in.UV0;
    out.color = in.Color;
    out.light = float2(in.UV2);
    // Particle vertices are already relative to the camera; the model-view matrix only turns them into view space.
    out.worldPos = in.Position;
    return out;
}

static float4 particle_lightmap(texture2d<float> lm, sampler s, float2 uv) {
    return lm.sample(s, clamp(uv / 256.0 + 0.5 / 16.0, float2(0.5 / 16.0), float2(15.5 / 16.0)));
}

fragment float4 particle_fragment(ParticleOut in [[stage_in]],
                                  constant DynamicTransforms &dyn [[buffer(IDX_TRANSFORMS)]],
                                  constant FrameData &frame [[buffer(IDX_FRAME)]],
                                  texture2d<float> atlas [[texture(IDX_ATLAS)]],
                                  sampler atlasSampler [[sampler(IDX_ATLAS)]],
                                  texture2d<float> lightTex [[texture(IDX_LIGHTMAP)]],
                                  sampler lightSampler [[sampler(IDX_LIGHTMAP)]],
                                  depth2d<float> shadowMap [[texture(IDX_SHADOW)]],
                                  sampler shadowSampler [[sampler(IDX_SHADOW)]],
                                  texture2d<float> shadowColor [[texture(IDX_SHADOW_COLOR)]],
                                  texture2d<float> cloudShadow [[texture(IDX_CLOUD_SHADOW)]],
                                  texture2d<float> frameConstants [[texture(IDX_FRAME_CONSTANTS)]]) {
    float4 albedo = atlas.sample(atlasSampler, in.uv0) * in.color * dyn.ColorModulator;
    if (albedo.a < 0.1) {
        discard_fragment();
    }
    // Full-bright particles (flames, lava drips, glowing sprites) give off their own light, as in vanilla.
    if (in.light.x >= 239.5 && in.light.y >= 239.5) {
        return albedo;
    }

    // The same light terrain.metal builds from the two lightmap coordinates.
    float3 floorL = particle_lightmap(lightTex, lightSampler, float2(0.0)).rgb;
    float3 skyOnly = particle_lightmap(lightTex, lightSampler, float2(0.0, in.light.y)).rgb;
    float3 skyTerm = max(skyOnly - floorL, 0.0);
    float blockL = max(saturate(in.light.x / 240.0), held_light(frame, in.worldPos, normalize(-in.worldPos)));
    float skyL = saturate(in.light.y / 240.0);

    float lightStrength = frame.sunDir.w;
    float3 direct = float3(0.0);
    float3 shade = float3(1.0);
    if (lightStrength > 0.0 && in.light.y > 0.0) {
        // A speck of debris is small and tumbling: lit evenly whichever way it faces, like foliage, taking only
        // the sun or moon light that reaches its position.
        float3 lightDir = frame.sunDir.xyz;
        float3 vis = shadow_visibility(frame, shadowMap, shadowSampler, shadowColor, cloudShadow, in.worldPos, lightDir, 1.0);
        direct = 0.85 * vis;
        float darkness = frame.sunColor.w;
        float3 shadowTint = frameConstants.read(uint2(0, 0)).rgb * (1.0 - darkness);
        shade = mix(float3(1.0), filtered_surface_light(shadowTint, frame.sunColor.rgb, direct), lightStrength);
    }
    float3 sky = skyTerm * frame.lightParams.y * shade;
    float3 blockTerm = float3(1.0, 0.66, 0.30) * torch_light(blockL);
    float daylight = skyL * skyL * frame.lightParams.x;
    blockTerm *= 1.0 - 0.8 * daylight * (0.6 + 0.4 * dot(direct, float3(0.333)));
    float3 ambient = floorL * float3(1.0, 1.05, 1.2);
    float3 light = ambient + sky + blockTerm;
    if (frame.lightning.w > 0.0 && in.light.y > 0.0) {
        float3 towardsCamera = normalize(-in.worldPos);
        light += (lightning_light(frame, in.worldPos, towardsCamera) + lightning_sky_light(frame)) * smoothstep(0.2, 0.9, skyL);
    }
    // Alpha is replaced by the block-light marker pass (MetalShaders.markEntityLight): 0, already lit.
    return float4(albedo.rgb * light, albedo.a);
}
