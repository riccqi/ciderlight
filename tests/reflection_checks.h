// Synthetic depth scenes exercise the production ray tracer, including the reported false hit.
static void checkReflections(id<MTLDevice> device, NSString *defines) {
    NSString *s = [[defines stringByAppendingString:@"#include <metal_stdlib>\nusing namespace metal;\n"] stringByAppendingString:source(@"reflection.metal")];
    s = [s stringByAppendingString:[NSString stringWithContentsOfFile:@"tests/reflection_checks.metal" encoding:NSUTF8StringEncoding error:NULL]];
    id<MTLLibrary> lib = compile(device, s);
    NSError *error = nil;
    id<MTLComputePipelineState> state = [device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"reflection_checks"] error:&error];
    require(state != nil, error.description);
    enum { size = 256 };
    float pixels[size * size];
    simd_float3 origin = {0,-2,-8}, direction = simd_normalize((simd_float3){0,0.4f,-1});
    for (int camera = 0; camera < 5; camera++) {
        float near = (camera & 1) ? 0.2f : 0.05f;
        simd_float4x4 projection = {.columns = {{1.2f,0,0,0},{0,1.8f,0,0},{0,0,0,-1},{0,0,near,0}}};
        if (camera & 1) {
            projection.columns[2].z = near / (512 - near);
            projection.columns[3].z = near * 512 / (512 - near);
        }
        if (camera == 4) projection = matrix_identity_float4x4; // orthographic fallback
        simd_float4x4 bob = matrix_identity_float4x4;
        if (camera >= 2 && camera < 4) {
            float angle = 0.04f;
            bob.columns[0] = (simd_float4){cosf(angle),sinf(angle),0,0};
            bob.columns[1] = (simd_float4){-sinf(angle),cosf(angle),0,0};
            bob.columns[3] = (simd_float4){0.04f,-0.08f,0.03f,1};
        }
        simd_float4x4 vp = simd_mul(projection,bob), inv = simd_inverse(vp);
        simd_float3 eye = simd_mul(simd_inverse(bob),(simd_float4){0,0,0,1}).xyz;
        for (int scene = 0; scene < 8; scene++) {
            // 0 sky, 1 real wall, 2 foreground silhouette, 3 legitimate nearby object,
            // 4 distant wall, 5 initially occluded, 6 sloping wall, 7 floor behind water.
            simd_float3 normal = scene == 6 ? (simd_float3){0,0.5f,1} :
                                 scene == 7 ? (simd_float3){0,1,0} : (simd_float3){0,0,1};
            float plane = scene == 4 ? -70 : scene == 7 ? -2.4f : -20;
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                float u = (x + 0.5f) / size, v = (y + 0.5f) / size;
                simd_float4 q = simd_mul(inv, (simd_float4){u*2-1,v*2-1,1,1});
                simd_float3 ray = simd_normalize(q.xyz/q.w-eye);
                float localPlane = scene == 3 ? (v > 0.35f ? -10 : -80) : plane;
                float t = (localPlane - simd_dot(normal,eye)) / simd_dot(normal,ray);
                simd_float3 point = eye + ray*t;
                simd_float4 clip = simd_mul(vp, (simd_float4){point.x,point.y,point.z,1});
                float d = t > 0 ? clip.z/clip.w : 0;
                if (scene == 0) d = 0;
                if ((scene == 2 && v > 0.45f) || scene == 5) {
                    d = -projection.columns[2].z + projection.columns[3].z / 2.0f;
                }
                pixels[y*size+x] = fmaxf(d,0);
            }
            MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:size height:size mipmapped:NO];
            desc.storageMode = MTLStorageModeShared; desc.usage = MTLTextureUsageShaderRead;
            id<MTLTexture> depth = [device newTextureWithDescriptor:desc];
            [depth replaceRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:0 withBytes:pixels bytesPerRow:size*sizeof(float)];
            id<MTLBuffer> uniforms = [device newBufferWithBytes:&vp length:sizeof(vp) options:MTLResourceStorageModeShared];
            id<MTLBuffer> result = [device newBufferWithLength:3*sizeof(simd_float4) options:MTLResourceStorageModeShared];
            id<MTLCommandBuffer> cb = [[device newCommandQueue] commandBuffer];
            id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoder];
            [enc setComputePipelineState:state]; [enc setBuffer:uniforms offset:0 atIndex:0];
            [enc setBuffer:result offset:0 atIndex:1]; [enc setTexture:depth atIndex:0];
            [enc dispatchThreads:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];
            [enc endEncoding]; [cb commit]; [cb waitUntilCompleted];
            require(cb.status == MTLCommandBufferStatusCompleted, cb.error.description);
            simd_float4 *out = result.contents;
            BOOL expectedHit = camera < 4 && (scene == 1 || scene == 3 || scene == 4 || scene == 6);
            require((out[0].z > 0.5f) == expectedHit,
                [NSString stringWithFormat:@"Reflection camera=%d scene=%d: confidence=%f distance=%f",camera,scene,out[0].z,out[0].w]);
            require(simd_distance(out[1].xyz, eye) < 0.00001f, @"Reflection eye ignores projection-space view bob");
            if (expectedHit) {
                float hitPlane = scene == 3 ? -10 : plane;
                float expectedT = (hitPlane-simd_dot(normal,origin))/simd_dot(normal,direction);
                require(fabsf(out[0].w-expectedT) < 0.08f, @"Reflection accepted a hit away from the actual surface");
            }
            if (scene == 2 && camera < 4) require(out[2].x == 1, @"Fixture no longer reproduces the old foreground reflection bug");
        }
    }
    printf("%s", defines.length > 0 ? "Low quality: " : "");
    puts("40 reflection scenes pass: foreground rejection, real near/far hits, sloped surfaces, sky, floor, and bobbed finite/infinite projections, and orthographic fallback.");
}
