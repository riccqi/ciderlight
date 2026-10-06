// Total air density at a world position, as the volumetric fog march sees it (composite.metal).
static float3 air_density_layers(constant FrameData &frame, float3 world) {
    return air_density_layers(air_medium(frame), AirHashNoise{}, world);
}

static float air_density(constant FrameData &frame, float3 world) {
    return dot(air_density_layers(frame, world), float3(1.0));
}

kernel void shader_checks(constant FrameData &frame [[buffer(0)]], device float *out [[buffer(1)]],
                          depth2d<float> depth [[texture(0)]], texture2d<float> color [[texture(1)]],
                          texture2d<float> clear [[texture(2)]], texture2d<float> history [[texture(3)]], texture2d<float> fog [[texture(4)]]) {
    constexpr sampler compare(filter::linear, address::clamp_to_edge, compare_func::less_equal);
    int at = 0;
    out[at++] = shadow_transmission(color, float2(0.25, 0.5), 0.4).r; // before glass
    out[at++] = shadow_transmission(color, float2(0.25, 0.5), 0.6).r; // behind glass
    out[at++] = shadow_transmission(color, float2(0.5, 0.5), 0.6).r; // filter after the depth test
    out[at++] = shadow_transmission(color, float2(0.75, 0.5), 0.6).r; // before the second texel's glass
    out[at++] = shadow_temporal(frame, history, float3(0.0, 0.0, 2.0), float3(0.3)).r; // disoccluded
    out[at++] = shadow_temporal(frame, history, float3(0.0, 0.0, 0.5), float3(0.3)).r; // one valid bilinear neighbour
    out[at++] = shadow_temporal(frame, history, float3(3.0, 0.0, 0.5), float3(0.5)).r; // off screen
    for (int level = 0; level <= 15; level++) {
        float alpha = round(encode_entity_light(float(level) / 15.0) * 255.0) / 255.0;
        out[at++] = decode_entity_light(alpha);
    }
    out[at++] = decode_entity_light(1.0);
    out[at++] = volumetric_scattering(fog, float2(0.5), 0.2).r;
    out[at++] = volumetric_scattering(fog, float2(0.5), 0.8).r;
    out[at++] = volumetric_scattering(fog, float2(0.5), 0.5).r;
    out[at++] = volumetric_scattering(fog, float2(0.5), 0.0).r;
    out[at++] = volumetric_scattering(fog, float2(0.5), 0.2, true).r;
    out[at++] = volumetric_scattering(fog, float2(0.5), 0.8, true).r;
    out[at++] = volumetric_scattering(fog, float2(0.5), 0.5, true).r;
    out[at++] = volumetric_scattering(fog, float2(0.5), 0.0, true).r;
}

// Exercise the actual reconstruction with Minecraft-style perspective * translation * rotation matrices.
kernel void bob_ray_checks(constant FrameData *frames [[buffer(0)]], device float4 *out [[buffer(1)]],
                           uint tid [[thread_position_in_grid]]) {
    const float2 uvs[] = {float2(0.5), float2(0.2, 0.8), float2(0.95, 0.1)};
    constant FrameData &frame = frames[tid / 3];
    float2 uv = uvs[tid % 3];
    ViewRay ray = view_ray(frame, uv);
    out[tid * 3] = float4(ray.origin, 0.0);
    out[tid * 3 + 1] = float4(ray.direction, 0.0);
    float4 clip = frame.viewProj * float4(ray.origin + ray.direction * 10.0, 1.0);
    // A marched point must project back to its own pixel, even during a footstep or hurt bob.
    float2 error = clip.xy / clip.w * 0.5 + 0.5 - uv;
    float3 legacy = normalize(reconstruct(frame, uv, 0.5));
    out[tid * 3 + 2] = float4(error, 0.0, dot(legacy, ray.direction));
}

kernel void water_checks(constant FrameData *frames [[buffer(0)]], device float4 *out [[buffer(1)]],
                         depth2d<float> open [[texture(0)]], depth2d<float> blocked [[texture(1)]],
                         texture2d<float> clear [[texture(2)]]) {
    constexpr sampler compare(filter::linear, address::clamp_to_edge, compare_func::less_equal);
    float3 sigma = water_extinction(frames[0]);
    const float lengths[] = {0.0, 0.1, 1.0, 12.0, 48.0, 96.0};
    for (int l = 0; l < 6; l++) {
        float3 sum = float3(0.0);
        for (int i = 0; i < 24; i++) {
            float a = float(i) / 24.0, b = float(i + 1) / 24.0;
            sum += water_interval(sigma, a * a * lengths[l], (b * b - a * a) * lengths[l]);
        }
        out[l] = float4(sum, 0.0);
        out[6 + l] = float4(exp(-sigma * lengths[l]), 0.0);
    }
    for (int i = 0; i < 12; i++) {
        float y = float(i) / 11.0;
        out[12 + i] = float4(water_light_direction(float3(sqrt(1.0 - y * y), y, 0.0)), 0.0);
    }
    out[24] = float4(water_ray_length(float3(0.0), float3(0, 1, 0), 48.0, 6.0),
                     water_ray_length(float3(0.0), float3(0, -1, 0), 48.0, 6.0),
                     water_ray_length(float3(0.0), float3(1, 0, 0), 48.0, 6.0),
                     water_ray_length(float3(0, 0.1, 0), float3(0, 1, 0), 48.0, 6.0));
    out[25] = float4(water_phase(1.0), water_phase(0.0), water_phase(-1.0),
                     water_ray_length(float3(0, 7, 0), float3(0, 1, 0), 48.0, 6.0));
    // Direct-only fixture: dark shadows must not glow, and a zero-length ray adds nothing.
    for (int i = 0; i < 5; i++) {
        float dither = float(i) / 4.0;
        out[26 + i] = float4(march_water(frames[1], open, compare, clear, clear, float3(0), float3(1, 0, 0), 12.0, dither), 0);
        out[31 + i] = float4(march_water(frames[1], blocked, compare, clear, clear, float3(0), float3(1, 0, 0), 12.0, dither), 0);
    }
    out[36] = float4(march_water(frames[0], open, compare, clear, clear, float3(0), float3(1, 0, 0), 0.0, 0.5), 0);
    out[37] = float4(march_water(frames[2], open, compare, clear, clear, float3(0), float3(1, 0, 0), 12.0, 0.5), 0);
    out[38] = float4(march_water(frames[3], open, compare, clear, clear, float3(0), float3(1, 0, 0), 12.0, 0.5), 0);
    out[39] = float4(march_water(frames[0], blocked, compare, clear, clear, float3(0), float3(1, 0, 0), 12.0, 0.5), 0);
}

kernel void air_checks(constant FrameData *frames [[buffer(0)]], device float4 *out [[buffer(1)]],
                       depth2d<float> open [[texture(0)]], depth2d<float> blocked [[texture(1)]],
                       texture2d<float> clear [[texture(2)]], texture2d<float> red [[texture(3)]], texture2d<float> blue [[texture(4)]]) {
    constexpr sampler compare(filter::linear, address::clamp_to_edge, compare_func::less_equal);
    const float lengths[] = {0.0, 0.1, 20.0, 96.0, 180.0, 256.0};
    for (int l = 0; l < 6; l++) for (int d = 0; d < 5; d++) {
        AirVolume v = {float3(0.0), 1.0, 0.0};
        for (int i = 0; i < 40; i++) {
            float a = float(i) / 40.0, b = float(i + 1) / 40.0;
            float width = (b * b - a * a) * lengths[l];
            air_integrate(v, 0.003, width, a * a * lengths[l] + width * float(d) / 4.0, float3(1.0));
        }
        out[l * 5 + d] = float4(v.scattering, v.transmittance);
    }
    // Actual world-density march, including only the distant cascade beyond the near map.
    for (int i = 0; i < 4; i++) {
        AirVolume v = march_air(frames[i], open, open, compare, clear, clear, clear, open, float3(0), float3(1,0,0), 256.0, 0.5);
        out[30 + i] = float4(v.scattering, v.transmittance);
    }
    AirVolume shadow = march_air(frames[0], blocked, blocked, compare, clear, clear, clear, open, float3(0), float3(1,0,0), 256.0, 0.5);
    out[34] = float4(shadow.scattering, shadow.transmittance);
    AirVolume near = march_air(frames[0], open, open, compare, clear, clear, clear, open, float3(0), float3(1,0,0), 20.0, 0.5);
    out[35] = float4(near.scattering, near.transmittance);
    out[36] = float4(air_visibility(frames[0], blocked, open, compare, clear, clear, clear, float3(180,0,0)), 0);
    out[37] = float4(air_visibility(frames[0], open, blocked, compare, clear, clear, clear, float3(180,0,0)), 0);
    out[38] = float4(air_visibility(frames[0], open, open, compare, clear, clear, clear, float3(1000,0,0)), 0);
    out[39] = float4(air_density(frames[0], float3(12,64,17)), air_density(frames[0], float3(12,164,17)),
                     air_phase(1), air_phase(0));
    // Translating the camera and inverse-translating the relative origin must preserve the volume.
    AirVolume moved = march_air(frames[4], open, open, compare, clear, clear, clear, open, float3(-7,0,0), float3(1,0,0), 256.0, 0.5);
    out[40] = float4(moved.scattering, moved.transmittance);
    out[41] = float4(air_range(frames[0]), air_range(frames[5]), 0, 0);
    out[42] = float4(material_transmission(float4(0.8,0.1,0.05,0.3), float3(1), false), 0);
    out[43] = float4(material_transmission(float4(0.05,0.1,0.8,0.3), float3(1), false), 0);
    out[44] = float4(material_transmission(float4(1,1,1,0.3), float3(1), false), 0);
    out[45] = float4(material_transmission(float4(0.8,0.1,0.05,0.3), float3(1), true), 0);
    out[46] = float4(air_visibility(frames[0], open, open, compare, red, blue, clear, float3(0)), 0);
    out[47] = float4(air_visibility(frames[0], open, open, compare, red, blue, clear, float3(180,0,0)), 0);
    out[48] = float4(air_visibility(frames[0], open, open, compare, red, blue, clear, float3(0,0,-150)), 0);
    out[50] = float4(water_visibility(frames[0], open, compare, red, clear, float3(0), float3(0)), 0);
    out[51] = float4(water_visibility(frames[0], blocked, compare, red, clear, float3(0), float3(0)), 0);
    AirVolume tinted = march_air(frames[0], open, open, compare, red, red, clear, open, float3(0), float3(1,0,0), 20, 0.5);
    out[52] = float4(tinted.scattering, tinted.transmittance);
    AirVolume distant = march_air(frames[0], open, open, compare, red, blue, clear, open, float3(140,0,0), float3(1,0,0), 60, 0.5);
    AirVolume distantWhite = march_air(frames[0], open, open, compare, clear, clear, clear, open, float3(140,0,0), float3(1,0,0), 60, 0.5);
    out[53] = float4(distant.scattering, distant.transmittance);
    out[54] = float4(distantWhite.scattering, distantWhite.transmittance);
    out[55] = float4(march_water(frames[0], open, compare, red, clear, float3(0), float3(1,0,0), 12, 0.5), 0);
    out[57] = float4(material_transmission(float4(0.8,0.1,0.05,0.3), float3(0.6), false), 0);
    out[56] = float4(march_water(frames[0], open, compare, clear, clear, float3(0), float3(1,0,0), 12, 0.5), 0);
    // Compare lit/occluded volumes with a real coloured sky at hill height and in adverse conditions.
    for (int i = 6; i < 12; i++) {
        AirVolume lit = march_air(frames[i], open, open, compare, clear, clear, clear, open, float3(0), float3(1,0,0), 128, 0.5);
        AirVolume dark = march_air(frames[i], blocked, blocked, compare, clear, clear, clear, open, float3(0), float3(1,0,0), 128, 0.5);
        out[58+(i-6)*2] = float4(lit.scattering, lit.transmittance);
        out[59+(i-6)*2] = float4(dark.scattering, dark.transmittance);
    }
    const float3 views[] = {normalize(float3(1,0,1)), float3(0,0,1), float3(1,0,0)};
    for (int i = 0; i < 3; i++) {
        float length = i == 2 ? 10.0 : 128.0;
        AirVolume lit = march_air(frames[6], open, open, compare, clear, clear, clear, open, float3(0), views[i], length, 0.5);
        AirVolume dark = march_air(frames[6], blocked, blocked, compare, clear, clear, clear, open, float3(0), views[i], length, 0.5);
        out[70+i*2] = float4(lit.scattering, lit.transmittance);
        out[71+i*2] = float4(dark.scattering, dark.transmittance);
    }
    out[76] = float4(air_density(frames[6], float3(31,80,19)), air_density(frames[6], float3(31,96,19)),
                    air_density(frames[6], float3(31,128,19)), air_density(frames[6], float3(31,176,19)));
    out[77] = float4(air_phase(1), air_phase(0.7071), air_phase(0), air_phase(-1));


}

kernel void sky_checks(constant FrameData *frames [[buffer(0)]], device float4 *out [[buffer(1)]]) {
    const float3 directions[] = {float3(0,1,0), float3(1,0.02,0), float3(-1,0.02,0), float3(0,0.02,1), float3(0,-0.2,1), float3(0,0.5,1)};
    for (int f = 0; f < 7; f++) {
        for (int d = 0; d < 6; d++) out[f*8+d] = float4(atmosphere_sky(frames[f], directions[d]), 0);
        out[f*8+6] = float4(atmosphere_ambient(frames[f]), 0);
        out[f*8+7] = float4(atmosphere_shadow_tint(frames[f]), 0);
    }
    const float heights[] = {64,77,122,274};
    for (int i = 0; i < 4; i++) out[56+i] = float4(air_density_layers(frames[0], float3(31,heights[i],19)), 0);
    out[60] = float4(air_density_layers(frames[3], float3(31,64,19)), 0);
    out[61] = float4(air_density_layers(frames[1], float3(31,64,19)), 0);
    // Smooth field across cell boundaries, with genuine variation across different parts of the world.
    out[62] = float4(air_noise(float2(10.9999,7.25)), air_noise(float2(11.0001,7.25)), air_noise(float2(2.25,8.5)), air_noise(float2(73.25,19.5)));
    out[63] = float4(air_density_layers(frames[4], float3(31,64,19)), 0);
}

// GTAO: analytic slice integrals, a floor/wall corner, a pillar standing in front of a distant floor (halo test) and
// plane-predicted history lookups. Each probe pixel is averaged over 16 slice rotations, as the filters do over time.
kernel void ao_checks(constant FrameData &frame [[buffer(0)]], device float *out [[buffer(1)]],
                      constant int2 *probes [[buffer(2)]],
                      depth2d<float> corner [[texture(0)]], depth2d<float> pillar [[texture(1)]],
                      texture2d<float> plane [[texture(2)]]) {
    int at = 0;
    out[at++] = gtao_slice(0.0, -M_PI_2_F, M_PI_2_F);                 // open, normal facing the viewer
    out[at++] = gtao_slice(0.0, -M_PI_2_F, 0.0);                      // half the slice blocked
    out[at++] = gtao_slice(0.5, -M_PI_F, M_PI_F) / gtao_open(0.5);     // open, tilted normal
    out[at++] = gtao_slice(-0.9, -M_PI_F, M_PI_F) / gtao_open(-0.9);
    out[at++] = gtao_slice(0.5, -M_PI_F, 0.2) / gtao_open(0.5);        // tilted and partly blocked
    for (int p = 0; p < 6; p++) {
        float sum = 0.0;
        for (int k = 0; k < 16; k++) {
            float2 gradient;
            float2 noise = float2((float(k) + 0.5) / 16.0, fract(0.37 + float(k) * 0.618034));
            sum += p < 3 ? gtao(frame, corner, probes[p], noise, gradient) : gtao(frame, pillar, probes[p], noise, gradient);
        }
        out[at++] = sum / 16.0;
    }
    // Texels sit at i + 0.75; distances 10, 16, 22 lie on a plane with 3 blocks per full-resolution pixel, while the
    // last texel (50) belongs to something far behind.
    out[at++] = ao_lookup(plane, float2(1.25 / 4.0, 0.5), 13.0, float2(3.0, 0.0)).x;  // between texels 0 and 1
    out[at++] = ao_lookup(plane, float2(1.25 / 4.0, 0.5), 13.0, float2(0.0)).y;       // without the gradient: no match
    out[at++] = ao_lookup(plane, float2(3.25 / 4.0, 0.5), 25.0, float2(3.0, 0.0)).x;  // texel 3 is rejected
    out[at++] = ao_lookup(plane, float2(3.25 / 4.0, 0.5), 80.0, float2(3.0, 0.0)).y;  // nothing on this surface
}

// The fog's noise table, filled as air_noise_table_fragment fills it, and read back against the hash noise.
kernel void air_noise_table_fill(texture2d<float, access::write> table [[texture(0)]], uint2 gid [[thread_position_in_grid]]) {
    table.write(float4(air_hash(float2(gid)), 0.0, 0.0, 1.0), gid);
}

kernel void air_noise_table_checks(texture2d<float> table [[texture(0)]], device float4 *out [[buffer(1)]]) {
    AirTableNoise noise = {table};
    const float2 points[] = {float2(10.9999, 7.25), float2(11.0001, 7.25), float2(2.25, 8.5), float2(73.25, 19.5),
                             float2(200.6, 31.1), float2(0.3, 254.7), float2(127.5, 127.5), float2(36.02, 99.98)};
    for (int i = 0; i < 8; i++) {
        // Table, hash, and the table one period away (the table repeats; the hash does not, so only the table is compared).
        out[i] = float4(noise.sample(points[i]), air_noise(points[i]), noise.sample(points[i] + float2(AIR_NOISE_TABLE, -AIR_NOISE_TABLE)), 0.0);
    }
}
