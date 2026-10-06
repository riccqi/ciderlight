static void checkAir(id<MTLDevice> device, id<MTLLibrary> lib) {
    float f[12][444] = {0};
    f[0][0] = f[0][5] = 1.0f / 112; f[0][10] = 0.001f; f[0][14] = 0.2f; f[0][15] = 1;
    f[0][416] = f[0][421] = 1.0f / 272; f[0][426] = 0.001f; f[0][430] = 0.2f; f[0][431] = 1;
    f[0][435] = 1; f[0][436] = 256; f[0][437] = 64; f[0][438] = 1;
    f[0][20] = 0.8f; f[0][21] = 0.6f; f[0][23] = 1;
    f[0][24] = f[0][25] = f[0][26] = 1; f[0][31] = 1;
    f[0][85] = 64; f[0][88] = 1; f[0][92] = 1; f[0][93] = 512; f[0][94] = 1;
    for (int i = 1; i < 6; i++) memcpy(f[i], f[0], sizeof(f[0]));
    f[1][31] = 0; // far cascade alone must illuminate distant mist
    f[2][31] = f[2][435] = 0; // no map must not generate direct light
    f[3][438] = 0; // disabled dimension
    f[4][84] = f[4][16] = f[4][432] = 7; // same world ray after camera translation
    f[5][93] = 128;
    // Real sky fill and hill-height air: zero ambient/sea-level tests alone miss washed-out shafts.
    memcpy(f[6], f[0], sizeof(f[0]));
    f[6][20] = f[6][440] = sqrtf(1-0.35f*0.35f);
    f[6][21] = f[6][441] = 0.35f; f[6][443] = 1; f[6][85] = 96;
    for (int i = 7; i < 12; i++) memcpy(f[i], f[6], sizeof(f[0]));
    f[7][85] = 144;
    f[8][29] = 1; f[8][94] = 2.5f; f[8][23] = 0.15f; // overcast attenuation
    f[9][90] = 1; f[9][88] = 0; f[9][440] *= -1; f[9][441] *= -1; // moon
    f[10][92] = 0; // deep cave
    f[11][20] = f[11][440] = sqrtf(1-0.05f*0.05f);
    f[11][21] = f[11][441] = 0.05f; f[11][23] = 0.4096f; // continuous dawn shadow fade
    NSError *error = nil;
    id<MTLComputePipelineState> state = [device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"air_checks"] error:&error];
    require(state != nil, error.description);
    id<MTLBuffer> uniforms = [device newBufferWithBytes:f length:sizeof(f) options:MTLResourceStorageModeShared];
    id<MTLBuffer> output = [device newBufferWithLength:78 * sizeof(simd_float4) options:MTLResourceStorageModeShared];
    id<MTLTexture> open = texture(device, MTLPixelFormatDepth32Float, 1), blocked = texture(device, MTLPixelFormatDepth32Float, 1);
    id<MTLTexture> clear = texture(device, MTLPixelFormatRGBA32Float, 1);
    float one = 1, zero = 0, white[] = {1,1,1,1};
    [open replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0 withBytes:&one bytesPerRow:4];
    [blocked replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0 withBytes:&zero bytesPerRow:4];
    [clear replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0 withBytes:white bytesPerRow:16];
    id<MTLTexture> red = texture(device, MTLPixelFormatRGBA32Float, 1), blue = texture(device, MTLPixelFormatRGBA32Float, 1);
    float redValues[] = {0.9f,0.05f,0.03f,0.1f}, blueValues[] = {0.03f,0.05f,0.9f,0.1f};
    [red replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0 withBytes:redValues bytesPerRow:16];
    [blue replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0 withBytes:blueValues bytesPerRow:16];
    id<MTLCommandBuffer> cb = [[device newCommandQueue] commandBuffer];
    id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
    [enc setComputePipelineState:state];
    [enc setBuffer:uniforms offset:0 atIndex:0]; [enc setBuffer:output offset:0 atIndex:1];
    [enc setTexture:open atIndex:0]; [enc setTexture:blocked atIndex:1]; [enc setTexture:clear atIndex:2];
    [enc setTexture:red atIndex:3]; [enc setTexture:blue atIndex:4];
    [enc dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];
    [enc endEncoding]; [cb commit]; [cb waitUntilCompleted];
    require(cb.status == MTLCommandBufferStatusCompleted, cb.error.description);
    simd_float4 *v = output.contents;
    float lengths[] = {0,0.1f,20,96,180,256};
    for (int i = 0; i < 78; i++) for (int j = 0; j < 4; j++) require(isfinite(v[i][j]) && v[i][j] >= 0, @"Invalid air volume value");
    for (int l = 0; l < 6; l++) for (int d = 0; d < 5; d++) {
        float t = expf(-0.003f * lengths[l]);
        require(fabsf(v[l*5+d].w-t) < 0.00001f && fabsf(v[l*5+d].x-(1-t)) < 0.00001f, @"Air scattering/absorption violated Beer-Lambert energy conservation");
    }
    require(v[30].x > 0.01f && v[31].x > 0.01f && v[30].w < 0.8f, @"Distant fog or far-cascade lighting missing");
    require(v[32].x == 0 && v[34].x == 0, @"Direct light leaked through shadows or invalid maps");
    require(v[33].x == 0 && v[33].w == 1, @"Disabled atmosphere not transparent");
    require(v[35].w > 0.94f && v[35].w > v[30].w, @"Nearby air should remain clear");
    require(v[36].x == 1 && v[37].x == 0 && v[38].x == 0, @"Distant shadow coverage incorrect");
    require(v[39].x > v[39].y && v[39].z > v[39].w, @"Height falloff or forward phase regression");
    require(simd_distance(v[40], v[30]) < 0.00001f, @"World-space mist moved with the camera");
    require(v[41].x == 256 && fabsf(v[41].y-125.44f) < 0.0001f, @"Fog extends beyond render distance");
    require(v[42].x > v[42].y * 3 && v[43].z > v[43].y * 3, @"Glass dye washed out by texture transparency");
    require(simd_distance(v[44].xyz, (simd_float3){1,1,1}) < 0.00001f && v[45].y > 0.85f, @"Clear glass/water became strongly tinted");
    require(simd_distance(v[46].xyz, (simd_float3){0.9f,0.05f,0.03f}) < 0.00001f, @"Near glass tint missing");
    require(simd_distance(v[47].xyz, (simd_float3){0.03f,0.05f,0.9f}) < 0.00001f, @"Far glass tint missing");
    require(simd_distance(v[48].xyz, (simd_float3){1,1,1}) < 0.00001f, @"Glass coloured light in front of its surface");
    require(simd_distance(v[50].xyz, v[46].xyz) < 0.00001f && simd_length(v[51].xyz) == 0, @"Water visibility lost its RGB tint or leaked through blockers");
    for (int j = 0; j < 3; j++) {
        require(fabsf(v[52][j] - v[35][j] * redValues[j]) < 0.00001f, @"Air march did not preserve near glass colour");
        require(fabsf(v[53][j] - v[54][j] * blueValues[j]) < 0.00001f, @"Air march did not preserve far glass colour");
        require(v[56][j] > 0 && fabsf(v[55][j] - v[56][j] * redValues[j]) < 0.00001f, @"Water march averaged glass colour to grey");
    }
    require(simd_distance(v[42], v[57]) < 0.00001f, @"Glass transmission incorrectly includes face shading/AO");
    float beam = v[58].x-v[59].x, highBeam = v[60].x-v[61].x;
    require(beam > 0.12f && highBeam > 0.06f, @"Sun shafts disappeared at treetop/hill elevation");
    require(v[58].w > 0.60f && v[58].w < 0.90f && v[60].w > v[58].w, @"Hill fog is opaque or too thin to carry beams");
    require(v[70].x-v[71].x > 0.045f && v[72].x-v[73].x > 0.025f, @"Sun shafts vanish when viewed obliquely");
    require(v[74].w > 0.97f && v[74].w == v[75].w && v[58].w == v[59].w, @"Nearby air is too opaque or shadows alter extinction");
    require(v[62].x-v[63].x < beam*0.65f, @"Overcast shafts are too bright");
    require(v[64].x-v[65].x < beam*0.35f, @"Moonlight became as bright as sunlight");
    require(v[66].x == 0 && v[66].w == 1 && v[67].x == 0, @"Fog leaked into a deep cave");
    require(v[68].x-v[69].x > 0.07f, @"Low morning sun fails to illuminate mist");
    require(v[76].y > 0.0011f && v[76].z > 0.00065f, @"Insufficient scattering medium above sea level");
    require(v[77].x > v[77].y && v[77].y > v[77].z && v[77].z > v[77].w && v[77].x < 1.6f, @"Phase loses directionality or overexposes the solar core");
    printf("Hill shafts: direct contrast %.3f, high hill %.3f, side view %.3f; nearby transmission %.3f\n", beam, highBeam, v[72].x-v[73].x, v[74].w);
    puts("78 atmosphere/glass cases pass: volume integration, near/far RGB transmission, surface tint, underwater tint, and receiver depth.");
}
