// Screen-space ground-truth ambient occlusion (GTAO). Included after frame.metal.
//
// Implemented from the paper: Jimenez, Wu, Pesce, Jarabo, "Practical Realtime Strategies for Accurate Indirect
// Occlusion" (SIGGRAPH 2016 course, Activision). For each pixel a few screen-space slices through the view vector are
// searched for the highest occluder ("horizon") on either side, within a world-space radius. The cosine-weighted
// visible arc between the two horizons, measured around the normal projected into the slice, is integrated
// analytically. Minecraft-specific choices: a radius in blocks, a linear distance falloff (instead of a thickness
// heuristic) so foreground silhouettes far in front of a surface cast no halo, and half-resolution evaluation with
// reprojected temporal accumulation (there is no TAA to resolve the per-pixel rotation noise).
// Written from the paper for Ciderlight; it contains no code from Photon or Bliss (whose licences do not permit reuse).
//
// Filtered texture (ao_filter_fragment): r = visibility (1 = open), a = camera distance of full-resolution pixel
// (2i+1, 2j+1) (0 = sky). See composite.metal for the passes.

constant int AO_SLICES = 2;        // slice directions per pixel per frame; the rotation changes every frame
constant int AO_STEPS = 4;         // horizon samples on each side of a slice
constant float AO_RADIUS = 2.0;    // world-space search radius, blocks
constant float AO_MAX_SCREEN = 0.12; // cap on the search radius as a fraction of the screen height, for close-ups

// Visible, cosine-weighted arc between the projected normal angle n and horizon angle h (paper eq. 10, one side).
static float gtao_arc(float h, float n) {
    return 0.25 * (-cos(2.0 * h - n) + cos(n) + 2.0 * h * sin(n));
}

// Visibility of one slice. h1 <= 0 <= h2 are the horizon angles from the view vector (negative side first); n is the
// signed angle of the normal projected into the slice. Horizons are clamped to the hemisphere around the normal.
static float gtao_slice(float n, float h1, float h2) {
    h1 = n + max(h1 - n, -M_PI_2_F);
    h2 = n + min(h2 - n, M_PI_2_F);
    return gtao_arc(h1, n) + gtao_arc(h2, n);
}

// gtao_slice with nothing in the way (horizons at the hemisphere edge): cos n + n sin n. Summed with the same weights,
// it normalises the estimate so an open surface is exactly 1 for any slice direction. This removes the variance a
// two-slice estimate otherwise has on tilted (almost all) surfaces, at the cost of a slight bias.
static float gtao_open(float n) {
    return cos(n) + n * sin(n);
}

static float3 ao_reconstruct(constant FrameData &frame, float2 uv, float depth) {
    float4 world = frame.invViewProj * float4(uv * 2.0 - 1.0, depth, 1.0);
    return world.xyz / world.w;
}

// Highest horizon (as a cosine against the view vector) along one direction of a slice. Samples must stay on the
// slice's screen line: a texel centre beside it would see the surface's own plane in another direction, which on
// grazing surfaces (screen edges) reads as a horizon above the tangent and greys open ground. Each texel's depth is
// therefore moved to the exact sample position with the centre surface's depth gradient (exact on that plane).
// Terrain marks the pixels of thin plants (grass, flowers, ferns: crossed quads) with this alpha. They are no
// occluders: a tuft of grass would otherwise put a dark blot on the ground around it.
constant float AO_THIN_PLANT_ALPHA = 0.47;
// Terrain alpha from 0 to this is the share of the pixel's light that ambient occlusion can remove (see composite.metal).
constant float TERRAIN_AO_ALPHA = 0.44;

// The AO texture is evaluated every `scale` pixels: texel i holds full-resolution pixel scale * i + scale / 2.
// Half resolution in a window; a third at about 1900 rows, where the pass would otherwise cost four times as much.
static float ao_scale(constant FrameData &frame) {
    return max(frame.aoParams.x, 2.0);
}

// Mobs, items and players (scene alpha 0.5 and up) occlude only within this reach. With the full radius a mob standing
// on open ground leaves a dark halo on every side of it, which at sunset hides its real, directional shadow.
constant float AO_ENTITY_RADIUS = 0.5;

// Search radius of the occluder at a pixel; 0 means it is no occluder at all.
struct AoNoMask {
    float radius(uint2 pixel) const { return AO_RADIUS; }
};

struct AoSceneMask {
    texture2d<float> scene;
    float radius(uint2 pixel) const {
        float a = scene.read(pixel).a;
        if (abs(a - AO_THIN_PLANT_ALPHA) < 0.012) return 0.0;
        return a >= 0.5 ? AO_ENTITY_RADIUS : AO_RADIUS;
    }
};

template <typename Mask>
static float gtao_horizon(constant FrameData &frame, depth2d<float> depth, float2 uv, float2 screenStep,
                          float3 P, float3 V, float jitter, float2 size, float2 depthGradient, Mask mask) {
    float best = -1.0;
    for (int i = 0; i < AO_STEPS; i++) {
        float2 suv = uv + screenStep * (float(i) + jitter);
        if (any(suv < 0.0) || any(suv > 1.0)) break;
        float2 px = min(suv * size, size - 1.0);
        float d = depth.read(uint2(px));
        if (d <= 0.0) continue; // sky
        d += dot(depthGradient, px - (floor(px) + 0.5));
        float3 w = ao_reconstruct(frame, suv, d) - P;
        float len = length(w);
        if (len < 1e-4) continue;
        float raw = dot(w, V) / len;
        // Occluders beyond the radius fade out entirely: no dark halo around a silhouette far in front.
        float c = mix(raw, -1.0, saturate(len / AO_RADIUS * 2.0 - 1.0));
        // Only a sample that would raise the horizon needs the (second texture) check of what kind of occluder it is.
        if (c > best) {
            float radius = mask.radius(uint2(px));
            if (radius < AO_RADIUS) c = radius > 0.0 ? mix(raw, -1.0, saturate(len / radius * 2.0 - 1.0)) : -1.0;
            best = max(best, c);
        }
    }
    return best;
}

// Face normal from the depth buffer, choosing the closer neighbour on each axis so silhouettes do not bend it.
// `gradient` receives the change of camera distance per pixel along x and y on that surface.
static float3 ao_normal(constant FrameData &frame, depth2d<float> depth, int2 pixel, float3 P, float2 size,
                        thread float2 &gradient, thread float2 &depthGradient) {
    int2 hi = int2(size) - 1;
    float3 n[4];
    float ds[4];
    float centre = depth.read(uint2(pixel));
    const int2 offsets[4] = {int2(1, 0), int2(-1, 0), int2(0, 1), int2(0, -1)};
    for (int i = 0; i < 4; i++) {
        int2 q = clamp(pixel + offsets[i], int2(0), hi);
        float d = depth.read(uint2(q));
        ds[i] = d - centre;
        n[i] = ao_reconstruct(frame, (float2(q) + 0.5) / size, max(d, 1e-6)) - P;
    }
    bool right = length_squared(n[0]) < length_squared(n[1]), up = length_squared(n[2]) < length_squared(n[3]);
    float3 dx = right ? n[0] : -n[1];
    float3 dy = up ? n[2] : -n[3];
    depthGradient = float2(right ? ds[0] : -ds[1], up ? ds[2] : -ds[3]);
    float dist = length(P);
    gradient = float2(length(P + dx) - dist, length(P + dy) - dist);
    return normalize(cross(dx, dy));
}

// GTAO visibility of the surface seen through full-resolution pixel `pixel`. `noise` in [0,1)^2 rotates the slices
// and offsets the horizon samples.
template <typename Mask>
static float gtao(constant FrameData &frame, depth2d<float> depth, int2 pixel, float2 noise, thread float2 &gradient, Mask mask) {
    gradient = float2(0.0);
    float2 size = float2(depth.get_width(), depth.get_height());
    float2 uv = (float2(pixel) + 0.5) / size;
    float d = depth.read(uint2(pixel));
    if (d <= 0.0) return 1.0;
    float3 P = ao_reconstruct(frame, uv, d);
    float4 eye = frame.invViewProj * float4(0.0, 0.0, 1.0, 0.0);
    float3 toEye = eye.xyz / eye.w - P;
    // Projected size of the radius (vertical focal length from the projection): skip what is too far to matter.
    float focal = length(float3(frame.viewProj[0][1], frame.viewProj[1][1], frame.viewProj[2][1]));
    if (AO_RADIUS * focal * size.y * 0.5 < 1.5 * length(toEye)) return 1.0;
    float3 V = normalize(toEye);
    float2 depthGradient;
    float3 N = ao_normal(frame, depth, pixel, P, size, gradient, depthGradient);
    if (dot(N, V) < 0.0) N = -N;

    float sum = 0.0, weight = 0.0;
    for (int s = 0; s < AO_SLICES; s++) {
        float phi = (float(s) + noise.x) * (M_PI_F / float(AO_SLICES));
        float2 dir = float2(cos(phi), sin(phi));
        // Any screen line through the pixel back-projects to a plane through the eye that contains V. Points on that
        // line at the same depth give the slice's in-plane direction; keep only its part perpendicular to V.
        float3 along = ao_reconstruct(frame, uv + dir * (4.0 / size.y), d) - P;
        float3 ortho = along - V * dot(along, V);
        if (length_squared(ortho) < 1e-12) continue;
        ortho = normalize(ortho);
        // Screen extent of the world-space radius along this direction.
        float4 clip = frame.viewProj * float4(P + ortho * AO_RADIUS, 1.0);
        float limit = AO_MAX_SCREEN * size.y;
        // Close to the camera the end of the radius can lie behind the eye plane. Its projection is then mirrored,
        // which would swap the two sides of the slice and darken flat surfaces. It is far off screen in the slice's
        // own direction, so the capped reach along that direction is used.
        float2 reach = dir * (limit / length(dir * size));
        if (clip.w > 1e-3) {
            float2 projectedReach = clip.xy / clip.w * 0.5 + 0.5 - uv;
            float reachPixels = length(projectedReach * size);
            if (reachPixels < 2.0) continue; // too far away to matter
            if (reachPixels <= limit) reach = projectedReach;
        }
        float2 step = reach / float(AO_STEPS);
        // Start at least one pixel out so the centre surface never occludes itself.
        float jitter = max(noise.y, 1.0 / max(length(step * size), 1.0));
        float c2 = gtao_horizon(frame, depth, uv, step, P, V, jitter, size, depthGradient, mask);
        float c1 = gtao_horizon(frame, depth, uv, -step, P, V, jitter, size, depthGradient, mask);

        float3 axis = cross(ortho, V);
        float3 projected = N - axis * dot(N, axis);
        float projectedLength = length(projected);
        if (projectedLength < 1e-4) continue;
        float cosN = saturate(dot(projected, V) / projectedLength);
        float n = sign(dot(projected, ortho)) * acos(cosN);
        float h1 = -acos(clamp(c1, -1.0, 1.0));
        float h2 = acos(clamp(c2, -1.0, 1.0));
        sum += gtao_slice(n, h1, h2) * projectedLength;
        weight += gtao_open(n) * projectedLength;
    }
    return weight > 0.0 ? saturate(sum / weight) : 1.0;
}

static float gtao(constant FrameData &frame, depth2d<float> depth, int2 pixel, float2 noise, thread float2 &gradient) {
    return gtao(frame, depth, pixel, noise, gradient, AoNoMask{});
}

// Depth-validated bilinear lookup of the AO texture at `uv`, for a surface at camera distance `expected` whose
// distance changes by `gradient` per full-resolution pixel. Each AO texel holds the full-resolution pixel
// scale * i + scale / 2 (see ao_scale), i.e. texel coordinate i + 0.5 + 0.5 / scale. The receiver's plane predicts the distance each neighbour must have, so grazing
// surfaces keep all their neighbours while other surfaces behind or in front are dropped.
// Returns (visibility, 1), or (1, 0) when no neighbour belongs to this surface.
static float2 ao_lookup(texture2d<float> ao, float2 uv, float expected, float2 gradient, float scale = 2.0) {
    constexpr sampler nearest(address::clamp_to_edge, filter::nearest);
    float2 size = float2(ao.get_width(), ao.get_height());
    float2 p = uv * size - (0.5 + 0.5 / scale);
    float2 base = floor(p);
    float2 f = p - base;
    // Two gathers fetch the 2x2 footprint base..base+1, ordered (0,1), (1,1), (1,0), (0,0).
    float2 corner = (base + 1.0) / size;
    float4 values = ao.gather(nearest, corner, int2(0), component::x);
    float4 distances = ao.gather(nearest, corner, int2(0), component::w);
    float4 dx = float4(0.0, 1.0, 1.0, 0.0) - f.x;
    float4 dy = float4(1.0, 1.0, 0.0, 0.0) - f.y;
    float4 predicted = expected + (gradient.x * dx + gradient.y * dy) * scale;
    float tolerance = max(0.05, expected * 0.004) + 0.5 * length(gradient);
    float4 weights = (1.0 - abs(dx)) * (1.0 - abs(dy)) + 1e-3;
    weights *= select(float4(0.0), float4(1.0), distances > 0.0 && abs(distances - predicted) <= tolerance);
    float total = dot(weights, 1.0);
    return total > 0.0 ? float2(dot(values, weights) / total, 1.0) : float2(1.0, 0.0);
}

// Last frame's AO for a camera-relative world position: the history that ao_filter_fragment blends with.
static float2 ao_reprojected(constant FrameData &frame, texture2d<float> ao, float3 worldPos, float2 gradient) {
    if (frame.fogParams.w < 1.5) return float2(1.0, 0.0);
    float3 prevRel = worldPos + frame.cameraDelta.xyz;
    float4 clip = frame.prevViewProj * float4(prevRel, 1.0);
    if (clip.w <= 0.0) return float2(1.0, 0.0);
    float2 uv = clip.xy / clip.w * 0.5 + 0.5;
    if (any(uv <= 0.0) || any(uv >= 1.0)) return float2(1.0, 0.0);
    return ao_lookup(ao, uv, length(prevRel), gradient, ao_scale(frame));
}

// How strongly the GTAO term is applied on top of vanilla's baked smooth-lighting AO. The curve deepens contact
// creases a little more than open slopes.
constant float AO_STRENGTH = 0.6;
// Around sunrise and sunset the light is low and directional, and the creases it leaves in shade read much deeper.
constant float AO_STRENGTH_GOLDEN = 0.95;

static float ao_apply(float visibility, float golden = 0.0) {
    return mix(1.0, visibility * (0.5 + 0.5 * visibility), mix(AO_STRENGTH, AO_STRENGTH_GOLDEN, golden));
}
