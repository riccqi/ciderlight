static void checkSky(id<MTLDevice> device, id<MTLLibrary> lib) {
    float f[7][444] = {0};
    f[0][441] = f[0][443] = 1;
    f[0][92] = f[0][94] = f[0][438] = 1; f[0][437] = 64;
    for (int i = 1; i < 7; i++) memcpy(f[i], f[0], sizeof(f[0]));
    f[1][440] = sqrtf(1-0.02f*0.02f); f[1][441] = 0.02f;
    f[2][441] = -1;
    memcpy(f[3], f[1], sizeof(f[1])); f[3][29] = 1; f[3][94] = 2.5f;
    memcpy(f[4], f[1], sizeof(f[1])); f[4][20] = -1; f[4][90] = 1;
    f[5][443] = 0; f[5][80] = 0.11f; f[5][81] = 0.22f; f[5][82] = 0.33f;
    memcpy(f[6], f[1], sizeof(f[1])); f[6][441] += 0.0001f;
    NSError *error = nil;
    id<MTLComputePipelineState> state = [device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"sky_checks"] error:&error];
    require(state != nil, error.description);
    id<MTLBuffer> uniforms = [device newBufferWithBytes:f length:sizeof(f) options:MTLResourceStorageModeShared];
    id<MTLBuffer> output = [device newBufferWithLength:64*sizeof(simd_float4) options:MTLResourceStorageModeShared];
    id<MTLCommandBuffer> cb = [[device newCommandQueue] commandBuffer];
    id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
    [enc setComputePipelineState:state]; [enc setBuffer:uniforms offset:0 atIndex:0]; [enc setBuffer:output offset:0 atIndex:1];
    [enc dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];
    [enc endEncoding]; [cb commit]; [cb waitUntilCompleted];
    require(cb.status == MTLCommandBufferStatusCompleted, cb.error.description);
    simd_float4 *v = output.contents;
    for (int i = 0; i < 64; i++) for (int j = 0; j < 4; j++) require(isfinite(v[i][j]) && v[i][j] >= 0, @"Nonfinite/negative sky or layered fog");
    require(v[0].z > v[0].x * 1.25f && v[1].x > v[0].x, @"Day sky must be blue overhead and lighter at the horizon");
    require(v[9].x > v[9].z && v[9].x > v[10].x, @"Sunrise horizon lost its directional warm colour");
    require(simd_length(v[16].xyz) < simd_length(v[0].xyz)*0.15f && simd_length(v[16].xyz) > 0, @"Night sky is overbright or completely black");
    require(fabsf(v[25].x-v[25].z) < fabsf(v[9].x-v[9].z), @"Rain did not soften the sunset palette");
    for (int i=0; i<6; i++) {
        require(simd_distance(v[8+i], v[32+i]) < 0.00001f, @"Sky jumps when shadow light switches to moon");
        require(simd_distance(v[40+i].xyz, (simd_float3){0.11f,0.22f,0.33f}) < 0.00001f, @"Vanilla atmosphere fallback changed");
        require(simd_distance(v[8+i], v[48+i]) < 0.002f, @"Sky has a discontinuity near sunrise");
    }
    for (int i=57;i<60;i++) for(int j=0;j<3;j++) require(v[i][j] < v[i-1][j], @"A fog layer grows with altitude");
    require(v[57].x/v[56].x < v[57].y/v[56].y && v[57].y/v[56].y < v[57].z/v[56].z, @"Ground mist, veil and haze do not have separate heights");
    require(simd_distance(v[60], v[61]*2.5f) < 0.00001f, @"Rain did not increase mist density");
    require(fabsf(v[62].x-v[62].y) < 0.0001f && fabsf(v[62].z-v[62].w) > 0.01f, @"Mist banks are discontinuous or spatially flat");
    require(simd_distance(v[61],v[63]) < 0.00001f, @"Fog density changes abruptly when sun/moon shadows switch");
    puts("64 sky/layer cases pass: directional sunrise, blue day, dark night, rain, smooth transitions, fallbacks and independent height layers.");
}

static void checkAirNoiseTable(id<MTLDevice> device, id<MTLLibrary> lib) {
    NSError *error = nil;
    id<MTLComputePipelineState> fill = [device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"air_noise_table_fill"] error:&error];
    require(fill != nil, error.description);
    id<MTLComputePipelineState> check = [device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"air_noise_table_checks"] error:&error];
    require(check != nil, error.description);
    // The format the game uses for the table (EXTINCTION_FORMAT).
    MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR16Float width:256 height:256 mipmapped:NO];
    d.usage = MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite;
    id<MTLTexture> table = [device newTextureWithDescriptor:d];
    id<MTLBuffer> output = [device newBufferWithLength:8 * sizeof(simd_float4) options:MTLResourceStorageModeShared];
    id<MTLCommandBuffer> cb = [[device newCommandQueue] commandBuffer];
    id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
    [enc setComputePipelineState:fill]; [enc setTexture:table atIndex:0];
    [enc dispatchThreads:MTLSizeMake(256, 256, 1) threadsPerThreadgroup:MTLSizeMake(16, 16, 1)];
    [enc endEncoding];
    enc = [cb computeCommandEncoder];
    [enc setComputePipelineState:check]; [enc setTexture:table atIndex:0]; [enc setBuffer:output offset:0 atIndex:1];
    [enc dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
    [enc endEncoding]; [cb commit]; [cb waitUntilCompleted];
    require(cb.status == MTLCommandBufferStatusCompleted, cb.error.description);
    simd_float4 *v = output.contents;
    float worst = 0;
    for (int i = 0; i < 8; i++) {
        worst = fmaxf(worst, fabsf(v[i].x - v[i].y));
        require(fabsf(v[i].x - v[i].z) < 0.0001f, @"Fog noise table does not repeat seamlessly");
    }
    require(worst < 0.01f, @"Fog noise table differs from the hash noise");
    require(fabsf(v[0].x - v[1].x) < 0.001f, @"Fog noise table is discontinuous across a cell edge");
    printf("Fog noise table matches the hash noise (largest difference %.4f) and repeats seamlessly.\n", worst);
}
