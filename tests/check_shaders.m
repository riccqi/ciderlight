// Run from the repository root: python3 tests/check_shaders.py
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <math.h>
#import <simd/simd.h>

static void require(BOOL condition, NSString *message) {
    if (!condition) { fprintf(stderr, "%s\n", message.UTF8String); exit(1); }
}

static NSString *source(NSString *name) {
    NSString *path = [@"src/main/resources/assets/honeycrisp/shaders" stringByAppendingPathComponent:name];
    NSString *s = [NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:NULL];
    require(s != nil, [@"Cannot read " stringByAppendingString:path]);
    for (NSString *header in @[@"frame.metal", @"shadow.metal", @"water.metal", @"reflection.metal", @"water_surface.metal", @"atmosphere.metal", @"foliage.metal", @"ao.metal"]) {
        NSString *include = [NSString stringWithFormat:@"#include \"%@\"", header];
        if ([s containsString:include]) s = [s stringByReplacingOccurrencesOfString:include withString:source(header)];
    }
    return s;
}

static id<MTLLibrary> compile(id<MTLDevice> device, NSString *s) {
    NSError *error = nil;
    MTLCompileOptions *options = [MTLCompileOptions new];
    options.languageVersion = MTLLanguageVersion3_0;
    options.mathMode = MTLMathModeFast;
    id<MTLLibrary> lib = [device newLibraryWithSource:s options:options error:&error];
    require(lib != nil, error.description);
    return lib;
}

static id<MTLTexture> texture(id<MTLDevice> device, MTLPixelFormat format, NSUInteger width) {
    MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:width height:1 mipmapped:NO];
    d.storageMode = MTLStorageModeShared;
    d.usage = MTLTextureUsageShaderRead;
    return [device newTextureWithDescriptor:d];
}

static void checkBobRays(id<MTLDevice> device, id<MTLLibrary> lib) {
    enum { frameCount = 32, rayCount = frameCount * 3 };
    float frames[frameCount][444];
    memset(frames, 0, sizeof(frames));
    simd_float3 origins[frameCount], directions[rayCount];
    const simd_float2 uvs[] = {{0.5f, 0.5f}, {0.2f, 0.8f}, {0.95f, 0.1f}};
    for (int i = 0; i < frameCount; i++) {
        float phase = (i % 16) * 2.0f * M_PI / 16.0f;
        float near = i < 16 ? 0.05f : 0.2f;
        simd_float4x4 t = matrix_identity_float4x4;
        t.columns[3] = (simd_float4){0.05f * sinf(phase), -0.1f * fabsf(cosf(phase)), 0, 1};
        float z = 0.05f * sinf(phase), x = 0.08f * fabsf(cosf(phase - 0.2f));
        simd_float4x4 rz = matrix_identity_float4x4, rx = matrix_identity_float4x4;
        rz.columns[0] = (simd_float4){cosf(z), sinf(z), 0, 0};
        rz.columns[1] = (simd_float4){-sinf(z), cosf(z), 0, 0};
        rx.columns[1] = (simd_float4){0, cosf(x), sinf(x), 0};
        rx.columns[2] = (simd_float4){0, -sinf(x), cosf(x), 0};
        simd_float4x4 view = matrix_identity_float4x4;
        view.columns[0] = (simd_float4){cosf(0.37f), 0, -sinf(0.37f), 0};
        view.columns[2] = (simd_float4){sinf(0.37f), 0, cosf(0.37f), 0};
        simd_float4x4 transform = simd_mul(t, simd_mul(rz, simd_mul(rx, view)));
        simd_float4x4 inverseTransform = simd_inverse(transform);
        simd_float4x4 projection = {.columns = {
            {1.2f, 0, 0, 0}, {0, 1.8f, 0, 0}, {0, 0, 0, -1}, {0, 0, near, 0}
        }};
        if (i % 16 >= 8) { // Cover finite as well as infinite reverse-Z projections.
            projection.columns[2].z = near / (512.0f - near);
            projection.columns[3].z = near * 512.0f / (512.0f - near);
        }
        simd_float4x4 vp = simd_mul(projection, transform), inv = simd_inverse(vp);
        memcpy(frames[i] + 48, &inv, sizeof(inv));
        memcpy(frames[i] + 64, &vp, sizeof(vp));
        origins[i] = simd_mul(inverseTransform, (simd_float4){0, 0, 0, 1}).xyz;
        for (int j = 0; j < 3; j++) {
            simd_float4 d = {(2 * uvs[j].x - 1) / 1.2f, (2 * uvs[j].y - 1) / 1.8f, -1, 0};
            directions[i * 3 + j] = simd_normalize(simd_mul(inverseTransform, d).xyz);
        }
    }
    NSError *error = nil;
    id<MTLComputePipelineState> state = [device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"bob_ray_checks"] error:&error];
    require(state != nil, error.description);
    id<MTLBuffer> uniforms = [device newBufferWithBytes:frames length:sizeof(frames) options:MTLResourceStorageModeShared];
    id<MTLBuffer> output = [device newBufferWithLength:rayCount * 3 * sizeof(simd_float4) options:MTLResourceStorageModeShared];
    id<MTLCommandBuffer> cb = [[device newCommandQueue] commandBuffer];
    id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
    [enc setComputePipelineState:state];
    [enc setBuffer:uniforms offset:0 atIndex:0]; [enc setBuffer:output offset:0 atIndex:1];
    [enc dispatchThreads:MTLSizeMake(rayCount, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
    [enc endEncoding]; [cb commit]; [cb waitUntilCompleted];
    require(cb.status == MTLCommandBufferStatusCompleted, cb.error.description);
    simd_float4 *values = output.contents;
    float worstLegacyDot = 1;
    for (int i = 0; i < rayCount; i++) {
        require(simd_distance(values[i * 3].xyz, origins[i / 3]) < 0.00001f, @"Incorrect effective eye during view bob");
        require(simd_distance(values[i * 3 + 1].xyz, directions[i]) < 0.00001f, @"View bob changed the reconstructed ray angle");
        require(simd_length(values[i * 3 + 2].xy) < 0.00001f, @"Fog ray left its pixel during view bob");
        worstLegacyDot = fminf(worstLegacyDot, values[i * 3 + 2].w);
        if (i >= 48) require(simd_distance(values[i * 3 + 1].xyz, values[(i - 48) * 3 + 1].xyz) < 0.00001f,
                            @"Fog direction depends on the near plane");
    }
    require(worstLegacyDot < 0.9f, @"The regression fixture did not exercise the original view-bob bug");
    printf("96 view-bob ray cases pass; the old formula deviates by up to %.1f degrees in this fixture.\n",
           acosf(worstLegacyDot) * 180 / M_PI);
}

static void checkWater(id<MTLDevice> device, id<MTLLibrary> lib) {
    float frames[4][444] = {0};
    frames[0][0] = frames[0][5] = frames[0][10] = 0.001f;
    frames[0][14] = 0.2f; frames[0][15] = 1;
    frames[0][20] = 0.8f; frames[0][21] = 0.6f; frames[0][23] = 1;
    frames[0][24] = frames[0][25] = frames[0][26] = 1;
    frames[0][31] = 1; frames[0][88] = 1;
    frames[0][412] = 1; frames[0][413] = 6; frames[0][414] = 1; frames[0][415] = 1;
    for (int i = 1; i < 4; i++) memcpy(frames[i], frames[0], sizeof(frames[0]));
    frames[1][414] = 0; // direct only
    memcpy(frames[2], frames[1], sizeof(frames[1])); frames[2][31] = 0; // invalid shadow map
    memcpy(frames[3], frames[1], sizeof(frames[1])); frames[3][90] = 1; // moon
    NSError *error = nil;
    id<MTLComputePipelineState> state = [device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"water_checks"] error:&error];
    require(state != nil, error.description);
    id<MTLBuffer> uniforms = [device newBufferWithBytes:frames length:sizeof(frames) options:MTLResourceStorageModeShared];
    id<MTLBuffer> output = [device newBufferWithLength:40 * sizeof(simd_float4) options:MTLResourceStorageModeShared];
    id<MTLTexture> open = texture(device, MTLPixelFormatDepth32Float, 1);
    id<MTLTexture> blocked = texture(device, MTLPixelFormatDepth32Float, 1);
    id<MTLTexture> clear = texture(device, MTLPixelFormatRGBA32Float, 1);
    float one = 1, zero = 0, white[] = {1,1,1,1};
    [open replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0 withBytes:&one bytesPerRow:4];
    [blocked replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0 withBytes:&zero bytesPerRow:4];
    [clear replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0 withBytes:white bytesPerRow:16];
    id<MTLCommandBuffer> cb = [[device newCommandQueue] commandBuffer];
    id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
    [enc setComputePipelineState:state];
    [enc setBuffer:uniforms offset:0 atIndex:0]; [enc setBuffer:output offset:0 atIndex:1];
    [enc setTexture:open atIndex:0]; [enc setTexture:blocked atIndex:1]; [enc setTexture:clear atIndex:2];
    [enc dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];
    [enc endEncoding]; [cb commit]; [cb waitUntilCompleted];
    require(cb.status == MTLCommandBufferStatusCompleted, cb.error.description);
    simd_float4 *v = output.contents;
    float lengths[] = {0,0.1f,1,12,48,96}, sigma[] = {0.18f,0.075f,0.045f};
    for (int i = 0; i < 40; i++) for (int j = 0; j < 4; j++) require(isfinite(v[i][j]) && v[i][j] >= 0, @"Invalid water radiance");
    for (int i = 0; i < 6; i++) for (int j = 0; j < 3; j++) {
        require(fabsf(v[i][j] - (1 - expf(-sigma[j] * lengths[i])) / sigma[j]) < 0.0001f, @"Water interval lost energy");
        require(fabsf(v[6+i][j] - expf(-sigma[j] * lengths[i])) < 0.00001f, @"Water extinction is incorrect");
        if (i > 0) require(v[6+i][j] < v[5+i][j], @"Water does not attenuate with distance");
    }
    for (int i = 1; i < 6; i++) require(v[6+i].x < v[6+i].y && v[6+i].y < v[6+i].z, @"Red light should attenuate fastest");
    for (int i = 0; i < 12; i++) {
        float y = i / 11.0f;
        require(fabsf(simd_length(v[12+i].xyz) - 1) < 0.00001f, @"Refracted light not normalized");
        require(fabsf(v[12+i].x * 1.333f - sqrtf(1-y*y)) < 0.00001f, @"Snell's law regression");
        require(v[12+i].y >= y - 0.00001f, @"Light should refract towards the water normal");
    }
    require(simd_distance(v[24], (simd_float4){6,48,48,5.9f}) < 0.00001f, @"Ray did not stop at the water surface");
    require(v[25].x > v[25].y && v[25].y > v[25].z && v[25].w == 0, @"Water phase or boundary regression");
    for (int i = 0; i < 5; i++) {
        require(simd_length(v[26+i].xyz) > 0.01f, @"Open water received no sun scattering");
        require(simd_length(v[31+i].xyz) == 0, @"Sun scattering leaked through an opaque blocker");
    }
    require(simd_length(v[36].xyz) == 0 && simd_length(v[37].xyz) == 0, @"Zero-length/invalid shadow map regression");
    require(simd_length(v[38].xyz) < simd_length(v[28].xyz) * 0.15f, @"Moonlight is too strong underwater");
    require(simd_length(v[39].xyz) > 0, @"Ambient water scattering lost in direct shadow");
    puts("Water checks pass: spectral extinction, integrated energy, refraction, boundary clipping, blocked rays, and moonlight.");
}

#include "reflection_checks.h"
#include "air_checks.h"
#include "sky_checks.h"
#include "water_surface_checks.h"
#include "ao_checks.h"

int main(void) { @autoreleasepool {
    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    require(device != nil, @"No Metal device available");
    NSString *terrain = [@"#define IDX_GLOBALS 0\n#define IDX_PROJECTION 1\n#define IDX_TERRAIN 2\n#define IDX_FOG 3\n#define IDX_LIGHTMAP 4\n#define IDX_ATLAS 5\n" stringByAppendingString:source(@"terrain.metal")];
    compile(device, terrain);
    compile(device, [@"#define ALPHA_CUTOUT 0.5\n" stringByAppendingString:terrain]);
    compile(device, [@"#define MC_REFLECT\n" stringByAppendingString:terrain]);
    compile(device, [@"#define ALPHA_CUTOUT 0.1\n#define MC_REFLECT 1\n" stringByAppendingString:terrain]); // production translucent
    NSString *waving = [@"#define MC_VERTEX_STRIDE 28\n#define MC_UV_OFFSET 16\n#define MC_POS_OFFSET 0\n" stringByAppendingString:terrain];
    compile(device, waving);
    compile(device, [@"#define ALPHA_CUTOUT 0.5\n" stringByAppendingString:waving]);
    compile(device, [@"#define MC_REFLECT\n" stringByAppendingString:waving]);
    compile(device, [@"#define MC_WAVING_DEBUG 1\n" stringByAppendingString:waving]);
    compile(device, [@"#define ALPHA_CUTOUT 0.1\n#define MC_REFLECT 1\n" stringByAppendingString:waving]);
    // Low quality (Quality.LOW): shorter fog, water and reflection marches.
    compile(device, [@"#define MC_QUALITY_LOW 1\n#define ALPHA_CUTOUT 0.1\n#define MC_REFLECT 1\n" stringByAppendingString:terrain]);
    compile(device, [@"#define MC_QUALITY_LOW 1\n" stringByAppendingString:source(@"composite.metal")]);
    compile(device, source(@"entity.metal"));
    compile(device, source(@"sky.metal"));
    NSString *checks = [NSString stringWithContentsOfFile:@"tests/shader_checks.metal" encoding:NSUTF8StringEncoding error:NULL];
    id<MTLLibrary> lib = compile(device, [source(@"composite.metal") stringByAppendingString:checks]);
    NSError *error = nil;
    id<MTLComputePipelineState> state = [device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"shader_checks"] error:&error];
    require(state != nil, error.description);
    // Shared FrameData layout: identity shadow matrix and projection, valid histories and a fixed test weight.
    float frame[444] = {0};
    for (int i = 0; i < 4; i++) { frame[i * 5] = 1; frame[96 + i * 5] = 1; }
    frame[10] = 0.001f; frame[14] = 0.2f; // Keep the entire 96-block test ray inside the shadow volume.
    frame[30] = 1.0f / 4096; frame[31] = 1;
    frame[114] = 1; frame[115] = 0.8f;
    id<MTLBuffer> uniforms = [device newBufferWithBytes:frame length:sizeof(frame) options:MTLResourceStorageModeShared];
    id<MTLBuffer> output = [device newBufferWithLength:256 * sizeof(float) options:MTLResourceStorageModeShared];
    // No opaque blocker. Translucent colour has two texels, one in front of the receiver, one behind it.
    id<MTLTexture> depth = texture(device, MTLPixelFormatDepth32Float, 1);
    float depthValue = 1;
    [depth replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0 withBytes:&depthValue bytesPerRow:4];
    id<MTLTexture> color = texture(device, MTLPixelFormatRGBA32Float, 2);
    float colors[] = {0.2f,0.4f,0.6f,0.5f, 0.3f,0.5f,0.7f,0.8f};
    [color replaceRegion:MTLRegionMake2D(0,0,2,1) mipmapLevel:0 withBytes:colors bytesPerRow:sizeof(colors)];
    id<MTLTexture> clear = texture(device, MTLPixelFormatRGBA32Float, 1);
    float white[] = {1,1,1,1};
    [clear replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0 withBytes:white bytesPerRow:sizeof(white)];
    id<MTLTexture> history = texture(device, MTLPixelFormatRGBA32Float, 2);
    float historyValues[] = {0.2f,0.2f,0.2f,0.5f, 0.8f,0.8f,0.8f,4.0f};
    [history replaceRegion:MTLRegionMake2D(0,0,2,1) mipmapLevel:0 withBytes:historyValues bytesPerRow:sizeof(historyValues)];
    id<MTLTexture> fog = texture(device, MTLPixelFormatRGBA32Float, 2);
    float fogValues[] = {0.2f,0.2f,0.2f,0.2f, 0.8f,0.8f,0.8f,0.8f};
    [fog replaceRegion:MTLRegionMake2D(0,0,2,1) mipmapLevel:0 withBytes:fogValues bytesPerRow:sizeof(fogValues)];
    id<MTLCommandBuffer> cb = [[device newCommandQueue] commandBuffer];
    id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
    [enc setComputePipelineState:state];
    [enc setBuffer:uniforms offset:0 atIndex:0];
    [enc setBuffer:output offset:0 atIndex:1];
    [enc setTexture:depth atIndex:0]; [enc setTexture:color atIndex:1];
    [enc setTexture:clear atIndex:2]; [enc setTexture:history atIndex:3]; [enc setTexture:fog atIndex:4];
    [enc dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];
    [enc endEncoding]; [cb commit]; [cb waitUntilCompleted];
    require(cb.status == MTLCommandBufferStatusCompleted, cb.error.description);
    float *values = output.contents;
    int n = 0;
    float expected[] = {1, 0.2f, 0.6f, 1, 0.3f, 0.26f, 0.5f};
    for (int i = 0; i < 7; i++) {
        require(fabsf(values[n + i] - expected[i]) < 0.0001f,
            [NSString stringWithFormat:@"Transmission/history check %d: got %f, expected %f", i, values[n+i], expected[i]]);
    }
    n += 7;
    for (int level = 0; level <= 15; level++) {
        require(fabsf(values[n + level] - level / 15.0f) < 0.005f, @"Entity light RGBA8 round-trip regression");
        if (level > 0) require(values[n + level] > values[n + level - 1], @"Entity light must increase monotonically");
    }
    require(values[n + 16] == 0, @"Unknown light sentinel regression");
    n += 17;
    // Under water (the last four) a depth with no matching neighbour borrows the closest one, scaled to its ray.
    float fogExpected[] = {0.2f, 0.8f, 0.0f, 0.0f, 0.2f, 0.8f, 0.2f, 0.0f};
    for (int i = 0; i < 8; i++) {
        require(fabsf(values[n + i] - fogExpected[i]) < 0.0001f, @"Fog upsampling crossed a depth boundary");
    }
    checkBobRays(device, lib);
    checkWater(device, lib);
    checkAir(device, lib);
    checkSky(device, lib);
    checkAirNoiseTable(device, lib);
    checkAmbientOcclusion(device, lib);
    checkReflections(device, @"");
    checkReflections(device, @"#define MC_QUALITY_LOW 1\n");
    checkWaterSurface(device);
    puts("All 11 Metal shader variants compile (5 with waving foliage); 32 lighting checks and 96 view-bob ray cases pass.");
    return 0;
} }
