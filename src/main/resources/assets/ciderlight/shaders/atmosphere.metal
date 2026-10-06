// World-space mist shared by the air march and its numerical checks.
static float air_range(constant FrameData &frame) {
    return min(max(frame.airParams.x, 32.0), max(frame.fogParams.y, 32.0) * 0.98);
}

// Smooth, bounded value noise: world coordinates anchor the banks; slow wind is their only motion.
static float air_hash(float2 p) {
    float3 q = fract(float3(p.x, p.y, p.x) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

static float air_noise(float2 p) {
    float2 cell = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(air_hash(cell), air_hash(cell + float2(1,0)), f.x),
               mix(air_hash(cell + float2(0,1)), air_hash(cell + float2(1,1)), f.x), f.y);
}

// The fog's noise straight from the hash: the reference, and what the numerical checks use.
struct AirHashNoise {
    float sample(float2 p) const { return air_noise(p); }
};

// The same noise from a table of air_hash over AIR_NOISE_TABLE cells (air_noise_table_fragment), repeating every
// AIR_NOISE_TABLE cells (over 9000 blocks for the finest octave). One filtered texture read replaces four hashes: the
// smoothstep is applied to the coordinate, so the hardware's bilinear filter interpolates exactly as air_noise does.
constant float AIR_NOISE_TABLE = 256.0;

struct AirTableNoise {
    texture2d<float> table;
    float sample(float2 p) const {
        constexpr sampler repeat(filter::linear, address::repeat);
        float2 cell = floor(p), f = fract(p);
        f = f * f * (3.0 - 2.0 * f);
        return table.sample(repeat, (cell + 0.5 + f) / AIR_NOISE_TABLE, level(0)).r;
    }
};

// What the density needs from the frame alone, worked out once per ray rather than at every step.
struct AirMedium {
    float3 scale;  // ground mist, veil and haze densities at the base height
    float2 drift;  // wind offset of the banks
    float base;    // mist base height
};

static AirMedium air_medium(constant FrameData &frame) {
    float sunHeight = frame.solarDir.w > 0.5 ? frame.solarDir.y : frame.sunDir.y;
    float dawn = exp(-pow((sunHeight - 0.08) / 0.28, 2.0));
    AirMedium medium;
    medium.scale = float3(0.0026 * (0.55 + 0.75 * dawn), 0.0022, 0.00038) * frame.fogParams.z
                 * smoothstep(0.1, 0.6, frame.fogParams.x) * frame.airParams.z;
    medium.drift = float2(frame.params.x * 0.07, frame.params.x * 0.035);
    medium.base = frame.airParams.y;
    return medium;
}

template <typename Noise>
static float3 air_density_layers(AirMedium medium, Noise noise, float3 world) {
    float height = max(world.y - medium.base, 0.0);
    float2 p = world.xz + medium.drift;
    float broad = noise.sample(p / 110.0), detail = noise.sample(p / 37.0 + float2(19.7, -6.3));
    float banks = smoothstep(0.20, 0.78, broad * 0.72 + detail * 0.28);
    // Keep a continuous scattering medium through treetops and hills, not only at sea level.
    // Banks shape the low mist; the broader veil keeps shafts visible between those banks.
    float ground = exp(-height / 22.0) * (0.22 + 0.78 * banks);
    float veil = exp(-height / 72.0) * (0.65 + 0.35 * broad);
    float haze = exp(-height / 210.0);
    return float3(ground, veil, haze) * medium.scale;
}

// An inexpensive directional atmosphere palette, inspired by scattering rather than an HDR atmosphere solver.
// Uses the actual solar elevation, so twilight stays continuous when the shadow light switches to the moon.
static float3 atmosphere_sky(constant FrameData &frame, float3 direction) {
    if (frame.solarDir.w < 0.5) return frame.skyColor.rgb;
    float3 V = normalize(direction), sun = frame.solarDir.xyz;
    float day = smoothstep(-0.16, 0.16, sun.y);
    float twilight = exp(-pow((sun.y + 0.015) / 0.18, 2.0));
    float horizon = exp(-max(V.y, 0.0) * 4.8);
    // A hazy daytime sky: pale blue overhead, near white over a broad band above the horizon.
    float haze = pow(1.0 - saturate(V.y), 1.6);
    // A low sun (morning, evening) washes the whole sky towards lavender and pink, not only the horizon.
    float low = 1.0 - smoothstep(0.02, 0.9, sun.y);
    float towardSun = saturate(dot(normalize(float3(V.x, 0.03, V.z)), normalize(float3(sun.x, 0.03, sun.z))));
    float3 dayZenith = mix(float3(0.35, 0.50, 0.74), float3(0.47, 0.49, 0.61), low);
    float3 dayHorizon = mix(float3(0.70, 0.75, 0.82), float3(0.72, 0.66, 0.70), low);
    float3 night = mix(float3(0.004, 0.009, 0.026), float3(0.028, 0.040, 0.075), horizon);
    float3 daylight = mix(dayZenith, dayHorizon, haze);
    // Towards the sun the haze glows bright and warm.
    daylight += float3(0.12, 0.085, 0.05) * pow(towardSun, 2.0) * haze * (0.35 + 0.65 * low);
    float3 colour = mix(night, daylight, day);
    float sunset = twilight * horizon * (0.12 + 0.88 * pow(towardSun, 3.0));
    colour = mix(colour, float3(0.96, 0.42, 0.22), sunset * 0.8);
    // A softer violet band opposite the sun instead of a uniformly orange sky.
    colour += float3(0.075, 0.017, 0.065) * twilight * horizon * pow(1.0 - towardSun, 2.0);
    float forward = pow(saturate(dot(V, sun)), 18.0);
    colour += float3(0.13, 0.09, 0.045) * forward * day * (1.0 - frame.params.y);
    float rain = saturate(frame.params.y);
    float3 overcast = mix(float3(0.014, 0.018, 0.026), float3(0.40, 0.43, 0.47), day);
    overcast *= mix(0.82, 1.15, horizon);
    return max(mix(colour, overcast, rain * 0.88), 0.0);
}

static float3 atmosphere_ambient(constant FrameData &frame) {
    if (frame.solarDir.w < 0.5) return frame.skyColor.rgb * mix(0.18, 0.65, frame.lightParams.x);
    // A hemisphere approximation: blue overhead fill mixed with light from the warm/cool horizon.
    return (atmosphere_sky(frame, float3(0,1,0)) * 0.60
          + atmosphere_sky(frame, float3(1,0.12,0)) * 0.20
          + atmosphere_sky(frame, float3(-1,0.12,0)) * 0.20) * 0.70;
}

static float3 atmosphere_shadow_tint(constant FrameData &frame) {
    if (frame.solarDir.w < 0.5) return float3(0.80, 0.88, 1.08);
    float3 ambient = atmosphere_ambient(frame);
    float luminance = max(dot(ambient, float3(0.2126,0.7152,0.0722)), 0.02);
    return mix(float3(1.0), clamp(ambient / luminance, 0.55, 1.35), 0.55);
}

static float air_phase(float cosine) {
    // A forward lobe for sun-facing shafts plus a broad lobe for oblique views through mist.
    // The mixture widens the visible beam without an excessive spike looking straight at the sun.
    float2 g = float2(0.5, 0.25);
    float2 hg = (1.0 - g * g) / pow(max(1.0 + g * g - 2.0 * g * clamp(cosine, -1.0, 1.0), 0.01), 1.5);
    return 0.10 + 0.16 * dot(hg, float2(0.55, 0.45));
}

struct AirVolume {
    float3 scattering;
    float transmittance;
    float moment; // opacity-weighted distance for volume history reprojection
};

static void air_integrate(thread AirVolume &volume, float density, float width, float distance, float3 incident) {
    float segment = exp(-max(density * width, 0.0));
    float weight = volume.transmittance * (1.0 - segment);
    volume.scattering += incident * weight;
    volume.moment += distance * weight;
    volume.transmittance *= segment;
}
