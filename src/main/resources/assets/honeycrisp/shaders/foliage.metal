// Waving leaves and plants, shared by the terrain and shadow vertex shaders so shadows sway with the plants.
// Motion tuned after Complementary Shaders (amplitudes and speeds); the code is original.
//
// Which blocks wave is decided on the CPU (MetalShaders.updateFoliageMap) from block tags and classes: every
// sprite those blocks' models use is marked in a small grid over the block atlas, one byte per grid cell.
// The vertex shader finds its quad's sprite from the middle of the quad's UVs (a vertex's own UV sits on the
// sprite's border, shared with the neighbouring sprite): the diagonally opposite vertex of the quad is read
// straight from the vertex buffer. MC_VERTEX_STRIDE, MC_UV_OFFSET and MC_POS_OFFSET describe the chunk
// vertex layout; without them (an unexpected layout) nothing waves.

#define IDX_FOLIAGE 13
#define IDX_RAW_VERTICES 16 // the backend binds vertex buffer slot 0 at Metal buffer index 16

#define FOLIAGE_NONE 0
#define FOLIAGE_LEAVES 1      // whole block moves
#define FOLIAGE_PLANT 2       // anchored at the bottom: the higher a vertex sits in its quad, the more it moves
#define FOLIAGE_LOWER 3       // lower half of a two-block plant: as a plant
#define FOLIAGE_UPPER 4       // upper half: its bottom matches the lower half's top, its top swings a little further
#define FOLIAGE_HANGING 5     // vines, hanging roots, sugar cane: whole block, gently (they touch walls and stack)

// The sway is under a pixel at this distance (blocks) and fades out before it; vertices beyond skip the lookups.
constant float FOLIAGE_FADE_START = 64.0;
constant float FOLIAGE_FADE_END = 96.0;

// Sprite map layout: float2 cells per atlas UV, uint2 grid size, then one byte per cell (row-major): the low four
// bits are the foliage kind above, the high four the sprite's material (SPRITE_* in terrain.metal).
static int sprite_map_cell(const device uchar *map, float2 uv) {
    float2 scale = *(const device float2 *)map;
    uint2 grid = *(const device uint2 *)(map + 8);
    int2 cell = int2(floor(uv * scale));
    if (cell.x < 0 || cell.y < 0 || uint(cell.x) >= grid.x || uint(cell.y) >= grid.y) {
        return 0;
    }
    return int(map[16 + uint(cell.y) * grid.x + uint(cell.x)]);
}

static int foliage_kind(const device uchar *map, float2 uv) {
    return sprite_map_cell(map, uv) & 15;
}

// A smooth, world-anchored wind field: slow gusts roll across the world along a prevailing direction, a sway
// rides on them, and rain adds a faster flutter. Everything is a continuous function of position and time, so
// vertices shared between blocks (or between the halves of a tall plant) always move together.
static float3 wind_field(float3 p, float t, float rain) {
    const float2 windDir = float2(0.8, 0.6);
    const float2 crossDir = float2(-0.6, 0.8);
    float along = dot(p.xz, windDir);
    float across = dot(p.xz, crossDir);
    float gust = 0.6 + 0.4 * sin(t * 0.37 - along * 0.09) * sin(t * 0.23 + across * 0.05 + 1.7);
    float sway = 0.65 * sin(t * 1.9 - along * 0.55 + p.y * 0.35) + 0.35 * sin(t * 3.3 - along * 0.9 + across * 0.4 + p.y * 0.2);
    float side = sin(t * 2.6 + across * 0.8 + p.y * 0.5 + along * 0.2);
    float flutter = sin(t * 7.1 + p.x * 2.1 + p.z * 1.7 + p.y * 1.3) * rain;
    float3 w;
    w.xz = windDir * (sway * gust + flutter * 0.5) + crossDir * (side * 0.35 * gust + flutter * 0.25);
    w.y = 0.5 * sin(t * 2.3 + p.x * 0.7 + p.z * 0.9 + p.y * 0.4) * gust;
    return w * (1.0 + 0.7 * rain);
}

// Displacement of this vertex in blocks. chunkPos + localPos is its absolute world position, cameraDistance its
// distance from the camera; skyLight is UV2.y (0..240).
static float3 foliage_offset(int3 chunkPos, float3 localPos, float cameraDistance, float2 uv, float skyLight, uint vertexId,
                             uint baseVertex, const device uchar *raw, const device uchar *map, constant FrameData &frame) {
#if defined(MC_VERTEX_STRIDE) && defined(MC_UV_OFFSET) && defined(MC_POS_OFFSET)
    if (cameraDistance >= FOLIAGE_FADE_END) {
        return float3(0.0);
    }
    // Terrain is drawn as quads (indices 4q..4q+3 relative to the base vertex); vertex i's diagonal partner is i ^ 2.
    uint opposite = baseVertex + ((vertexId - baseVertex) ^ 2u);
    const device uchar *v = raw + opposite * MC_VERTEX_STRIDE;
    float2 oppositeUV = float2(*(const device packed_float2 *)(v + MC_UV_OFFSET));
    int kind = foliage_kind(map, (uv + oppositeUV) * 0.5);
    if (kind == FOLIAGE_NONE) {
        return float3(0.0);
    }
    float oppositeY = (*(const device packed_float3 *)(v + MC_POS_OFFSET)).y;
    // Height above the quad's lowest edge: 0 at the ground, 1 at the top of a cross-shaped plant. Flat quads
    // (petals, lily pads) stay put.
    float rise = saturate(localPos.y - oppositeY);
    float3 absPos = float3(chunkPos) + localPos;
    float3 w = wind_field(absPos, frame.params.x, frame.params.y);
    // No wind deep underground (lush caves); fades in over the first few sky light levels.
    float open = saturate(skyLight / 64.0);
    float3 offset;
    if (kind == FOLIAGE_LEAVES) {
        offset = float3(w.x * 0.04, w.y * 0.025, w.z * 0.04);
    } else if (kind == FOLIAGE_HANGING) {
        offset = float3(w.x * 0.025, w.y * 0.01, w.z * 0.025);
    } else {
        float weight = kind == FOLIAGE_UPPER ? 1.0 + 0.5 * rise : rise;
        offset = float3(w.x, 0.0, w.z) * (0.065 * weight);
    }
    return offset * (open * (1.0 - smoothstep(FOLIAGE_FADE_START, FOLIAGE_FADE_END, cameraDistance)));
#else
    return float3(0.0);
#endif
}

#if defined(MC_WAVING_DEBUG) && defined(MC_VERTEX_STRIDE)
// -Dhoneycrisp.wavingDebug=true: colour terrain by foliage kind (grey still, green leaves, yellow plants, red/blue
// lower/upper halves, magenta hanging).
static float3 foliage_debug_color(float2 uv, uint vertexId, uint baseVertex, const device uchar *raw, const device uchar *map) {
    uint opposite = baseVertex + ((vertexId - baseVertex) ^ 2u);
    float2 oppositeUV = float2(*(const device packed_float2 *)(raw + opposite * MC_VERTEX_STRIDE + MC_UV_OFFSET));
    const float3 colors[6] = {float3(0.2), float3(0, 1, 0), float3(1, 1, 0), float3(1, 0, 0), float3(0, 0, 1), float3(1, 0, 1)};
    return colors[clamp(foliage_kind(map, (uv + oppositeUV) * 0.5), 0, 5)];
}
#endif
