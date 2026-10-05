// Shared CPU/GPU layout. Offsets and size are defined in MetalShaders.java.
struct FrameData {
    float4x4 shadowMat;   // anchor-relative world -> shadow clip space
    float4 camToAnchor;   // camera position minus shadow anchor
    float4 sunDir;        // world-space direction to the sun, w = sun strength
    float4 sunColor;      // rgb sunlight tint, w = shadow darkness
    float4 params;        // x time, y rain, z shadow texel size, w shadows valid
    float4x4 invView;
    float4x4 invViewProj;
    float4x4 viewProj;
    float4 skyColor;      // vanilla fog colour
    float4 cameraPos;     // w: height of the cloud base above the camera (cloud shadows fall only on what is below it)
    float4 lightParams;   // x day factor, y night sky darkening, z moon is the shadow light, w moon phase brightness
    float4 fogParams;     // x sky light at the camera, y render distance, z haze density, w ambient occlusion: 0 off, 1 on, 2 on with valid history
    float4x4 prevViewProj;
    float4 temporal;      // x frame index, y fog history valid, z shadow history valid, w shadow history weight
    float4 cameraDelta;
    float4 aoParams;      // x: how many pixels apart the ambient-occlusion texels are (2 when unset)
    float4 airNear;       // extra mist around the camera: x how much denser the air is at the camera, y the distance (blocks) over which that fades; z golden hour (1 with the sun at the horizon, 0 high up or at night)
    float4 lightning;     // xyz: the nearest lightning bolt relative to the camera, w: how brightly it lights the world
    float4 lightningEye;  // xyz: the camera relative to the point the bolt's shadow map is seen from, w: that map is valid
    float4x4 lightningShadowMat; // world relative to that point -> the bolt's shadow map (perspective)
    int4 rainOrigin;      // xy: world x, z of the rain mask's corner, z: whether it rains at the camera (used off the mask)
    uint4 rainMask[32];   // RAIN_GRID x RAIN_GRID bits, one per 4x4-block column: 1 where the biome there gets rain
    float4 heldLights[8]; // light sources in players' hands, nearest first: xyz relative to the camera, w block-light level (0-1); the list ends at the first 0
    float4 surfaceSun;     // rgb direct sun (or moon) light on surfaces, before shadows and the light's strength
    float4 surfaceAmbient; // rgb sky light on surfaces in shade (blue by day), before sky-light falloff
    float4 reserved[22];  // unused; keeps the offsets of the fields below
    float4 waterParams;   // x underwater, y local surface relative to camera, z surface sky light, w extinction scale
    float4x4 farShadowMat;
    float4 farCamToAnchor; // xyz camera minus far-map anchor, w valid
    float4 airParams;     // x fog range, y mist base height, z atmosphere enabled, w temporal weight
    float4 solarDir;      // actual sun (never swapped for moon), w atmosphere enabled
};

// Vanilla's fixed brightness per face direction (CardinalLighting.DEFAULT), multiplied into terrain vertex colours:
// 1 facing up, 0.8 north/south, 0.6 east/west, 0.5 down. 1 for faces that are not axis-aligned (vanilla shades
// those crossed plants as if facing up).
static float vanilla_face_shade(float3 n) {
    float3 a = abs(n);
    if (a.y > 0.98) return n.y > 0.0 ? 1.0 : 0.5;
    if (a.z > 0.98) return 0.8;
    if (a.x > 0.98) return 0.6;
    return 1.0;
}

// How much of the open sky's light reaches a face: all of it facing up, about three quarters on walls, half facing
// down. Applied to sky, block and cave light, not to the sun, which has its own direction.
static float sky_face_shade(float3 n) {
    return (0.775 + 0.225 * n.y) * (1.0 + 0.04 * abs(n.z) - 0.04 * abs(n.x));
}

// Lit colours above 1 (sunlit snow, sand, pale stone) would clip flat in the 8-bit scene: below KNEE they are kept,
// above it they approach 1 smoothly, with the same slope at the knee.
static float3 highlight_rolloff(float3 c) {
    const float knee = 0.68;
    float3 over = max(c - knee, 0.0);
    return min(c, knee) + (1.0 - knee) * (1.0 - exp(-over / (1.0 - knee)));
}

// Alpha 1 belongs to vanilla geometry with unknown light. Leave a gap that survives RGBA8 quantization.
static float encode_entity_light(float blockLight) {
    return 0.5 + 0.45 * saturate(blockLight);
}

// Between the block-light range and 1: a flame particle, which is a light source itself (see entity_emissive).
constant float ENTITY_EMISSIVE_ALPHA = 0.975;
// Glowing eyes (endermen, spiders, phantoms): unshaded like flames, but kept at their own colour, not brightened.
// Two 8-bit steps above the flames' mark (the scene's alpha is 8 bits).
constant float ENTITY_GLOW_ALPHA = 0.985;

// Strength of torch/block light at a vanilla block-light level (0-1): a warm falloff with a hot core next to the
// source. Shared by terrain and the entity composite so mobs are lit like the ground around them.
static float torch_light(float blockL) {
    return pow(blockL, 1.8) * (0.85 + 0.45 * pow(blockL, 6.0)) * 1.6;
}

// Block light (0-1) reaching a surface at `worldPos` (camera-relative) facing `normal` from the light sources players
// hold: like placed light it loses a level per block, but smoothly and without being stopped by walls.
constant int HELD_LIGHTS = 8;
static float held_light(constant FrameData &frame, float3 worldPos, float3 normal) {
    float light = 0.0;
    for (int i = 0; i < HELD_LIGHTS && frame.heldLights[i].w > 0.0; i++) {
        float3 toLight = frame.heldLights[i].xyz - worldPos;
        float dist = length(toLight);
        // Surfaces turned away from the light keep most of it, as block light has no direction.
        float facing = mix(0.6, 1.0, saturate(dot(normal, toLight) / max(dist, 1e-3) * 2.0 + 0.5));
        light = max(light, saturate(frame.heldLights[i].w - dist / 15.0) * facing);
    }
    return light;
}

// Light from a lightning bolt on a surface at `worldPos` (camera-relative) facing `normal`: a tall, blue-white column
// of light, so only the horizontal distance counts, and it reaches surfaces from above and from the side.
static float3 lightning_light(constant FrameData &frame, float3 worldPos, float3 normal) {
    float2 away = frame.lightning.xz - worldPos.xz;
    float distance2 = dot(away, away);
    float3 toBolt = normalize(float3(away.x, 0.6 * sqrt(distance2) + 4.0, away.y));
    float facing = 0.3 + 0.7 * saturate(dot(normal, toBolt));
    // A bolt lights the land for a long way around: half as bright 160 blocks away.
    return float3(0.78, 0.84, 1.0) * (frame.lightning.w * facing * 1.7 / (1.0 + distance2 / (160.0 * 160.0)));
}

// What the flash leaves in the bolt's own shadows: a little light scattered back by the sky and the rain.
static float3 lightning_sky_light(constant FrameData &frame) {
    return float3(0.70, 0.78, 1.0) * (frame.lightning.w * 0.12);
}

// How much rain falls on the column at world `xz` (absolute): 1 in biomes that get rain, 0 in dry and snowy ones
// (deserts, savannas, the badlands, the cold biomes). Blended between neighbouring 4x4 cells, so biome borders fade.
constant int RAIN_GRID = 64;
static float rain_exposure(constant FrameData &frame, float2 xz) {
    float2 p = (xz - float2(frame.rainOrigin.xy)) / 4.0 - 0.5;
    float2 base = floor(p), f = p - base;
    float corner[4];
    for (int k = 0; k < 4; k++) {
        int2 cell = int2(base) + int2(k & 1, k >> 1);
        if (any(cell < 0) || any(cell >= RAIN_GRID)) {
            corner[k] = float(frame.rainOrigin.z);
            continue;
        }
        uint bit = uint(cell.y * RAIN_GRID + cell.x);
        uint word = frame.rainMask[bit >> 7][(bit >> 5) & 3u];
        corner[k] = float((word >> (bit & 31u)) & 1u);
    }
    return mix(mix(corner[0], corner[1], f.x), mix(corner[2], corner[3], f.x), f.y);
}

static float decode_entity_light(float alpha) {
    return alpha < 0.995 ? saturate((alpha - 0.5) / 0.45) : 0.0;
}

static bool entity_emissive(float alpha) {
    return alpha > 0.9625 && alpha < 0.995;
}

// A flame (ENTITY_EMISSIVE_ALPHA) rather than glowing eyes (ENTITY_GLOW_ALPHA); both are entity_emissive.
static bool entity_flame(float alpha) {
    return alpha > 0.9625 && alpha < 0.98;
}
