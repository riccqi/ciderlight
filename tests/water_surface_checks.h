// Wave normals must be real (not a sub-degree wobble), animated, calm and rougher at a distance;
// rain must add ripples; the glint and absorption must behave physically.
static void checkWaterSurface(id<MTLDevice> device) {
    NSString *s = [@"#include <metal_stdlib>\nusing namespace metal;\n" stringByAppendingString:source(@"water_surface.metal")];
    s = [s stringByAppendingString:[NSString stringWithContentsOfFile:@"tests/water_surface_checks.metal" encoding:NSUTF8StringEncoding error:NULL]];
    id<MTLLibrary> lib = compile(device, s);
    NSError *error = nil;
    id<MTLComputePipelineState> state = [device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"water_surface_checks"] error:&error];
    require(state != nil, error.description);
    id<MTLBuffer> output = [device newBufferWithLength:6 * sizeof(simd_float4) options:MTLResourceStorageModeShared];
    id<MTLCommandBuffer> cb = [[device newCommandQueue] commandBuffer];
    id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
    [enc setComputePipelineState:state];
    [enc setBuffer:output offset:0 atIndex:0];
    [enc dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];
    [enc endEncoding]; [cb commit]; [cb waitUntilCompleted];
    require(cb.status == MTLCommandBufferStatusCompleted, cb.error.description);
    simd_float4 *v = output.contents;
    require(v[0].w == 1 && v[1].w == 1, @"Wave normals are not finite");
    require(v[0].x > 0.04f && v[0].y < 0.6f, [NSString stringWithFormat:@"Near wave slope out of range: mean %f max %f", v[0].x, v[0].y]);
    require(v[1].x < v[0].x * 0.15f, @"Distant water is not calmer than near water (aliasing)");
    require(v[1].z > v[0].z + 0.001f, @"Filtered wave detail is not carried into roughness");
    require(v[2].x > 0.01f, @"Waves do not animate");
    require(v[2].y > v[0].x * 1.15f, @"Rain does not roughen the water");
    require(v[2].z > 0.005f && v[2].w == 0, @"Rain ripples missing near, or aliasing at a distance");
    require(v[3].x > 1 && v[3].y < v[3].x * 0.05f && v[3].z == 0 && v[3].w < v[3].x, @"Water glint lobe regression");
    for (int j = 0; j < 3; j++) require(v[4][j] > 0 && isfinite(v[4][j]) && v[5][j] > 0, @"Absorption must be positive");
    require(v[4].x > v[4].y && v[4].y > v[4].z, @"Plains water must absorb red first, then green");
    require(fabsf(v[5].x - v[5].z) < 0.01f, @"Neutral water should absorb nearly evenly");
    printf("Water surface checks pass: wave slope near %.3f / far %.4f, rain %.3f, ripples %.3f, glint peak %.1f.\n",
           v[0].x, v[1].x, v[2].y, v[2].z, v[3].x);
}
