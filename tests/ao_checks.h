// Ambient occlusion regressions: synthetic depth buffers rendered on the CPU with a reverse-Z perspective camera.
enum { aoSize = 256 };

static simd_float4x4 aoCamera(simd_float4x4 *inverse) {
    // Eye at (0, 2, 0), looking along -Z and 30 degrees down; infinite reverse-Z projection.
    float pitch = 30.0f * M_PI / 180.0f;
    simd_float4x4 view = matrix_identity_float4x4;
    view.columns[1] = (simd_float4){0, cosf(pitch), sinf(pitch), 0};
    view.columns[2] = (simd_float4){0, -sinf(pitch), cosf(pitch), 0};
    simd_float4x4 translate = matrix_identity_float4x4;
    translate.columns[3] = (simd_float4){0, -2, 0, 1};
    simd_float4x4 projection = {.columns = {{1.5f, 0, 0, 0}, {0, 1.5f, 0, 0}, {0, 0, 0, -1}, {0, 0, 0.05f, 0}}};
    simd_float4x4 vp = simd_mul(projection, simd_mul(view, translate));
    *inverse = simd_inverse(vp);
    return vp;
}

// Scene 0: floor y=0 and a wall z=-6 facing the camera. Scene 1: floor and a thin pillar whose front face is at z=-2.
static float aoTrace(int scene, simd_float3 origin, simd_float3 dir, simd_float3 *hit) {
    float best = INFINITY;
    if (dir.y < 0) best = -origin.y / dir.y;
    if (dir.z < 0) {
        float wall = scene == 0 ? -6.0f : -2.0f;
        float t = (wall - origin.z) / dir.z;
        simd_float3 p = origin + dir * t;
        bool inside = scene == 0 ? p.y >= 0 : (fabsf(p.x) <= 0.3f && p.y >= 0 && p.y <= 10);
        if (inside && t < best) best = t;
    }
    if (isinf(best)) return 0;
    *hit = origin + dir * best;
    return best;
}

static void checkAmbientOcclusion(id<MTLDevice> device, id<MTLLibrary> lib) {
    float frame[444] = {0};
    simd_float4x4 inverse, vp = aoCamera(&inverse);
    memcpy(frame + 48, &inverse, sizeof(inverse));
    memcpy(frame + 64, &vp, sizeof(vp));
    static float depths[2][aoSize * aoSize];
    static simd_float3 hits[2][aoSize * aoSize];
    simd_float3 eye = {0, 2, 0};
    for (int scene = 0; scene < 2; scene++) {
        for (int y = 0; y < aoSize; y++) for (int x = 0; x < aoSize; x++) {
            simd_float4 ndc = {(x + 0.5f) / aoSize * 2 - 1, (y + 0.5f) / aoSize * 2 - 1, 0.5f, 1};
            simd_float4 w = simd_mul(inverse, ndc);
            simd_float3 dir = simd_normalize(w.xyz / w.w - eye);
            simd_float3 hit = {0, -1000, 0};
            float t = aoTrace(scene, eye, dir, &hit);
            float depth = 0;
            if (t > 0) {
                simd_float4 clip = simd_mul(vp, (simd_float4){hit.x, hit.y, hit.z, 1});
                depth = clip.z / clip.w;
            }
            depths[scene][y * aoSize + x] = depth;
            hits[scene][y * aoSize + x] = hit;
        }
    }
    // Probe pixels, chosen from what each pixel actually sees.
    simd_int2 probes[6];
    bool found[6] = {false};
    for (int y = 0; y < aoSize; y++) for (int x = 0; x < aoSize; x++) {
        simd_float3 a = hits[0][y * aoSize + x], b = hits[1][y * aoSize + x];
        simd_int2 p = {x, y};
        if (!found[0] && x == aoSize / 2 && fabsf(a.y) < 1e-3f && a.z < -5.75f && a.z > -5.9f) { probes[0] = p; found[0] = true; } // floor at the wall
        if (!found[1] && x == aoSize / 2 && fabsf(a.y) < 1e-3f && a.z < -3.0f && a.z > -3.3f) { probes[1] = p; found[1] = true; } // open floor
        if (!found[2] && x == aoSize / 2 && fabsf(a.z + 6.0f) < 1e-3f && a.y > 2.25f) { probes[2] = p; found[2] = true; } // high on the wall
        if (!found[3] && fabsf(b.y) < 1e-3f && b.z < -15 && x > aoSize / 2 && fabsf(hits[1][y * aoSize + x - 1].z + 2.0f) < 1e-3f) {
            probes[3] = p; found[3] = true; // distant floor just beside the pillar's silhouette
        }
        if (!found[4] && fabsf(b.y) < 1e-3f && b.z > -1.95f && b.z < -1.8f && fabsf(b.x) < 0.15f) { probes[4] = p; found[4] = true; } // pillar base
        if (!found[5] && fabsf(b.z + 2.0f) < 1e-3f && b.y > 2.05f && x == aoSize / 2) { probes[5] = p; found[5] = true; } // pillar face
    }
    for (int i = 0; i < 6; i++) require(found[i], [NSString stringWithFormat:@"AO fixture lacks probe %d", i]);
    MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:aoSize height:aoSize mipmapped:NO];
    d.storageMode = MTLStorageModeShared;
    d.usage = MTLTextureUsageShaderRead;
    id<MTLTexture> corner = [device newTextureWithDescriptor:d], pillar = [device newTextureWithDescriptor:d];
    [corner replaceRegion:MTLRegionMake2D(0, 0, aoSize, aoSize) mipmapLevel:0 withBytes:depths[0] bytesPerRow:aoSize * 4];
    [pillar replaceRegion:MTLRegionMake2D(0, 0, aoSize, aoSize) mipmapLevel:0 withBytes:depths[1] bytesPerRow:aoSize * 4];
    id<MTLTexture> plane = texture(device, MTLPixelFormatRGBA32Float, 4);
    float planeValues[] = {0.2f,0,0,10, 0.4f,0,0,16, 0.6f,0,0,22, 1.0f,0,0,50};
    [plane replaceRegion:MTLRegionMake2D(0,0,4,1) mipmapLevel:0 withBytes:planeValues bytesPerRow:sizeof(planeValues)];
    NSError *error = nil;
    id<MTLComputePipelineState> state = [device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"ao_checks"] error:&error];
    require(state != nil, error.description);
    id<MTLBuffer> uniforms = [device newBufferWithBytes:frame length:sizeof(frame) options:MTLResourceStorageModeShared];
    id<MTLBuffer> probeBuffer = [device newBufferWithBytes:probes length:sizeof(probes) options:MTLResourceStorageModeShared];
    id<MTLBuffer> output = [device newBufferWithLength:32 * sizeof(float) options:MTLResourceStorageModeShared];
    id<MTLCommandBuffer> cb = [[device newCommandQueue] commandBuffer];
    id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
    [enc setComputePipelineState:state];
    [enc setBuffer:uniforms offset:0 atIndex:0]; [enc setBuffer:output offset:0 atIndex:1]; [enc setBuffer:probeBuffer offset:0 atIndex:2];
    [enc setTexture:corner atIndex:0]; [enc setTexture:pillar atIndex:1]; [enc setTexture:plane atIndex:2];
    [enc dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];
    [enc endEncoding]; [cb commit]; [cb waitUntilCompleted];
    require(cb.status == MTLCommandBufferStatusCompleted, cb.error.description);
    float *v = output.contents;
    for (int i = 0; i < 15; i++) require(isfinite(v[i]) && v[i] >= 0 && v[i] <= 1.0001f, [NSString stringWithFormat:@"AO value %d out of range: %f", i, v[i]]);
    require(fabsf(v[0] - 1) < 1e-5f && fabsf(v[1] - 0.5f) < 1e-5f, @"GTAO slice integral regression");
    require(fabsf(v[2] - 1) < 1e-4f && fabsf(v[3] - 1) < 1e-4f, @"An open slice must be fully visible whatever the normal's tilt");
    require(v[4] > 0.1f && v[4] < 0.4f, @"Partly blocked tilted slice");
    require(v[5] < 0.8f, [NSString stringWithFormat:@"Floor/wall crease is not occluded (%.3f)", v[5]]);
    require(v[6] > 0.97f && v[7] > 0.97f, [NSString stringWithFormat:@"Open floor or wall is occluded (%.3f, %.3f)", v[6], v[7]]);
    require(v[8] > 0.97f, [NSString stringWithFormat:@"Dark halo behind a foreground silhouette (%.3f)", v[8]]);
    require(v[9] < 0.9f, [NSString stringWithFormat:@"No contact shadow at the pillar base (%.3f)", v[9]]);
    require(v[10] > 0.97f, [NSString stringWithFormat:@"Pillar face occludes itself (%.3f)", v[10]]);
    require(v[11] > 0.25f && v[11] < 0.35f, [NSString stringWithFormat:@"Plane-predicted AO lookup lost a grazing neighbour (%.3f)", v[11]]);
    require(v[12] == 0, @"AO lookup without a gradient should reject a steep plane");
    require(fabsf(v[13] - 0.6f) < 1e-4f, [NSString stringWithFormat:@"AO lookup mixed in a surface far behind (%.3f)", v[13]]);
    require(v[14] == 0, @"AO lookup matched an unrelated surface");
    printf("AO checks pass: crease %.2f, open floor %.2f, wall %.2f, behind silhouette %.2f, pillar base %.2f, pillar face %.2f.\n",
           v[5], v[6], v[7], v[8], v[9], v[10]);
}
