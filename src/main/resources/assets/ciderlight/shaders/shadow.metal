// Shared surface shadow sampling and history. Included after frame.metal.
constant float2 SHADOW_TAPS[12] = {
    float2(-0.326, -0.406), float2(-0.840, -0.074), float2(-0.696, 0.457), float2(-0.203, 0.621),
    float2(0.962, -0.195), float2(0.473, -0.480), float2(0.519, 0.767), float2(0.185, -0.893),
    float2(0.507, 0.064), float2(0.896, 0.412), float2(-0.322, -0.933), float2(-0.792, -0.598)
};
constant float SHADOW_RADIUS_TEXELS = 2.2;

// Texture alpha describes how visible a glass face is, not how white its transmitted light is.
// Saturated dyes filter strongly even on visually transparent glass; neutral glass and water stay gentle.
static float3 material_transmission(float4 albedo, float3 vertexColor, bool water) {
    // Terrain vertex RGB also contains vanilla face shading/AO. That is not absorption by the glass.
    float vertexPeak = max(vertexColor.r, max(vertexColor.g, vertexColor.b));
    float3 tint = saturate(albedo.rgb * (water ? vertexColor : vertexColor / max(vertexPeak, 1e-5)));
    if (water) return mix(float3(1.0), tint, albedo.a * 0.4);
    float peak = max(tint.r, max(tint.g, tint.b));
    float spread = peak - min(tint.r, min(tint.g, tint.b));
    float dye = smoothstep(0.06, 0.25, spread) * smoothstep(0.10, 0.30, albedo.a);
    float3 neutral = mix(float3(1.0), tint, albedo.a * 0.8);
    // A saturated dye mainly removes complementary wavelengths; its display brightness must not
    // extinguish the surviving colour as well. This is an artistic RGB approximation of a filter.
    float3 coloured = float3(0.03) + 0.94 * tint / max(peak, 1e-5);
    return mix(neutral, coloured, dye);
}

// Translucent casters further than `farGap` (in shadow depth) in front of the receiver are left out.
static float3 shadow_transmission(texture2d<float> colorMap, float2 uv, float receiverDepth, float farGap = 2.0) {
    // Filter the depth-tested colours, never the blocker depths themselves: an interpolated depth would
    // spread colour onto air in front of glass at silhouettes. Alpha is the nearest translucent depth.
    constexpr sampler nearest(address::clamp_to_edge, filter::nearest);
    float4 depths = colorMap.gather(nearest, uv, int2(0), component::w);
    depths = select(depths, float4(2.0), receiverDepth - depths > farGap);
    if (all(receiverDepth <= depths)) {
        return float3(1.0);
    }
    if (all(receiverDepth > depths)) {
        constexpr sampler linear(address::clamp_to_edge, filter::linear);
        return colorMap.sample(linear, uv).rgb;
    }
    float2 p = uv * float2(colorMap.get_width(), colorMap.get_height()) - 0.5;
    int2 base = int2(floor(p));
    float2 f = fract(p);
    int2 hi = int2(colorMap.get_width(), colorMap.get_height()) - 1;
    float3 result = 0.0;
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            float4 t = colorMap.read(uint2(clamp(base + int2(x, y), int2(0), hi)));
            float weight = (x == 0 ? 1.0 - f.x : f.x) * (y == 0 ? 1.0 - f.y : f.y);
            result += (receiverDepth > t.a && receiverDepth - t.a <= farGap ? t.rgb : float3(1.0)) * weight;
        }
    }
    return result;
}

// Clouds dim the light like a grey filter. They are high above the receiver, so their shadow has a wide penumbra:
// they are drawn into a small map of their own (same area as the shadow map) and averaged over a disc about three
// blocks across. `height` is the receiver's height above the camera; nothing at or above the cloud base is shaded.
constant float CLOUD_SHADOW_RADIUS_TEXELS = 28.0; // in shadow-map texels

static float cloud_shadow(constant FrameData &frame, texture2d<float> cloudMap, float2 uv, float height) {
    if (height >= frame.cameraPos.w) {
        return 1.0;
    }
    constexpr sampler linear(address::clamp_to_edge, filter::linear);
    float sum = 0.0;
    for (int i = 0; i < 8; i++) {
        sum += cloudMap.sample(linear, uv + SHADOW_TAPS[i] * frame.params.z * CLOUD_SHADOW_RADIUS_TEXELS, level(0)).r;
    }
    return sum / 8.0;
}

// One tap of the cloud map at a camera-relative point, for volumes (fog, water) that integrate many points.
static float cloud_shadow_point(constant FrameData &frame, texture2d<float> cloudMap, float2 uv, float height) {
    constexpr sampler linear(address::clamp_to_edge, filter::linear);
    return height >= frame.cameraPos.w ? 1.0 : cloudMap.sample(linear, uv, level(0)).r;
}

static float3 shadow_visibility(constant FrameData &frame, depth2d<float> shadowMap, sampler shadowSampler,
                                texture2d<float> shadowColor, texture2d<float> cloudMap, float3 worldPos, float3 normal, float ndotl) {
    if (frame.params.w < 0.5) {
        return float3(1.0);
    }
    float dist = length(worldPos);
    float3 p = worldPos + normal * (0.04 + 0.002 * dist) * (1.5 - ndotl) + frame.camToAnchor.xyz;
    float4 sp = frame.shadowMat * float4(p, 1.0);
    float2 uv = float2(sp.x * 0.5 + 0.5, 0.5 - sp.y * 0.5);
    if (any(uv < 0.0) || any(uv > 1.0) || sp.z < 0.0 || sp.z >= 1.0) {
        return float3(1.0);
    }
    float z = sp.z - 0.0005;
    float2 radius = float2(frame.params.z * SHADOW_RADIUS_TEXELS);
    // Four taps spread around the disc first: where they agree the surface is fully lit or fully shadowed (most of
    // the screen) and the other eight would only repeat them. Penumbrae get the whole disc.
    float sum = shadowMap.sample_compare(shadowSampler, uv + SHADOW_TAPS[1] * radius, z)
              + shadowMap.sample_compare(shadowSampler, uv + SHADOW_TAPS[4] * radius, z)
              + shadowMap.sample_compare(shadowSampler, uv + SHADOW_TAPS[6] * radius, z)
              + shadowMap.sample_compare(shadowSampler, uv + SHADOW_TAPS[10] * radius, z);
    float visibility = sum * 0.25;
    if (sum > 0.0 && sum < 4.0) {
        for (int i = 0; i < 12; i++) {
            if (i != 1 && i != 4 && i != 6 && i != 10) {
                sum += shadowMap.sample_compare(shadowSampler, uv + SHADOW_TAPS[i] * radius, z);
            }
        }
        visibility = sum / 12.0;
    }
    // Fully shadowed: no glass or cloud tint to look up.
    float3 transmission = visibility > 0.0 ? shadow_transmission(shadowColor, uv, z) * cloud_shadow(frame, cloudMap, uv, worldPos.y) : float3(1.0);
    float edge = max(abs(sp.x), abs(sp.y));
    return mix(visibility * transmission, float3(1.0), smoothstep(0.85, 1.0, edge));
}

// How much of a lightning bolt's light reaches a surface: the bolt has a shadow map of its own, a perspective view
// from a point up the bolt towards the camera's surroundings, rendered while a bolt is out. Outside that view the
// surface counts as lit.
static float lightning_shadow(constant FrameData &frame, depth2d<float> map, sampler compare, float3 worldPos, float3 normal) {
    if (frame.lightningEye.w < 0.5) {
        return 1.0;
    }
    float3 p = worldPos + frame.lightningEye.xyz;
    p += normal * (0.12 + 0.006 * length(p));
    float4 sp = frame.lightningShadowMat * float4(p, 1.0);
    if (sp.w <= 0.5) {
        return 1.0;
    }
    float3 ndc = sp.xyz / sp.w;
    float2 uv = float2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
    if (any(uv < 0.0) || any(uv > 1.0) || ndc.z >= 1.0) {
        return 1.0;
    }
    // Perspective depth is finest near the bolt: a quarter of a block of slack at this distance.
    float z = ndc.z - 0.25 / (sp.w * sp.w);
    float2 texel = 0.75 / float2(map.get_width(), map.get_height());
    return 0.25 * (map.sample_compare(compare, uv + texel * float2(-1.0, -1.0), z) + map.sample_compare(compare, uv + texel * float2(1.0, -1.0), z)
                 + map.sample_compare(compare, uv + texel * float2(-1.0, 1.0), z) + map.sample_compare(compare, uv + texel * float2(1.0, 1.0), z));
}

// gradient: change in camera distance per history texel along x and y. The receiver's plane predicts the distance each
// neighbour must have, so the ground at a grazing angle keeps its neighbours while the camera moves. Without it they
// fail the distance test whenever the reprojected point falls between texels, and the shadow flickers between its
// filtered history and the raw jittered sample.
static float3 shadow_temporal(constant FrameData &frame, texture2d<float> history, float3 worldPos, float3 current,
                              float2 gradient = float2(0.0)) {
    if (frame.temporal.z < 0.5) {
        return current;
    }
    float3 prevRel = worldPos + frame.cameraDelta.xyz;
    float4 clip = frame.prevViewProj * float4(prevRel, 1.0);
    if (clip.w <= 0.0) {
        return current;
    }
    float2 uv = clip.xy / clip.w * 0.5 + 0.5;
    if (any(uv <= 0.0) || any(uv >= 1.0)) {
        return current;
    }
    float2 p = uv * float2(history.get_width(), history.get_height()) - 0.5;
    int2 base = int2(floor(p));
    float2 f = fract(p);
    float expectedDistance = length(prevRel);
    float tolerance = max(0.04, expectedDistance * 0.002) + 0.5 * length(gradient);
    int2 hi = int2(history.get_width(), history.get_height()) - 1;
    float3 sum = 0.0;
    float total = 0.0;
    // Validate each neighbour before interpolation, so foreground history cannot bleed onto a newly exposed wall.
    for (int y = 0; y < 2; y++) {
        for (int x = 0; x < 2; x++) {
            int2 pixel = base + int2(x, y);
            if (any(pixel < 0) || any(pixel > hi)) {
                continue;
            }
            float4 h = history.read(uint2(pixel));
            float predicted = expectedDistance + gradient.x * (float(x) - f.x) + gradient.y * (float(y) - f.y);
            if (h.a <= 0.0 || abs(h.a - predicted) > tolerance) {
                continue;
            }
            float weight = (x == 0 ? 1.0 - f.x : f.x) * (y == 0 ? 1.0 - f.y : f.y);
            sum += h.rgb * weight;
            total += weight;
        }
    }
    if (total < 0.01) {
        return current;
    }
    // Limit stale shadow trails from moving casters; smooth sub-texel changes without averaging scene colour.
    float3 previous = clamp(sum / total, current - 0.25, current + 0.25);
    return mix(current, previous, frame.temporal.w * min(total, 1.0));
}
