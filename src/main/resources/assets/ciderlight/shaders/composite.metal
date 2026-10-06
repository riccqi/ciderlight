// Ciderlight composite pass: runs once after the world is drawn, before the hand and HUD.
// Reads the scene color straight from tile memory (framebuffer fetch) and the scene depth as a texture.
// Terrain shadows itself in its own shader and writes alpha 0; anything else opaque (entities, items,
// particles) still has alpha 1 and is shadowed here from the depth buffer. A shadowed scattering
// volume supplies both fog absorption and illumination, then screen-space sun rays and a final grade.

#include <metal_stdlib>
using namespace metal;

#include "frame.metal"

// Samples along the fog, underwater and screen-space shaft marches. Low quality (Quality.LOW) takes fewer, longer
// steps; the fog and water are accumulated over frames with a moving dither, which hides most of the difference.
#ifdef MC_QUALITY_LOW
constant int AIR_STEPS = 16;
constant int WATER_STEPS = 16;
constant int SHAFT_STEPS = 12;
constant float SHAFT_DECAY = 0.9216; // 0.96 squared: the same falloff towards the sun in half the samples
#else
constant int AIR_STEPS = 24;
constant int WATER_STEPS = 24;
constant int SHAFT_STEPS = 24;
constant float SHAFT_DECAY = 0.96; // each shaft sample counts this much less than the one before it
#endif

struct VOut {
    float4 position [[position]];
    float2 uv;            // GL-style: y up, matches render target row order
    float2 lightUV;       // screen position of the sun or moon
    float lightOnScreen;
};

vertex VOut composite_vertex(uint vid [[vertex_id]], constant FrameData &frame [[buffer(0)]]) {
    float2 p = float2((vid << 1) & 2, vid & 2);
    VOut o;
    o.position = float4(p * 2.0 - 1.0, 0.0, 1.0);
    o.position.y = -o.position.y;
    o.uv = p;
    float4 clip = frame.viewProj * float4(frame.sunDir.xyz * 1000.0, 1.0);
    o.lightOnScreen = clip.w > 0.0 ? 1.0 : 0.0;
    o.lightUV = clip.xy / max(clip.w, 1e-4) * 0.5 + 0.5;
    return o;
}

static float3 grade(float3 c) {
    // Gentle filmic S-curve, a touch of saturation and warmth.
    float3 x = saturate(c); // the S-curve folds values above 1 back down
    // The contrast curve only applies to mid and bright tones; dark tones are left alone so nights stay readable
    // (Complementary lifts its tonemap off dark colours the same way).
    float l0 = dot(x, float3(0.2126, 0.7152, 0.0722));
    x = mix(x, x * x * (3.0 - 2.0 * x) * 0.2 + x * 0.8, smoothstep(0.05, 0.3, l0));
    float l = dot(x, float3(0.2126, 0.7152, 0.0722));
    x = mix(float3(l), x, 1.0);
    x *= float3(1.01, 1.0, 0.98);
    return saturate(x);
}

static float3 reconstruct(constant FrameData &frame, float2 uv, float depth) {
    float4 clip = float4(uv * 2.0 - 1.0, depth, 1.0);
    float4 world = frame.invViewProj * clip;
    return world.xyz / world.w;
}

struct ViewRay {
    float3 origin;    // effective eye in camera-relative world space, including projection-space view bob
    float3 direction;
};

static ViewRay view_ray(constant FrameData &frame, float2 uv) {
    // Minecraft multiplies the walking/hurt bob transform into its perspective projection. Consequently
    // unprojecting a near-plane point does NOT give a vector from (0,0,0). Unproject the perspective eye
    // (a homogeneous clip-space direction) too, and subtract it before normalizing. Otherwise a few cm
    // of walking bob become a large, periodic angular error in the fog and its forward-scattering glow.
    float4 eye = frame.invViewProj * float4(0.0, 0.0, 1.0, 0.0);
    ViewRay ray;
    ray.origin = eye.xyz / eye.w;
    ray.direction = normalize(reconstruct(frame, uv, 1.0) - ray.origin);
    return ray;
}

// Interleaved gradient noise: a stable per-pixel dither for the volumetric march.
static float gradient_noise(float2 p) {
    return fract(52.9829189 * fract(0.06711056 * p.x + 0.00583715 * p.y));
}

#include "shadow.metal"
#include "water.metal"
#include "atmosphere.metal"
#include "ao.metal"
// Local water visibility. The underwater volume stays within the near cascade.
static float3 shadow_point(constant FrameData &frame, depth2d<float> shadowMap, sampler shadowSampler, texture2d<float> shadowColor,
                           texture2d<float> cloudMap, float3 worldPos) {
    float4 sp = frame.shadowMat * float4(worldPos + frame.camToAnchor.xyz, 1.0);
    float2 uv = float2(sp.x * 0.5 + 0.5, 0.5 - sp.y * 0.5);
    if (any(uv < 0.0) || any(uv > 1.0) || sp.z < 0.0 || sp.z >= 1.0) {
        return float3(1.0);
    }
    float3 transmission = shadow_transmission(shadowColor, uv, sp.z - 0.0006) * cloud_shadow_point(frame, cloudMap, uv, worldPos.y);
    return shadowMap.sample_compare(shadowSampler, uv, sp.z - 0.0006) * transmission;
}

// Blend shadow cascades by actual map coverage, retaining the matrix of each captured map.
// Missing coverage supplies ambient fog only; it must never invent a sunlit shaft.
// `nearPoint` and `farPoint` are `p` in the two maps' clip spaces. Both projections are orthographic, so a ray march
// can step them along the ray instead of transforming every sample.
static float3 air_visibility(constant FrameData &frame, depth2d<float> nearMap, depth2d<float> farMap,
                             sampler compare, texture2d<float> transmission, texture2d<float> farTransmission,
                             texture2d<float> cloudMap, float3 p, float3 nearPoint, float3 farPoint) {
    // How much of the answer comes from the near map: all of it well inside, none outside.
    float nearWeight = 0.0;
    float2 nearUV = float2(nearPoint.x * 0.5 + 0.5, 0.5 - nearPoint.y * 0.5);
    if (frame.params.w >= 0.5 && all(nearUV >= 0.0) && all(nearUV <= 1.0) && nearPoint.z >= 0.0 && nearPoint.z < 1.0) {
        float edge = min(min(nearUV.x, nearUV.y), min(1.0 - nearUV.x, 1.0 - nearUV.y));
        nearWeight = smoothstep(0.025, 0.10, edge);
    }
    float3 farVis = float3(0.0);
    float farOpen = 1.0; // the far map's occlusion alone, 1 where it has no coverage
    // Well inside the near map the far map adds nothing: the near map holds every caster between it and the light
    // (it is drawn with depth clamping), and the far map's tint is not blended in there.
    if (frame.farCamToAnchor.w > 0.5 && nearWeight < 1.0) {
        float3 sp = farPoint;
        float2 uv = float2(sp.x * 0.5 + 0.5, 0.5 - sp.y * 0.5);
        if (all(uv >= 0.0) && all(uv <= 1.0) && sp.z >= 0.0 && sp.z < 1.0) {
            farOpen = farMap.sample_compare(compare, uv, sp.z - 0.00035);
            float edge = min(min(uv.x, uv.y), min(1.0 - uv.x, 1.0 - uv.y));
            farVis = farOpen * smoothstep(0.0, 0.025, edge) * shadow_transmission(farTransmission, uv, sp.z - 0.00035);
        }
    }
    if (nearWeight <= 0.0) return farVis;
    // In the band where the maps blend, the far map's occlusion is applied to the near map's answer too.
    // Clouds are in the far map's tint and, for the near map's area, in the cloud map.
    float nearOpen = min(nearMap.sample_compare(compare, nearUV, nearPoint.z - 0.0006), farOpen);
    // Fully shadowed: no glass or cloud tint to look up.
    float3 nearVis = nearOpen > 0.0 ? nearOpen * shadow_transmission(transmission, nearUV, nearPoint.z - 0.0006)
                                      * cloud_shadow_point(frame, cloudMap, nearUV, p.y)
                                    : float3(0.0);
    return mix(farVis, nearVis, nearWeight);
}

static float3 air_visibility(constant FrameData &frame, depth2d<float> nearMap, depth2d<float> farMap,
                             sampler compare, texture2d<float> transmission, texture2d<float> farTransmission,
                             texture2d<float> cloudMap, float3 p) {
    float3 nearPoint = (frame.shadowMat * float4(p + frame.camToAnchor.xyz, 1.0)).xyz;
    float3 farPoint = (frame.farShadowMat * float4(p + frame.farCamToAnchor.xyz, 1.0)).xyz;
    return air_visibility(frame, nearMap, farMap, compare, transmission, farTransmission, cloudMap, p, nearPoint, farPoint);
}

template <typename Noise>
static AirVolume march_air(constant FrameData &frame, depth2d<float> nearMap, depth2d<float> farMap,
                            sampler compare, texture2d<float> transmission, texture2d<float> farTransmission,
                            texture2d<float> cloudMap, depth2d<float> lightningMap, float3 origin, float3 V, float rayEnd,
                            float dither, Noise noise, bool skyRay = false) {
    AirVolume volume = {float3(0.0), 1.0, 0.0};
    AirMedium medium = air_medium(frame);
    // No medium (deep caves, dimensions without an atmosphere): nothing to march.
    if (all(medium.scale <= 0.0)) return volume;
    bool moon = frame.lightParams.z > 0.5;
    // Retain sky-coloured fill, but leave enough contrast for shadowed gaps to separate the shafts.
    float3 ambient = atmosphere_ambient(frame) * 0.35;
    // Far away the air takes on the colour of the sky behind it (aerial perspective): distant hills fade into the
    // pale horizon rather than into a dark blue veil. Nearby, the dimmer fill keeps shafts and shade contrasty.
    float3 skyBehind = atmosphere_sky(frame, V) * (skyRay ? 0.97 : 0.85);
    // The low sun lights the mist most strongly (golden hour), and keeps doing so until it is at the horizon, but
    // somewhat less than at noon: looking towards it, the haze around the sun would glare.
    float3 direct = frame.sunColor.rgb * frame.sunDir.w * (moon ? 0.5 : 1.65 * (1.0 - 0.2 * frame.airNear.z))
                  * smoothstep(0.0, 0.04, frame.sunDir.y) * air_phase(dot(V, frame.sunDir.xyz));
    bool lit = any(direct > 0.0);
    // A lightning bolt shines through the air too, with its own shadows: shafts between the trees for a moment.
    bool flash = frame.lightning.w > 0.0;
    float3 flashLight = float3(0.78, 0.84, 1.0) * (frame.lightning.w * 1.7);
    // The dither moves the samples every frame and the result is accumulated over frames (volumetric_fragment).
    // The ray's start and direction in both shadow maps.
    float3 nearOrigin = (frame.shadowMat * float4(origin + frame.camToAnchor.xyz, 1.0)).xyz;
    float3 nearStep = (frame.shadowMat * float4(V, 0.0)).xyz;
    float3 farOrigin = (frame.farShadowMat * float4(origin + frame.farCamToAnchor.xyz, 1.0)).xyz;
    float3 farStep = (frame.farShadowMat * float4(V, 0.0)).xyz;
    const int steps = AIR_STEPS;
    for (int i = 0; i < steps; i++) {
        float a = float(i) / float(steps), b = float(i + 1) / float(steps);
        float start = a * a * rayEnd, width = (b * b - a * a) * rayEnd;
        float distance = start + width * dither;
        float3 p = origin + V * distance;
        float density = dot(air_density_layers(medium, noise, p + frame.cameraPos.xyz), float3(1.0));
        // Thicker air around the camera, so nearby scenery sits in mist and shafts show close by without the
        // distance whiting out. It depends on distance along the ray only, so walking does not sway it.
        // (Half of it around sunrise and sunset: with the sun low and ahead, thick mist at the camera glares.)
        density *= 1.0 + frame.airNear.x * (1.0 - 0.5 * frame.airNear.z) * exp(-distance / max(frame.airNear.y, 1.0));
        // A ray into the open sky scatters the sky's own colour all along it: the sky already is the light the air
        // scatters, and the dim fill would lay a grey veil over its blue.
        float3 incident = skyRay ? skyBehind : mix(ambient, skyBehind, smoothstep(48.0, 200.0, distance));
        // A step that scatters next to nothing (thin air high up) is not worth the shadow lookups.
        if (lit && density * width > 1e-4) {
            incident += direct * air_visibility(frame, nearMap, farMap, compare, transmission, farTransmission, cloudMap, p,
                                                nearOrigin + nearStep * distance, farOrigin + farStep * distance);
        }
        if (flash && density * width > 1e-4) {
            float3 toBolt = float3(frame.lightning.x - p.x, 0.0, frame.lightning.z - p.z);
            float distance2 = dot(toBolt, toBolt);
            toBolt.y = 0.6 * sqrt(distance2) + 4.0;
            incident += flashLight * (air_phase(dot(V, normalize(toBolt))) / (1.0 + distance2 / (160.0 * 160.0)))
                      * lightning_shadow(frame, lightningMap, compare, p, float3(0.0));
        }
        air_integrate(volume, density, width, distance, incident);
    }
    return volume;
}

static AirVolume march_air(constant FrameData &frame, depth2d<float> nearMap, depth2d<float> farMap,
                            sampler compare, texture2d<float> transmission, texture2d<float> farTransmission,
                            texture2d<float> cloudMap, depth2d<float> lightningMap, float3 origin, float3 V, float rayEnd,
                            float dither) {
    return march_air(frame, nearMap, farMap, compare, transmission, farTransmission, cloudMap, lightningMap, origin, V, rayEnd,
                     dither, AirHashNoise{});
}

// Refracted entry point into the local water surface. Above-water occluders shadow this
// point; the receiver lookup additionally prevents light leaking through submerged geometry.
static float3 water_visibility(constant FrameData &frame, depth2d<float> shadowMap, sampler shadowSampler,
                              texture2d<float> shadowColor, texture2d<float> cloudMap, float3 p, float3 entry) {
    if (frame.params.w < 0.5 || frame.sunDir.w <= 0.0) return float3(0.0);
    return min(shadow_point(frame, shadowMap, shadowSampler, shadowColor, cloudMap, entry + float3(0.0, 0.03, 0.0)),
               shadow_point(frame, shadowMap, shadowSampler, shadowColor, cloudMap, p));
}

static float3 march_water(constant FrameData &frame, depth2d<float> shadowMap, sampler shadowSampler,
                          texture2d<float> shadowColor, texture2d<float> cloudMap, float3 origin, float3 V, float rayEnd,
                          float dither) {
    float3 extinction = water_extinction(frame);
    float3 scatter = float3(0.026, 0.043, 0.040) * max(frame.waterParams.w, 0.1);
    float3 L = water_light_direction(frame.sunDir.xyz);
    float phase = water_phase(dot(V, L));
    bool moon = frame.lightParams.z > 0.5;
    float3 sun = frame.sunColor.rgb * frame.sunDir.w * (moon ? 0.14 : 3.5)
               * smoothstep(0.0, 0.2, frame.sunDir.y);
    float3 ambient = float3(0.25, 0.35, 0.40) * frame.waterParams.z
                  * mix(0.15, 1.0, frame.lightParams.x);
    float3 sum = float3(0.0);
    const int steps = WATER_STEPS;
    for (int i = 0; i < steps; i++) {
        // Quadratic spacing resolves nearby beams more densely without leaving unintegrated gaps.
        float a = float(i) / float(steps), b = float(i + 1) / float(steps);
        float start = a * a * rayEnd, width = (b * b - a * a) * rayEnd;
        float3 p = origin + V * (start + width * dither);
        float depth = max(frame.waterParams.y - p.y, 0.0);
        float lightPath = depth / L.y;
        float3 entry = p + L * lightPath;
        float3 visibility = water_visibility(frame, shadowMap, shadowSampler, shadowColor, cloudMap, p, entry);
        float focus = water_caustics(entry.xz + frame.cameraPos.xz, frame.params.x, depth);
        float3 incident = sun * visibility * phase * focus * exp(-extinction * lightPath)
                        + ambient * exp(-extinction * depth * 0.65);
        sum += incident * scatter * water_interval(extinction, start, width);
    }
    return sum;
}

// Bilateral upsampling of the half-resolution fog: foreground silhouettes must not inherit a long,
// bright background ray. When none of the neighbours represents this surface no light is added, except under water
// (`diffuse`), where the neighbour closest in depth is used, scaled down to this pixel's shorter ray: most of a
// pixel's brightness there is this light, so adding none leaves black pixels along the outlines of thin things
// (seaweed, a squid, the water line).
static float3 volumetric_scattering(texture2d<float> volumetric, float2 uv, float rayFraction, bool diffuse = false) {
    float2 p = uv * float2(volumetric.get_width(), volumetric.get_height()) - 0.5;
    int2 base = int2(floor(p));
    int2 hi = int2(volumetric.get_width(), volumetric.get_height()) - 1;
    float3 sum = float3(0.0);
    float total = 0.0;
    float4 closest = float4(0.0);
    float closestGap = 1e9;
    // Water's moving caustics need a slightly broader reconstruction footprint than air fog.
    int first = diffuse ? -1 : 0, end = diffuse ? 3 : 2;
    float radius = diffuse ? 2.0 : 1.0;
    for (int y = first; y < end; y++) {
        for (int x = first; x < end; x++) {
            float4 v = volumetric.read(uint2(clamp(base + int2(x, y), int2(0), hi)));
            float2 tent = saturate(1.0 - abs(float2(base + int2(x, y)) - p) / radius);
            float weight = tent.x * tent.y;
            weight *= saturate(1.0 - abs(v.a - rayFraction) * 40.0);
            sum += v.rgb * weight;
            total += weight;
            if (abs(v.a - rayFraction) < closestGap) {
                closestGap = abs(v.a - rayFraction);
                closest = v;
            }
        }
    }
    if (total > 0.001) return sum / total;
    if (!diffuse) return float3(0.0);
    return closest.rgb * saturate(rayFraction / max(closest.a, 1e-4));
}

// Reconstruct scattering and transmission with the same depth-aware weights.
// The open sky and what stands in front of it never share a texel: far terrain is at nearly the sky's ray length but
// in different fog, and blending the two redraws distant silhouettes at the fog buffer's coarse resolution.
static float4 air_sample(texture2d<float> light, texture2d<float> extinction, float2 uv, float rayFraction, bool sky) {
    float2 p = uv * float2(light.get_width(), light.get_height()) - 0.5;
    int2 base = int2(floor(p)), hi = int2(light.get_width(), light.get_height()) - 1;
    float4 sum = float4(0.0);
    float total = 0.0;
    float4 closest = float4(0.0, 0.0, 0.0, 1.0);
    float closestFraction = 1.0, closestGap = 1e9;
    for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
        uint2 texel = uint2(clamp(base + int2(x, y), int2(0), hi));
        float4 v = light.read(texel);
        float2 tent = saturate(1.0 - abs(float2(base + int2(x, y)) - p));
        // Only the open sky stores exactly 1 (volumetric_fragment).
        bool sameKind = (v.a > 0.999) == sky;
        float weight = sameKind ? tent.x * tent.y * saturate(1.0 - abs(v.a - rayFraction) * 40.0) : 0.0;
        float4 fog = float4(v.rgb, extinction.read(texel).r);
        sum += fog * weight;
        total += weight;
        float gap = abs(v.a - rayFraction) + (sameKind ? 0.0 : 1.0);
        if (gap < closestGap) {
            closestGap = gap;
            closest = fog;
            closestFraction = v.a;
        }
    }
    if (total > 0.001) return sum / total;
    // No neighbour is at this pixel's depth (a leaf in front of a far hill, or the hill seen through a gap in the
    // leaves). Leaving such pixels without fog shows as dark specks in distant trees, so the neighbour closest in depth
    // is rescaled to this ray's length as if the fog were even along it.
    float ratio = clamp(rayFraction / max(closestFraction, 1e-4), 0.0, 2.0);
    float transmittance = pow(saturate(closest.a), ratio);
    float scale = closest.a < 0.999 ? (1.0 - transmittance) / (1.0 - closest.a) : ratio;
    return float4(closest.rgb * scale, transmittance);
}

fragment float4 composite_fragment(VOut in [[stage_in]],
                                   float4 scene [[color(0)]],
                                   constant FrameData &frame [[buffer(0)]],
                                   depth2d<float> depth [[texture(0)]],
                                   depth2d<float> shadowMap [[texture(1)]],
                                   sampler shadowSampler [[sampler(1)]],
                                   texture2d<float> volumetric [[texture(2)]],
                                   texture2d<float> shadowColor [[texture(3)]],
                                   texture2d<float> surfaceHistory [[texture(4)]],
                                   texture2d<float> extinctionHistory [[texture(5)]],
                                   texture2d<float> ambientOcclusion [[texture(6)]],
                                   texture2d<float> cloudShadow [[texture(7)]],
                                   depth2d<float> lightningShadow [[texture(8)]]) {
    constexpr sampler s(filter::nearest, address::clamp_to_edge);
    float3 color = scene.rgb;
    float d = depth.sample(s, in.uv);
    bool isSky = d <= 0.0; // reverse-Z: the sky never wrote depth
    float lightStrength = frame.sunDir.w;
    float3 lightDir = frame.sunDir.xyz;
    bool moon = frame.lightParams.z > 0.5;
    float nightSky = frame.lightParams.y;
    float far = max(frame.fogParams.y, 32.0);

    // Derivatives of the reconstructed position give a face normal; take them in uniform control flow.
    float3 worldPos = reconstruct(frame, in.uv, max(d, 1e-6));
    float3 normal = normalize(cross(dfdx(worldPos), dfdy(worldPos)));
    float2 distanceGradient = float2(dfdx(length(worldPos)), dfdy(length(worldPos)));
    ViewRay ray = view_ray(frame, in.uv);
    float3 V = ray.direction;
    float dist = isSky ? far : length(worldPos - ray.origin);

    // Ambient occlusion for terrain, from this frame's GTAO pass. Terrain's alpha holds how much of the pixel's light
    // is ambient (sky light in shade, cave ambience, a little of the torch light), which is all that AO takes away.
    if (scene.a < TERRAIN_AO_ALPHA + 0.012 && !isSky && frame.fogParams.w > 0.5) {
        float ao = ao_apply(ao_lookup(ambientOcclusion, in.uv, length(worldPos), distanceGradient, ao_scale(frame)).x, frame.airNear.z);
        color *= 1.0 - saturate(scene.a / TERRAIN_AO_ALPHA) * (1.0 - ao);
    }

    // Deferred shadows and night darkening for non-terrain geometry (terrain already lit itself and wrote alpha below 0.5).
    // Alpha in [0.5, 0.95] carries the entity's block light from the marker pass; 1 means no information.
    // Flame particles are light sources: full brightness and unshaded, like the flames of blocks (terrain.metal).
    // Glowing eyes are unshaded too, at their own colour.
    bool emissive = entity_emissive(scene.a) && !isSky;
    if (emissive && entity_flame(scene.a)) {
        color *= 1.25;
    }
    if (scene.a >= 0.5 && !isSky && !emissive) {
        float blockL = decode_entity_light(scene.a);
        float3 shade = float3(1.0);
        float directShare = 0.0;
        float3 vis = float3(0.0); // sun or moon reaching this pixel; stays 0 on faces turned away from the light
        if (lightStrength > 0.0 && frame.params.w > 0.5) {
            if (dot(normal, ray.origin - worldPos) < 0.0) {
                normal = -normal;
            }
            float ndotl = saturate(dot(normal, lightDir));
            if (ndotl > 0.0) {
                vis = shadow_visibility(frame, shadowMap, shadowSampler, shadowColor, cloudShadow, worldPos, normal, ndotl);
                vis = shadow_temporal(frame, surfaceHistory, worldPos, vis, distanceGradient * 2.0);
            }
            // Vanilla already applies its own directional entity lighting, so only soften by facing.
            float3 direct = vis * saturate(ndotl * 3.0);
            directShare = dot(direct, float3(0.333)) * lightStrength;
            // The same sky and sun light as terrain (terrain.metal).
            shade = mix(float3(1.0), frame.surfaceAmbient.rgb, lightStrength) + frame.surfaceSun.rgb * direct * lightStrength;
        }
        // Where torch light dominates, the sun/moon shading and night darkening give way to a warm torch tint, so a
        // mob next to a torch is lit like the ground around it.
        float torch = torch_light(blockL);
        // Ambient occlusion from this frame's GTAO pass: it darkens the sky/ambient share of the light only, so it
        // fades out where the mob is in direct sun or torch light.
        if (frame.fogParams.w > 0.5) {
            float ao = ao_apply(ao_lookup(ambientOcclusion, in.uv, length(worldPos), distanceGradient, ao_scale(frame)).x, frame.airNear.z);
            shade *= mix(ao, 1.0, saturate(directShare));
        }
        float3 sunShade = shade * mix(1.0, nightSky, 0.6);
        float3 torchShade = float3(1.05, 0.9, 0.7);
        color = highlight_rolloff(color * mix(sunShade, max(sunShade, torchShade), saturate(torch * 1.5)));
        // Mobs and items have no sky-light value here, so a lightning bolt brightens all of them around it.
        if (frame.lightning.w > 0.0) {
            float3 facing = dot(normal, ray.origin - worldPos) < 0.0 ? -normal : normal;
            color *= 1.0 + lightning_light(frame, worldPos, facing) * lightning_shadow(frame, lightningShadow, shadowSampler, worldPos, facing)
                         + lightning_sky_light(frame);
        }
        // Gold-coloured things drawn through the entity path (bells, gold armour) shine in direct light. There is no
        // material information here, so gold is recognised by its colour.
        float maxC = max(color.r, max(color.g, color.b));
        float sat = maxC > 0.0 ? (maxC - min(color.r, min(color.g, color.b))) / maxC : 0.0;
        float gold = (color.r > 0.45 && color.r >= color.g && color.g > color.b * 1.5 && sat > 0.4) ? 1.0 : 0.0;
        if (gold > 0.0 && lightStrength > 0.0 && frame.params.w > 0.5) {
            float3 h = normalize(lightDir - V);
            float ndoth = saturate(dot(normal, h));
            color += frame.sunColor.rgb * vis * (pow(ndoth, 48.0) * 0.9 + pow(ndoth, 6.0) * 0.1) * lightStrength;
        }
    }

    if (frame.waterParams.x > 0.5) {
        float waterDistance = water_ray_length(ray.origin, V, dist, frame.waterParams.y);
        float3 extinction = water_extinction(frame);
        if (!isSky && worldPos.y < frame.waterParams.y && frame.params.w > 0.5) {
            if (dot(normal, ray.origin - worldPos) < 0.0) normal = -normal;
            float3 L = water_light_direction(lightDir);
            float depthBelow = max(frame.waterParams.y - worldPos.y, 0.0);
            float3 entry = worldPos + L * (depthBelow / L.y);
            float3 visibility = water_visibility(frame, shadowMap, shadowSampler, shadowColor, cloudShadow,
                                                worldPos + normal * 0.05, entry);
            float caustic = water_caustics(entry.xz + frame.cameraPos.xz, frame.params.x, depthBelow);
            color += color * max(caustic - 1.0, 0.0) * visibility * saturate(dot(normal, L))
                   * exp(-extinction * depthBelow / L.y) * lightStrength * (moon ? 0.08 : 0.65);
        }
        color = color * exp(-extinction * waterDistance)
              + volumetric_scattering(volumetric, in.uv, min(waterDistance / 48.0, 1.0), true);
    } else {
        float4 fog = air_sample(volumetric, extinctionHistory, in.uv, min(dist / air_range(frame), 1.0), isSky);
        // Clouds take less of the haze than the ground does: most of the air is below them, and they should stand
        // out white against the sky rather than melt into it.
        bool cloud = !isSky && frame.cameraPos.w > -1.0e8 && worldPos.y > frame.cameraPos.w - 0.5;
        color = mix(color * saturate(fog.a) + fog.rgb, color, cloud ? 0.6 : 0.0);
        // Aerial perspective: far terrain fades into the colour of the sky behind it, little within a hundred blocks
        // and most of the way at the edge of the render distance, so hills stack up in paler layers. It follows the
        // render distance, and stays out of caves.
        if (!isSky && !cloud && frame.solarDir.w > 0.5) {
            float reach = dist * min(1.0, 192.0 / far);
            float aerial = (1.0 - exp(-reach * reach * reach * 1.0e-6)) * 0.3 * smoothstep(0.1, 0.6, frame.fogParams.x);
            color = mix(color, atmosphere_sky(frame, V) * 0.95, aerial);
        }

        // Screen-space shafts: march towards the sun and count how much open sky there is. Anything else blocks it,
        // clouds and distant terrain included, so light streams through the gaps between clouds. The volumetric
        // texture's alpha holds each pixel's distance as a fraction of the fog range, exactly 1 only for the sky.
        float shaftStrength = lightStrength * in.lightOnScreen * (moon ? 0.45 : 1.0);
        float2 delta = in.lightUV - in.uv;
        float falloff = saturate(1.0 - length(delta * float2(1.6, 1.0)) * 0.9);
        if (shaftStrength > 0.0 && falloff > 0.0) {
            const int steps = SHAFT_STEPS;
            float2 stepUV = delta / float(steps);
            constexpr sampler lin(filter::linear, address::clamp_to_edge);
            float2 uv = in.uv + stepUV * gradient_noise(in.position.xy);
            float sky = 0.0;
            float weight = 1.0;
            float total = 0.0;
            for (int i = 0; i < steps; i++) {
                uv += stepUV;
                sky += smoothstep(0.998, 0.9995, volumetric.sample(lin, uv).a) * weight;
                total += weight;
                weight *= SHAFT_DECAY;
            }
            // The glow has the same strength whether or not anything covers the sun: occluders only carve their own
            // shadows out of it, per pixel. Scaling it by how much is covered near the sun would switch the whole
            // screen's glow on as a block drifts in front of it. It is kept moderate so the open-sky glow does not
            // wash out the square sun.
            float shafts = sky / total * falloff * falloff;
            // Additive, so the rays still glow over the already bright sky around the sun.
            float3 rayCol = moon ? float3(0.55, 0.65, 0.95) : frame.sunColor.rgb * float3(1.0, 0.86, 0.64);
            // Weaker at golden hour: with the sun low, the shafts land on the already bright horizon and glare.
            color += rayCol * shafts * 0.10 * (1.0 - 0.45 * frame.airNear.z) * shaftStrength * smoothstep(0.1, 0.6, frame.fogParams.x);
        }
    }

#ifdef MC_DEBUG_AO
    if (!isSky && frame.fogParams.w > 0.5) {
        return float4(float3(ao_lookup(ambientOcclusion, in.uv, length(worldPos), distanceGradient, ao_scale(frame)).x), 1.0);
    }
#endif
    color = grade(color);

    // Soft vignette.
    float2 v = in.uv - 0.5;
    color *= 1.0 - dot(v, v) * 0.18;
    return float4(color, 1.0);
}

// --- volumetric light pass ---
// Runs before the composite into its own texture. Each frame marches a different, dithered set of points along the
// view ray through the shadow map (the way Complementary does) and blends the result with the previous frame's,
// reprojected to this camera. Averaging over frames is what makes the light stable while moving; a single frame's
// march would swim as the sample points slide through the shadows.

struct VolumeOutput {
    float4 light [[color(0)]];
    float transmittance [[color(1)]];
};

fragment VolumeOutput volumetric_fragment(VOut in [[stage_in]],
                                    constant FrameData &frame [[buffer(0)]],
                                    depth2d<float> depth [[texture(0)]],
                                    depth2d<float> shadowMap [[texture(1)]],
                                    sampler shadowSampler [[sampler(1)]],
                                    texture2d<float> history [[texture(2)]],
                                    texture2d<float> shadowColor [[texture(3)]],
                                    depth2d<float> farMap [[texture(4)]],
                                    texture2d<float> extinctionHistory [[texture(5)]],
                                    texture2d<float> farTransmission [[texture(6)]],
                                    texture2d<float> cloudShadow [[texture(7)]],
                                    depth2d<float> lightningShadow [[texture(8)]],
                                    texture2d<float> airNoise [[texture(9)]]) {
    constexpr sampler s(filter::nearest, address::clamp_to_edge);
    constexpr sampler lin(filter::linear, address::clamp_to_edge);
    float d = depth.sample(s, in.uv);
    bool isSky = d <= 0.0;
    float3 worldPos = reconstruct(frame, in.uv, max(d, 1e-6));
    ViewRay ray = view_ray(frame, in.uv);
    float3 V = ray.direction;
    bool underwater = frame.waterParams.x > 0.5;
    float maxDist = underwater ? 48.0 : air_range(frame);
    float dist = isSky ? maxDist : min(length(worldPos - ray.origin), maxDist);
    if (underwater) dist = water_ray_length(ray.origin, V, dist, frame.waterParams.y);
    float dither = fract(gradient_noise(in.position.xy) + frame.temporal.x * 0.618034);
    AirVolume fog = {float3(0.0), 1.0, 0.0};
    float3 lit;
    if (underwater) lit = march_water(frame, shadowMap, shadowSampler, shadowColor, cloudShadow, ray.origin, V, dist, dither);
    else {
        fog = march_air(frame, shadowMap, farMap, shadowSampler, shadowColor, farTransmission, cloudShadow, lightningShadow, ray.origin, V, dist, dither,
                        AirTableNoise{airNoise}, isSky || (frame.cameraPos.w > -1.0e8 && worldPos.y > frame.cameraPos.w - 0.5));
        lit = fog.scattering;
    }

    // Temporal blend with the previous frame, reprojected: the same world point in the previous camera's view.
    if (frame.temporal.y > 0.5) {
        float anchorDistance = fog.transmittance < 0.999 ? fog.moment / (1.0 - fog.transmittance) : dist * 0.5;
        float3 anchor = underwater ? (isSky ? ray.origin + V * 4096.0 : worldPos)
                                  : ray.origin + V * anchorDistance;
        float3 prevRel = anchor + frame.cameraDelta.xyz;
        float4 clip = frame.prevViewProj * float4(prevRel, 1.0);
        if (clip.w > 0.0) {
            float2 uv = clip.xy / clip.w * 0.5 + 0.5;
            if (all(uv > 0.001) && all(uv < 0.999)) {
                float4 h = history.sample(lin, uv);
                // Reject history from a very different depth (something moved in front or behind).
                float depthDiff = abs(h.a - dist / maxDist);
                float keep = depthDiff < 0.025 ? (underwater ? 0.92 : frame.airParams.w) : 0.0;
                // Nor across the edge between the open sky (stored as exactly 1) and far terrain in front of it.
                if (!underwater && (h.a > 0.999) != isSky) keep = 0.0;
                // Water is denser and animated: clamp old radiance to avoid dragging bright beams through shadows.
                float limit = underwater ? 0.12 : 0.06;
                float3 old = clamp(h.rgb, max(lit - limit, 0.0), lit + limit);
                if (!underwater) {
                    float oldT = clamp(extinctionHistory.sample(lin, uv).r, fog.transmittance - 0.04, fog.transmittance + 0.04);
                    fog.transmittance = mix(fog.transmittance, oldT, keep);
                }
                lit = mix(lit, old, keep);
            }
        }
    }
    // Above water, only the open sky stores exactly 1, so the sun rays can tell it from clouds and far terrain.
    float rayFraction = underwater ? dist / maxDist : (isSky ? 1.0 : min(dist / maxDist, 0.998));
    return {float4(lit, rayFraction), saturate(fog.transmittance)};
}

// Save visibility, independently of material colour and lighting, for next frame's forward terrain pass.
// Terrain and composite both blend history with a full-resolution current sample.
// Alpha stores camera distance for disocclusion rejection.
// Fills the fog's noise table (AirTableNoise): texel (x, y) holds air_hash of cell (x, y). Drawn once.
fragment float4 air_noise_table_fragment(VOut in [[stage_in]]) {
    return float4(air_hash(floor(in.position.xy)), 0.0, 0.0, 1.0);
}

fragment float4 shadow_history_fragment(VOut in [[stage_in]],
                                        constant FrameData &frame [[buffer(0)]],
                                        depth2d<float> depth [[texture(0)]],
                                        depth2d<float> shadowMap [[texture(1)]],
                                        sampler shadowSampler [[sampler(1)]],
                                        texture2d<float> history [[texture(2)]],
                                        texture2d<float> shadowColor [[texture(3)]],
                                        texture2d<float> cloudShadow [[texture(7)]]) {
    constexpr sampler s(filter::nearest, address::clamp_to_edge);
    float d = depth.sample(s, in.uv);
    float3 p = reconstruct(frame, in.uv, max(d, 1e-6));
    float3 n = normalize(cross(dfdx(p), dfdy(p)));
    float2 distanceGradient = float2(dfdx(length(p)), dfdy(length(p))); // this pass runs at the history's resolution
    if (d <= 0.0 || frame.params.w < 0.5) {
        return float4(1.0, 1.0, 1.0, 0.0);
    }
    if (dot(n, -p) < 0.0) {
        n = -n;
    }
    float ndotl = saturate(dot(n, frame.sunDir.xyz));
    float3 vis = shadow_visibility(frame, shadowMap, shadowSampler, shadowColor, cloudShadow, p, n, ndotl);
    return float4(shadow_temporal(frame, history, p, vis, distanceGradient), min(length(p), 65000.0));
}

// --- ambient occlusion passes ---
// Reduced-resolution GTAO (see ao.metal, ao_scale) in two passes: ao_fragment evaluates two noisy slices per pixel; ao_filter_fragment
// averages each 3x3 block of the same surface (interleaved gradient noise spreads the slice angles evenly over any 3x3
// block) and blends with the reprojected previous result. Forward terrain reads that result one frame later through
// ao_reprojected; the composite reads it directly for entities.
// fogParams.w: 0 AO disabled, 1 enabled without history, 2 enabled with valid history.
// Raw texture: r visibility, gb distance gradient per full-resolution pixel, a camera distance (0 for sky).
fragment float4 ao_fragment(VOut in [[stage_in]],
                            constant FrameData &frame [[buffer(0)]],
                            depth2d<float> depth [[texture(0)]],
                            texture2d<float> scene [[texture(1)]]) {
    float2 size = float2(depth.get_width(), depth.get_height());
    // Each AO texel evaluates full-resolution pixel scale * i + scale / 2; ao_lookup relies on this.
    int scale = int(ao_scale(frame));
    int2 pixel = min(int2(in.position.xy) * scale + scale / 2, int2(size) - 1);
    float d = depth.read(uint2(pixel));
    if (d <= 0.0) return float4(1.0, 0.0, 0.0, 0.0);
    float3 P = ao_reconstruct(frame, (float2(pixel) + 0.5) / size, d);
    float2 noise = fract(float2(gradient_noise(in.position.xy), gradient_noise(in.position.yx + 17.0))
                         + frame.temporal.x * float2(0.618034, 0.754878));
    float2 gradient;
    float ao = gtao(frame, depth, pixel, noise, gradient, AoSceneMask{scene});
    return float4(ao, gradient, min(length(P), 65000.0));
}

fragment float4 ao_filter_fragment(VOut in [[stage_in]],
                                   constant FrameData &frame [[buffer(0)]],
                                   depth2d<float> depth [[texture(0)]],
                                   texture2d<float> raw [[texture(1)]],
                                   texture2d<float> history [[texture(2)]]) {
    int2 texel = int2(in.position.xy);
    int2 hi = int2(raw.get_width(), raw.get_height()) - 1;
    float4 centre = raw.read(uint2(texel));
    if (centre.a <= 0.0) return float4(1.0, 0.0, 0.0, 0.0);
    float2 gradient = centre.gb;
    float tolerance = max(0.05, centre.a * 0.004) + 0.5 * length(gradient);
    float sum = 0.0, total = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float4 v = raw.read(uint2(clamp(texel + int2(x, y), int2(0), hi)));
            float predicted = centre.a + dot(gradient, float2(x, y) * ao_scale(frame));
            if (v.a <= 0.0 || abs(v.a - predicted) > tolerance) continue;
            sum += v.r;
            total += 1.0;
        }
    }
    float ao = sum / total; // the centre always matches itself
    float2 size = float2(depth.get_width(), depth.get_height());
    int scale = int(ao_scale(frame));
    int2 pixel = min(texel * scale + scale / 2, int2(size) - 1);
    float3 P = ao_reconstruct(frame, (float2(pixel) + 0.5) / size, depth.read(uint2(pixel)));
    float2 previous = ao_reprojected(frame, history, P, gradient);
    if (previous.y > 0.0) {
        // Bounded history: a moving mob's contact shadow cannot trail for long, the remaining noise still averages.
        // The bound must stay wider than the per-frame slice noise, or that noise shows as fine diagonal stripes.
        ao = mix(ao, clamp(previous.x, ao - 0.2, ao + 0.2), 0.85);
    }
    return float4(ao, 0.0, 0.0, centre.a);
}

// --- per-frame constants ---
// A 1x1 texture of values that depend on the frame alone but are too costly to work out at every terrain pixel:
// rgb the tint of shadowed sky light (three evaluations of the sky).
fragment float4 frame_constants_fragment(VOut in [[stage_in]], constant FrameData &frame [[buffer(0)]]) {
    return float4(atmosphere_shadow_tint(frame), 1.0);
}

// World render scale (RenderScale): the world, drawn at a fraction of the window's resolution, is stretched over the
// full window before the HUD is drawn on it. Bilinear, then contrast-adaptive sharpening (after AMD's FidelityFX CAS,
// written from its published description) to win back some of the crispness the stretch softens: each pixel is pushed
// away from its four neighbours, less so where they already differ a lot, so edges do not ring.
constant float UPSCALE_SHARPNESS = 0.6; // 0: plain bilinear, 1: the strongest CAS setting

fragment float4 upscale_fragment(VOut in [[stage_in]], texture2d<float> source [[texture(0)]]) {
    constexpr sampler lin(filter::linear, address::clamp_to_edge);
    float2 texel = 1.0 / float2(source.get_width(), source.get_height());
    float3 c = source.sample(lin, in.uv).rgb;
    float3 n = source.sample(lin, in.uv + float2(0.0, -texel.y)).rgb;
    float3 s = source.sample(lin, in.uv + float2(0.0, texel.y)).rgb;
    float3 w = source.sample(lin, in.uv + float2(-texel.x, 0.0)).rgb;
    float3 e = source.sample(lin, in.uv + float2(texel.x, 0.0)).rgb;
    float3 lo = min(c, min(min(n, s), min(w, e)));
    float3 hi = max(c, max(max(n, s), max(w, e)));
    float3 amount = sqrt(saturate(min(lo, 1.0 - hi) / max(hi, 1e-4)));
    float3 weight = -amount / mix(8.0, 5.0, UPSCALE_SHARPNESS);
    return float4(saturate((c + (n + s + w + e) * weight) / (1.0 + 4.0 * weight)), 1.0);
}
