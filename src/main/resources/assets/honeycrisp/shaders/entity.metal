// Honeycrisp entity shadow-caster shaders: replay Minecraft's entity/item draws from the sun.
// Bindings: buffer(0) DynamicTransforms, buffer(14) FrameData, texture(0)/sampler(0) atlas,
// vertex buffer slot 0 at buffer(16) (the pipeline's own vertex layout, only Position and UV0 are read).

#include <metal_stdlib>
using namespace metal;

struct DynamicTransforms {
    float4x4 ModelViewMat;
    float4x4 TextureMat;
    float4 ColorModulator;
    packed_float3 ModelOffset;
};

#include "frame.metal"

constant float OWN_BODY_DROP = 10000.0;

struct EntityIn {
    float3 Position [[attribute(0)]];
    float2 UV0 [[attribute(2)]];
};

struct ShadowOut {
    float4 position [[position]];
    float2 uv0;
};

vertex ShadowOut entity_shadow_vertex(EntityIn in [[stage_in]],
                                      constant DynamicTransforms &dyn [[buffer(0)]],
                                      constant FrameData &frame [[buffer(14)]]) {
    ShadowOut out;
    // The first-person player's own body is drawn this far below its real place to keep it out of the main pass
    // (MetalShaders.OWN_BODY_DROP); nothing else is ever that far under the camera. Positions are camera-relative and
    // world-aligned, so it is moved back before the model-view matrix: armor and elytra scale that matrix by 1 - 1/4096
    // (vanilla's view-offset layering), which would otherwise shrink the drop and lift them 2.4 blocks.
    float3 position = in.Position;
    if (position.y < -0.5 * OWN_BODY_DROP) {
        position.y += OWN_BODY_DROP;
    }
    float4 world = frame.invView * (dyn.ModelViewMat * float4(position, 1.0));
    world.xyz += frame.camToAnchor.xyz;
    out.position = frame.shadowMat * float4(world.xyz, 1.0);
    out.uv0 = in.UV0;
    return out;
}

fragment void entity_shadow_fragment(ShadowOut in [[stage_in]],
                                     texture2d<float> atlas [[texture(0)]],
                                     sampler atlasSampler [[sampler(0)]]) {
    if (atlas.sample(atlasSampler, in.uv0, level(0)).a < 0.1) {
        discard_fragment();
    }
}

// --- block-light marker pass ---
// Entities are lit after the fact in the composite pass from the depth buffer, which knows nothing about the
// torches around them. This pass redraws the captured entity/item draws over the finished scene, writing only the
// alpha channel: 0.5 + 0.45 * block light, read back by the composite so torch-lit mobs stay lit. Transparent texels
// are skipped as in vanilla's entity shaders, or the scene behind a mob's empty outer layer (a villager's hat and
// robe) would be marked and lit a second time.

struct LightIn {
    float3 Position [[attribute(0)]];
    float2 UV0 [[attribute(2)]];
    int2 UV2 [[attribute(4)]];
};

struct LightOut {
    float4 position [[position]];
    float2 uv0;
    float blockLight;
};

vertex LightOut entity_light_vertex(LightIn in [[stage_in]],
                                    constant DynamicTransforms &dyn [[buffer(0)]],
                                    constant FrameData &frame [[buffer(14)]]) {
    LightOut out;
    float4 world = frame.invView * (dyn.ModelViewMat * float4(in.Position, 1.0));
    out.position = frame.viewProj * float4(world.xyz, 1.0);
    out.position.y = -out.position.y; // match the backend's GL-style render target layout
    out.uv0 = in.UV0;
    out.blockLight = saturate(float(in.UV2.x) / 240.0);
    return out;
}

// Particles: vanilla's particle vertex format puts UV0 at location 1 and UV2 at location 3.
struct ParticleLightIn {
    float3 Position [[attribute(0)]];
    float2 UV0 [[attribute(1)]];
    int2 UV2 [[attribute(3)]];
};

vertex LightOut particle_light_vertex(ParticleLightIn in [[stage_in]],
                                      constant DynamicTransforms &dyn [[buffer(0)]],
                                      constant FrameData &frame [[buffer(14)]]) {
    LightOut out;
    float4 world = frame.invView * (dyn.ModelViewMat * float4(in.Position, 1.0));
    out.position = frame.viewProj * float4(world.xyz, 1.0);
    out.position.y = -out.position.y;
    out.uv0 = in.UV0;
    out.blockLight = saturate(float(in.UV2.x) / 240.0);
    return out;
}

fragment float4 entity_light_fragment(LightOut in [[stage_in]],
                                      texture2d<float> atlas [[texture(0)]],
                                      sampler atlasSampler [[sampler(0)]]) {
    if (atlas.sample(atlasSampler, in.uv0).a < 0.1) {
        discard_fragment();
    }
    return float4(0.0, 0.0, 0.0, encode_entity_light(in.blockLight));
}

// Where the flame particles' sprites (flame, soul and copper flame, lava) lie in the particle atlas: u0, v0, u1, v1.
// All zero for the block and item atlases that debris is drawn from.
struct FlameSprites {
    float4 rect[4];
};

static bool is_flame_sprite(constant FlameSprites &flames, float2 uv) {
    for (int i = 0; i < 4; i++) {
        float4 r = flames.rect[i];
        if (uv.x >= r.x && uv.y >= r.y && uv.x < r.z && uv.y < r.w) {
            return true;
        }
    }
    return false;
}

// Flame particles are light sources like the flame of the torch under them: marked so the composite leaves them
// unshaded at full brightness.
fragment float4 particle_light_fragment(LightOut in [[stage_in]],
                                        texture2d<float> atlas [[texture(0)]],
                                        sampler atlasSampler [[sampler(0)]],
                                        constant FlameSprites &flames [[buffer(0)]]) {
    if (atlas.sample(atlasSampler, in.uv0).a < 0.1) {
        discard_fragment();
    }
    return float4(0.0, 0.0, 0.0, is_flame_sprite(flames, in.uv0) ? ENTITY_EMISSIVE_ALPHA : encode_entity_light(in.blockLight));
}

// The same, for particles particle.metal has already lit like terrain: alpha 0 tells the composite pass to leave
// them alone; flames are still marked as light sources.
fragment float4 particle_lit_fragment(LightOut in [[stage_in]],
                                      texture2d<float> atlas [[texture(0)]],
                                      sampler atlasSampler [[sampler(0)]],
                                      constant FlameSprites &flames [[buffer(0)]]) {
    if (atlas.sample(atlasSampler, in.uv0).a < 0.1) {
        discard_fragment();
    }
    return float4(0.0, 0.0, 0.0, is_flame_sprite(flames, in.uv0) ? ENTITY_EMISSIVE_ALPHA : 0.0);
}

// --- cloud shadow casters ---
// Clouds have no vertex buffer: like vanilla's clouds.vsh, each quad's corners come from the vertex index and a
// buffer of packed cells (x, z, direction and flags), placed with CloudInfo's offset and cell size relative to the
// camera. Here they are projected from the sun into the shadow map instead of to the screen.

struct CloudInfo {
    float4 CloudColor;
    packed_float3 CloudOffset;
    float pad0;
    packed_float3 CellSize;
};

struct CloudShadowOut {
    float4 position [[position]];
};

static float4 cloud_light_position(uint vid, constant DynamicTransforms &dyn, constant CloudInfo &cloud,
                                   constant FrameData &frame, texture_buffer<int> faces) {
    int quadVertex = int(vid % 4);
    uint index = (vid / 4) * 3;
    int cellX = faces.read(index).r;
    int cellZ = faces.read(index + 1).r;
    int flags = faces.read(index + 2).r;
    int direction = flags & 7;
    bool inside = (flags & 16) != 0;
    cellX = (cellX << 1) | ((flags & 128) >> 7);
    cellZ = (cellZ << 1) | ((flags & 64) >> 6);
    int corner = direction * 4 + (inside ? 3 - quadVertex : quadVertex);
    float3 faceVertex = float3((0xF03CC3 >> corner) & 1, (0x6666F0 >> corner) & 1, (0xC3F066 >> corner) & 1);
    float3 cellSize = float3(cloud.CellSize);
    float3 pos = faceVertex * cellSize + float3(cellX, 0, cellZ) * cellSize + float3(cloud.CloudOffset);
    float4 world = frame.invView * (dyn.ModelViewMat * float4(pos, 1.0));
    return frame.shadowMat * float4(world.xyz + frame.camToAnchor.xyz, 1.0);
}

vertex CloudShadowOut cloud_shadow_vertex(uint vid [[vertex_id]],
                                          constant DynamicTransforms &dyn [[buffer(0)]],
                                          constant CloudInfo &cloud [[buffer(1)]],
                                          constant FrameData &frame [[buffer(14)]],
                                          texture_buffer<int> faces [[texture(0)]]) {
    CloudShadowOut out;
    out.position = cloud_light_position(vid, dyn, cloud, frame, faces);
    return out;
}

// Clouds are not solid. They go into the transmission map (rgb multiplied, alpha the nearest depth) as a grey
// filter. Light crosses two faces of a cloud, so the shadow lets about 0.62 of the light through.
constant float CLOUD_FACE_TRANSMISSION = 0.79;

fragment float4 cloud_shadow_fragment(CloudShadowOut in [[stage_in]]) {
    return float4(float3(CLOUD_FACE_TRANSMISSION), in.position.z);
}

// --- cloud shadow map ---
// For surfaces, clouds go into a small map of their own (shadow.metal's cloud_shadow), covering the near shadow map's
// area. Depth is flattened so clouds beyond the shadow map's depth range still count; the receiver's height against
// the cloud base decides whether it is under them.
vertex CloudShadowOut cloud_map_vertex(uint vid [[vertex_id]],
                                       constant DynamicTransforms &dyn [[buffer(0)]],
                                       constant CloudInfo &cloud [[buffer(1)]],
                                       constant FrameData &frame [[buffer(14)]],
                                       texture_buffer<int> faces [[texture(0)]]) {
    CloudShadowOut out;
    out.position = cloud_light_position(vid, dyn, cloud, frame, faces);
    out.position.z = 0.5 * out.position.w;
    return out;
}

fragment float4 cloud_map_fragment(CloudShadowOut in [[stage_in]]) {
    return float4(CLOUD_FACE_TRANSMISSION);
}
