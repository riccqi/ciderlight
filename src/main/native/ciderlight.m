// Ciderlight native bridge: a thin C ABI over Metal, called from Java via the FFM API.
//
// Every Metal object handed to Java is a +1 retained pointer (CFBridgingRetain) and is
// released with mc_release. Hot-path calls take borrowed pointers and never retain.
//
// Java's threads never drain an autorelease pool, so calls that create or release Metal objects run inside their own
// @autoreleasepool, and MetalCommandEncoder keeps one open around each frame it records (mc_pool_push/mc_pool_pop).
// Without them, what Metal autoreleases is never freed, and with it the buffers it refers to.
//
// Format/enum arguments use Ciderlight's own stable ids (see MetalConst.java), which this
// file maps to the real Metal enums so the Java side never hardcodes Metal enum values.

#import <Metal/Metal.h>
#import <QuartzCore/QuartzCore.h>
#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#include <math.h>
#include <pthread.h>
#include <string.h>

#define EXPORT __attribute__((visibility("default")))
#define BORROW(type, p) ((__bridge type)(p))

// Buffer argument-table layout shared with the shader translator (MetalShaderCompiler.java).
// Uniform slot i -> buffer/texture/sampler index i; push constants -> 15; vertex buffer slot s -> 16 + s.
#define MC_PUSH_CONSTANT_INDEX 15
#define MC_VERTEX_BUFFER_BASE 16

// ---------------------------------------------------------------------------------------------
// Enum mapping

static MTLPixelFormat mc_pixel_format(int f) {
    switch (f) {
        case 0: return MTLPixelFormatR8Unorm;
        case 1: return MTLPixelFormatR8Snorm;
        case 2: return MTLPixelFormatRG8Unorm;
        case 3: return MTLPixelFormatRG8Snorm;
        case 6: return MTLPixelFormatRGBA8Unorm;
        case 7: return MTLPixelFormatRGBA8Snorm;
        case 8: return MTLPixelFormatR16Unorm;
        case 9: return MTLPixelFormatR16Snorm;
        case 10: return MTLPixelFormatRG16Unorm;
        case 11: return MTLPixelFormatRG16Snorm;
        case 14: return MTLPixelFormatRGBA16Unorm;
        case 15: return MTLPixelFormatRGBA16Snorm;
        case 16: return MTLPixelFormatR8Uint;
        case 17: return MTLPixelFormatR8Sint;
        case 18: return MTLPixelFormatRG8Uint;
        case 19: return MTLPixelFormatRG8Sint;
        case 22: return MTLPixelFormatRGBA8Uint;
        case 23: return MTLPixelFormatRGBA8Sint;
        case 24: return MTLPixelFormatR16Uint;
        case 25: return MTLPixelFormatR16Sint;
        case 26: return MTLPixelFormatRG16Uint;
        case 27: return MTLPixelFormatRG16Sint;
        case 30: return MTLPixelFormatRGBA16Uint;
        case 31: return MTLPixelFormatRGBA16Sint;
        case 32: return MTLPixelFormatR32Uint;
        case 33: return MTLPixelFormatR32Sint;
        case 34: return MTLPixelFormatRG32Uint;
        case 35: return MTLPixelFormatRG32Sint;
        case 38: return MTLPixelFormatRGBA32Uint;
        case 39: return MTLPixelFormatRGBA32Sint;
        case 40: return MTLPixelFormatR16Float;
        case 41: return MTLPixelFormatRG16Float;
        case 43: return MTLPixelFormatRGBA16Float;
        case 44: return MTLPixelFormatR32Float;
        case 45: return MTLPixelFormatRG32Float;
        case 47: return MTLPixelFormatRGBA32Float;
        case 48: return MTLPixelFormatRGB10A2Unorm;
        case 49: return MTLPixelFormatRGB10A2Uint;
        case 50: return MTLPixelFormatRG11B10Float;
        case 51: return MTLPixelFormatDepth32Float;
        case 52: return MTLPixelFormatDepth32Float_Stencil8;
        case 53: return MTLPixelFormatDepth32Float_Stencil8; // D24S8 is unavailable on Apple GPUs
        case 54: return MTLPixelFormatDepth16Unorm;
        case 55: return MTLPixelFormatStencil8;
        default: return MTLPixelFormatInvalid; // 3-component formats have no Metal texture equivalent
    }
}

static MTLVertexFormat mc_vertex_format(int f) {
    switch (f) {
        case 0: return MTLVertexFormatUCharNormalized;
        case 1: return MTLVertexFormatCharNormalized;
        case 2: return MTLVertexFormatUChar2Normalized;
        case 3: return MTLVertexFormatChar2Normalized;
        case 4: return MTLVertexFormatUChar3Normalized;
        case 5: return MTLVertexFormatChar3Normalized;
        case 6: return MTLVertexFormatUChar4Normalized;
        case 7: return MTLVertexFormatChar4Normalized;
        case 8: return MTLVertexFormatUShortNormalized;
        case 9: return MTLVertexFormatShortNormalized;
        case 10: return MTLVertexFormatUShort2Normalized;
        case 11: return MTLVertexFormatShort2Normalized;
        case 12: return MTLVertexFormatUShort3Normalized;
        case 13: return MTLVertexFormatShort3Normalized;
        case 14: return MTLVertexFormatUShort4Normalized;
        case 15: return MTLVertexFormatShort4Normalized;
        case 16: return MTLVertexFormatUChar;
        case 17: return MTLVertexFormatChar;
        case 18: return MTLVertexFormatUChar2;
        case 19: return MTLVertexFormatChar2;
        case 20: return MTLVertexFormatUChar3;
        case 21: return MTLVertexFormatChar3;
        case 22: return MTLVertexFormatUChar4;
        case 23: return MTLVertexFormatChar4;
        case 24: return MTLVertexFormatUShort;
        case 25: return MTLVertexFormatShort;
        case 26: return MTLVertexFormatUShort2;
        case 27: return MTLVertexFormatShort2;
        case 28: return MTLVertexFormatUShort3;
        case 29: return MTLVertexFormatShort3;
        case 30: return MTLVertexFormatUShort4;
        case 31: return MTLVertexFormatShort4;
        case 32: return MTLVertexFormatUInt;
        case 33: return MTLVertexFormatInt;
        case 34: return MTLVertexFormatUInt2;
        case 35: return MTLVertexFormatInt2;
        case 36: return MTLVertexFormatUInt3;
        case 37: return MTLVertexFormatInt3;
        case 38: return MTLVertexFormatUInt4;
        case 39: return MTLVertexFormatInt4;
        case 40: return MTLVertexFormatHalf;
        case 41: return MTLVertexFormatHalf2;
        case 42: return MTLVertexFormatHalf3;
        case 43: return MTLVertexFormatHalf4;
        case 44: return MTLVertexFormatFloat;
        case 45: return MTLVertexFormatFloat2;
        case 46: return MTLVertexFormatFloat3;
        case 47: return MTLVertexFormatFloat4;
        case 48: return MTLVertexFormatUInt1010102Normalized;
        case 50: return MTLVertexFormatFloatRG11B10;
        default: return MTLVertexFormatInvalid;
    }
}

static MTLBlendFactor mc_blend_factor(int f) {
    switch (f) {
        case 0: return MTLBlendFactorBlendAlpha;
        case 1: return MTLBlendFactorBlendColor;
        case 2: return MTLBlendFactorDestinationAlpha;
        case 3: return MTLBlendFactorDestinationColor;
        case 4: return MTLBlendFactorOne;
        case 5: return MTLBlendFactorOneMinusBlendAlpha;
        case 6: return MTLBlendFactorOneMinusBlendColor;
        case 7: return MTLBlendFactorOneMinusDestinationAlpha;
        case 8: return MTLBlendFactorOneMinusDestinationColor;
        case 9: return MTLBlendFactorOneMinusSourceAlpha;
        case 10: return MTLBlendFactorOneMinusSourceColor;
        case 11: return MTLBlendFactorSourceAlpha;
        case 12: return MTLBlendFactorSourceAlphaSaturated;
        case 13: return MTLBlendFactorSourceColor;
        default: return MTLBlendFactorZero;
    }
}

static MTLBlendOperation mc_blend_op(int op) {
    switch (op) {
        case 1: return MTLBlendOperationSubtract;
        case 2: return MTLBlendOperationReverseSubtract;
        case 3: return MTLBlendOperationMin;
        case 4: return MTLBlendOperationMax;
        default: return MTLBlendOperationAdd;
    }
}

static MTLCompareFunction mc_compare(int c) {
    switch (c) {
        case 0: return MTLCompareFunctionAlways;
        case 1: return MTLCompareFunctionLess;
        case 2: return MTLCompareFunctionLessEqual;
        case 3: return MTLCompareFunctionEqual;
        case 4: return MTLCompareFunctionNotEqual;
        case 5: return MTLCompareFunctionGreaterEqual;
        case 6: return MTLCompareFunctionGreater;
        default: return MTLCompareFunctionNever;
    }
}

// Ciderlight primitive ids: 0 points, 1 lines, 2 line strip, 3 triangles, 4 triangle strip.
static MTLPrimitiveType mc_primitive(int p) {
    switch (p) {
        case 0: return MTLPrimitiveTypePoint;
        case 1: return MTLPrimitiveTypeLine;
        case 2: return MTLPrimitiveTypeLineStrip;
        case 4: return MTLPrimitiveTypeTriangleStrip;
        default: return MTLPrimitiveTypeTriangle;
    }
}

static MTLPrimitiveTopologyClass mc_topology_class(int p) {
    switch (p) {
        case 0: return MTLPrimitiveTopologyClassPoint;
        case 1: case 2: return MTLPrimitiveTopologyClassLine;
        default: return MTLPrimitiveTopologyClassTriangle;
    }
}

static void mc_write_error(NSError *error, NSString *fallback, char *buf, int len) {
    if (buf == NULL || len <= 0) return;
    NSString *msg = error != nil ? error.localizedDescription : fallback;
    strlcpy(buf, msg != nil ? msg.UTF8String : "unknown error", (size_t)len);
}

// ---------------------------------------------------------------------------------------------
// Context: device, queue, submit tracking, and built-in utility pipelines.

static NSString *const kUtilityShaders =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct VOut { float4 pos [[position]]; float2 uv; };\n"
     "vertex VOut mc_fullscreen_vert(uint vid [[vertex_id]]) {\n"
     "    float2 p = float2((vid << 1) & 2, vid & 2);\n"
     "    VOut o; o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); o.uv = p; return o;\n"
     "}\n"
     "fragment float4 mc_present_frag(VOut in [[stage_in]], texture2d<float> src [[texture(0)]]) {\n"
     "    constexpr sampler s(filter::nearest, address::clamp_to_edge);\n"
     "    return float4(src.sample(s, in.uv).rgb, 1.0);\n"
     "}\n"
     "struct ClearOut { float4 color [[color(0)]]; float depth [[depth(any)]]; };\n"
     "fragment ClearOut mc_clear_frag(VOut in [[stage_in]], constant float4 *v [[buffer(0)]]) {\n"
     "    ClearOut o; o.color = v[0]; o.depth = v[1].x; return o;\n"
     "}\n";

@interface MCContext : NSObject
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic, strong) id<MTLCommandQueue> queue;
@property(nonatomic, strong) id<MTLLibrary> utilityLibrary;
@property(nonatomic, strong) NSMutableDictionary<NSNumber *, id<MTLRenderPipelineState>> *utilityPipelines;
@property(nonatomic, strong) id<MTLDepthStencilState> depthAlwaysWrite;
@property(nonatomic, strong) id<MTLDepthStencilState> depthDisabled;
@end

@implementation MCContext {
@public
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    uint64_t completedIndex;
}
@end

@interface MCFrame : NSObject
@property(nonatomic, strong) MCContext *ctx;
@property(nonatomic, strong) id<MTLCommandBuffer> commandBuffer;
@property(nonatomic, strong) id<MTLBlitCommandEncoder> blit;
// GPU profiling (-Dciderlight.gpuProfile=true): one timestamp pair per encoder, named in passLabels.
@property(nonatomic, strong) id<MTLCounterSampleBuffer> samples;
@property(nonatomic, strong) NSMutableArray<NSString *> *passLabels;
@end
@implementation MCFrame
@end

// ---------------------------------------------------------------------------------------------
// GPU profiling: the time between the start of each encoder's vertex stage and the end of its fragment stage, summed
// per label and logged every few hundred command buffers. Encoders overlap on a tile-based GPU, so the shares say
// where the time goes rather than adding up to the frame exactly.

#define MC_PROFILE_MAX_PASSES 128
#define MC_PROFILE_INTERVAL 300

static id<MTLCounterSet> mc_profile_counters = nil;
static NSString *mc_profile_next_label = nil;
static NSMutableDictionary<NSString *, NSNumber *> *mc_profile_totals = nil;
static NSMutableDictionary<NSString *, NSNumber *> *mc_profile_counts = nil;
static double mc_profile_gpu_seconds = 0;
static int mc_profile_frames = 0;
static bool mc_profile_log = false;     // -Dciderlight.gpuProfile=true: log the totals every MC_PROFILE_INTERVAL submits
static bool mc_profile_tracing = false; // the hitch trace is collecting them, and reads them with mc_profile_report
// For mc_profile_peaks: the most one submit spent in each pass, and the slowest submit's own breakdown.
static NSMutableDictionary<NSString *, NSNumber *> *mc_profile_peak = nil;
static double mc_profile_slowest_gpu = 0;
static NSString *mc_profile_slowest = nil;

EXPORT void mc_profile_trace(int on) {
    mc_profile_tracing = on != 0;
}

EXPORT int mc_profile_enable(void *ctxPtr, int log) {
    mc_profile_log = log != 0;
    MCContext *ctx = BORROW(MCContext *, ctxPtr);
    if (![ctx.device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) return 0;
    for (id<MTLCounterSet> set in ctx.device.counterSets) {
        if ([set.name isEqualToString:MTLCommonCounterSetTimestamp]) mc_profile_counters = set;
    }
    mc_profile_totals = [NSMutableDictionary new];
    mc_profile_counts = [NSMutableDictionary new];
    mc_profile_peak = [NSMutableDictionary new];
    return mc_profile_counters != nil;
}

// Names the next encoder that is opened.
EXPORT void mc_profile_label(const char *label) {
    @autoreleasepool {
        mc_profile_next_label = [NSString stringWithUTF8String:label];
    }
}

// Index of this encoder's timestamp pair, or -1 when not profiling.
static int mc_profile_slot(MCFrame *frame, NSString *fallback) {
    NSString *label = mc_profile_next_label ?: fallback;
    mc_profile_next_label = nil;
    if (frame.samples == nil || frame.passLabels.count >= MC_PROFILE_MAX_PASSES) return -1;
    [frame.passLabels addObject:label];
    return (int)frame.passLabels.count - 1;
}

static void mc_profile_pass(MCFrame *frame, MTLRenderPassDescriptor *rp, NSString *fallback) {
    int slot = mc_profile_slot(frame, fallback);
    if (slot < 0) return;
    MTLRenderPassSampleBufferAttachmentDescriptor *a = rp.sampleBufferAttachments[0];
    a.sampleBuffer = frame.samples;
    a.startOfVertexSampleIndex = (NSUInteger)slot * 4;
    a.endOfVertexSampleIndex = (NSUInteger)slot * 4 + 1;
    a.startOfFragmentSampleIndex = (NSUInteger)slot * 4 + 2;
    a.endOfFragmentSampleIndex = (NSUInteger)slot * 4 + 3;
}

static void mc_profile_finish(id<MTLCounterSampleBuffer> samples, NSArray<NSString *> *passLabels, id<MTLCommandBuffer> cb) {
    NSUInteger count = passLabels.count;
    NSData *data = count > 0 ? [samples resolveCounterRange:NSMakeRange(0, count * 4)] : nil;
    @synchronized (mc_profile_totals) {
        // This submit's time per label (a label can be opened more than once).
        NSMutableDictionary<NSString *, NSNumber *> *submit = [NSMutableDictionary new];
        if (data != nil) {
            const MTLCounterResultTimestamp *t = data.bytes;
            // Each encoder has a vertex-stage pair and a fragment-stage pair (blit encoders use the second pair only).
            for (NSUInteger i = 0; i < count * 2; i++) {
                uint64_t start = t[i * 2].timestamp, end = t[i * 2 + 1].timestamp;
                if (start == 0 || start == MTLCounterErrorValue || end == MTLCounterErrorValue || end < start) continue;
                NSString *label = i % 2 == 0 ? [passLabels[i / 2] stringByAppendingString:@" (vertex)"] : passLabels[i / 2];
                mc_profile_totals[label] = @(mc_profile_totals[label].doubleValue + (double)(end - start));
                mc_profile_counts[label] = @(mc_profile_counts[label].intValue + 1);
                submit[label] = @(submit[label].doubleValue + (double)(end - start));
            }
        }
        double gpu = cb.GPUEndTime - cb.GPUStartTime;
        double passSum = 0;
        for (NSString *label in submit) {
            double ns = submit[label].doubleValue;
            passSum += ns;
            if (ns > mc_profile_peak[label].doubleValue) mc_profile_peak[label] = @(ns);
        }
        if (gpu > mc_profile_slowest_gpu) {
            // When the passes add up to much less than the submit took, the GPU was held up outside them.
            NSMutableString *line = [NSMutableString stringWithFormat:@"%.1f ms on the GPU, passes sum to %.1f:", gpu * 1e3, passSum * 1e-6];
            int listed = 0;
            for (NSString *label in [submit keysSortedByValueUsingComparator:^(NSNumber *a, NSNumber *b) { return [b compare:a]; }]) {
                double ms = submit[label].doubleValue * 1e-6;
                if (ms < 0.2 || listed++ >= 10) break;
                [line appendFormat:@"%@ %@ %.1f", listed > 1 ? @"," : @"", label, ms];
            }
            mc_profile_slowest_gpu = gpu;
            mc_profile_slowest = line;
        }
        mc_profile_gpu_seconds += gpu;
        if (++mc_profile_frames < MC_PROFILE_INTERVAL || !mc_profile_log) return;
        double sum = 0;
        for (NSNumber *n in mc_profile_totals.allValues) sum += n.doubleValue;
        NSMutableString *line = [NSMutableString stringWithFormat:@"Ciderlight GPU profile: %.2f ms per submit, stages sum to %.2f ms;",
            mc_profile_gpu_seconds / mc_profile_frames * 1e3, sum / mc_profile_frames * 1e-6];
        for (NSString *label in [mc_profile_totals keysSortedByValueUsingComparator:^(NSNumber *a, NSNumber *b) { return [b compare:a]; }]) {
            double share = mc_profile_totals[label].doubleValue / MAX(sum, 1.0);
            if (share < 0.005) continue;
            [line appendFormat:@" %@ %.2f", label, mc_profile_totals[label].doubleValue / mc_profile_frames * 1e-6];
            // Labels opened more than once per submit also show how many encoders they took.
            double perSubmit = mc_profile_counts[label].doubleValue / mc_profile_frames;
            [line appendString:perSubmit > 1.05 ? [NSString stringWithFormat:@" (x%.1f),", perSubmit] : @","];
        }
        NSLog(@"%@", line);
        [mc_profile_totals removeAllObjects];
        [mc_profile_counts removeAllObjects];
        mc_profile_gpu_seconds = 0;
        mc_profile_frames = 0;
    }
}

// ---------------------------------------------------------------------------------------------
// Hitch trace (HitchTrace.java): what the GPU and the display did since the last read. Cheap enough to keep always on.

static double mc_display_interval, mc_display_granularity; // defined with the presentation code below

// The step frames stay on screen in: the variable refresh granularity, or the refresh interval.
static double mc_trace_step(void) {
    return mc_display_granularity > 0 ? mc_display_granularity : mc_display_interval;
}

// Off unless the hitch trace or frame pacing is on (mc_trace_enable): nothing is recorded per frame in normal play.
static bool mc_trace_on = false;

EXPORT void mc_trace_enable(int on) {
    mc_trace_on = on != 0;
}

static pthread_mutex_t mc_trace_mutex = PTHREAD_MUTEX_INITIALIZER;
static int64_t mc_trace_completed = 0;       // command buffers finished
static double mc_trace_gpu_sum = 0;          // seconds of GPU time they took
static double mc_trace_gpu_max = 0;
static double mc_trace_latency_max = 0;      // longest commit -> completed
static int64_t mc_trace_presented = 0;       // drawables that reached the screen
static int64_t mc_trace_dropped = 0;         // drawables presented but never shown
static double mc_trace_present_gap_max = 0;  // longest time between two frames reaching the screen
static double mc_trace_last_presented = 0;
static double mc_trace_drawable_max = 0;     // longest nextDrawable call
static int64_t mc_trace_drawable_nil = 0;    // nextDrawable calls that gave up
// How long each frame stayed on screen, in display steps from the shortest refresh interval up (the last bucket: that
// or longer): even motion needs these to be steady.
#define MC_TRACE_HELD 6
static int64_t mc_trace_held[MC_TRACE_HELD] = {0};

// out: [completed, gpuSumNs, gpuMaxNs, latencyMaxNs, presented, dropped, presentGapMaxNs, drawableMaxNs, drawableNil,
//       frames held for each of MC_TRACE_HELD display steps, shortest refresh interval ns, step ns]
EXPORT void mc_trace_stats(int64_t *out) {
    pthread_mutex_lock(&mc_trace_mutex);
    out[0] = mc_trace_completed;
    out[1] = (int64_t)(mc_trace_gpu_sum * 1e9);
    out[2] = (int64_t)(mc_trace_gpu_max * 1e9);
    out[3] = (int64_t)(mc_trace_latency_max * 1e9);
    out[4] = mc_trace_presented;
    out[5] = mc_trace_dropped;
    out[6] = (int64_t)(mc_trace_present_gap_max * 1e9);
    out[7] = (int64_t)(mc_trace_drawable_max * 1e9);
    out[8] = mc_trace_drawable_nil;
    for (int i = 0; i < MC_TRACE_HELD; i++) {
        out[9 + i] = mc_trace_held[i];
        mc_trace_held[i] = 0;
    }
    out[9 + MC_TRACE_HELD] = (int64_t)(mc_display_interval * 1e9);
    out[10 + MC_TRACE_HELD] = (int64_t)(mc_trace_step() * 1e9);
    mc_trace_completed = mc_trace_presented = mc_trace_dropped = mc_trace_drawable_nil = 0;
    mc_trace_gpu_sum = mc_trace_gpu_max = mc_trace_latency_max = mc_trace_present_gap_max = mc_trace_drawable_max = 0;
    pthread_mutex_unlock(&mc_trace_mutex);
}

// For frame pacing (FramePacer), since the last mc_pacing_take: the longest GPU time of a command buffer, and the
// drawables that reached the screen and that were dropped (all of them while the window is hidden).
static double mc_pacing_gpu_max = 0;
static int64_t mc_pacing_shown = 0, mc_pacing_dropped = 0;

// out: [gpuMaxSeconds, shown, dropped]
EXPORT void mc_pacing_take(double *out) {
    pthread_mutex_lock(&mc_trace_mutex);
    out[0] = mc_pacing_gpu_max;
    out[1] = (double)mc_pacing_shown;
    out[2] = (double)mc_pacing_dropped;
    mc_pacing_gpu_max = 0;
    mc_pacing_shown = mc_pacing_dropped = 0;
    pthread_mutex_unlock(&mc_trace_mutex);
}

static void mc_trace_command_buffer(id<MTLCommandBuffer> cb, double committed) {
    double gpu = cb.GPUEndTime - cb.GPUStartTime, latency = CACurrentMediaTime() - committed;
    pthread_mutex_lock(&mc_trace_mutex);
    mc_pacing_gpu_max = MAX(mc_pacing_gpu_max, gpu);
    mc_trace_completed++;
    mc_trace_gpu_sum += gpu;
    mc_trace_gpu_max = MAX(mc_trace_gpu_max, gpu);
    mc_trace_latency_max = MAX(mc_trace_latency_max, latency);
    pthread_mutex_unlock(&mc_trace_mutex);
}

// Input to screen, for the hitch trace: from the oldest key or mouse event a frame took in to the moment that frame
// reached the screen. Since the last mc_trace_input_take: the longest, how many were over 60 ms, and how many frames.
static double mc_trace_input_max = 0;
static int64_t mc_trace_input_slow = 0, mc_trace_input_frames = 0;
static double mc_trace_input_carry = 0; // input of dropped frames, waiting for the next frame that is shown
// From the moment each shown frame read the mouse and keyboard to the moment it reached the screen: the least input
// lag there can be. The longest and the sum, over how many frames.
static double mc_trace_read_max = 0, mc_trace_read_sum = 0;
static int64_t mc_trace_read_frames = 0;

// Called with mc_trace_mutex held.
static void mc_trace_input_shown(double inputTime, double presented) {
    double latency = presented - inputTime;
    mc_trace_input_max = MAX(mc_trace_input_max, latency);
    if (latency > 0.060) mc_trace_input_slow++;
    mc_trace_input_frames++;
}

EXPORT double mc_media_time(void) {
    return CACurrentMediaTime();
}

static void mc_trace_watch_drawable(id<CAMetalDrawable> drawable) {
    [drawable addPresentedHandler:^(id<MTLDrawable> d) {
        double t = d.presentedTime; // 0 when the drawable was dropped instead of shown
        pthread_mutex_lock(&mc_trace_mutex);
        if (t == 0) {
            mc_trace_dropped++;
            mc_pacing_dropped++;
        } else {
            mc_trace_presented++;
            mc_pacing_shown++;
            if (mc_trace_input_carry != 0) {
                mc_trace_input_shown(mc_trace_input_carry, t);
                mc_trace_input_carry = 0;
            }
            if (mc_trace_last_presented != 0) {
                double gap = t - mc_trace_last_presented;
                mc_trace_present_gap_max = MAX(mc_trace_present_gap_max, gap);
                double step = mc_trace_step();
                long first = lround(mc_display_interval / step), held = lround(gap / step);
                if (gap < 0.25) mc_trace_held[MIN(MAX(held - first, 0), MC_TRACE_HELD - 1)]++;
            }
            mc_trace_last_presented = t;
        }
        pthread_mutex_unlock(&mc_trace_mutex);
    }];
}

// inputTime: when the frame's oldest input happened, on the CACurrentMediaTime clock.
EXPORT void mc_trace_frame_input(void *drawablePtr, double inputTime) {
    id<CAMetalDrawable> drawable = BORROW(id<CAMetalDrawable>, drawablePtr);
    [drawable addPresentedHandler:^(id<MTLDrawable> d) {
        double t = d.presentedTime;
        pthread_mutex_lock(&mc_trace_mutex);
        if (t == 0) {
            // Dropped: its input reaches the screen with the next frame that is shown (mc_trace_watch_drawable).
            if (mc_trace_input_carry == 0 || inputTime < mc_trace_input_carry) mc_trace_input_carry = inputTime;
        } else {
            mc_trace_input_shown(inputTime, t);
        }
        pthread_mutex_unlock(&mc_trace_mutex);
    }];
}

// readTime: when the frame read the mouse and keyboard, on the CACurrentMediaTime clock.
EXPORT void mc_trace_frame_read(void *drawablePtr, double readTime) {
    id<CAMetalDrawable> drawable = BORROW(id<CAMetalDrawable>, drawablePtr);
    [drawable addPresentedHandler:^(id<MTLDrawable> d) {
        double t = d.presentedTime;
        if (t == 0) return;
        pthread_mutex_lock(&mc_trace_mutex);
        mc_trace_read_max = MAX(mc_trace_read_max, t - readTime);
        mc_trace_read_sum += t - readTime;
        mc_trace_read_frames++;
        pthread_mutex_unlock(&mc_trace_mutex);
    }];
}

// out: [longest input to screen in seconds, frames over 60 ms, frames measured,
//       longest read to screen in seconds, their sum, frames measured]
EXPORT void mc_trace_input_take(double *out) {
    pthread_mutex_lock(&mc_trace_mutex);
    out[0] = mc_trace_input_max;
    out[1] = (double)mc_trace_input_slow;
    out[2] = (double)mc_trace_input_frames;
    out[3] = mc_trace_read_max;
    out[4] = mc_trace_read_sum;
    out[5] = (double)mc_trace_read_frames;
    mc_trace_input_max = mc_trace_read_max = mc_trace_read_sum = 0;
    mc_trace_input_slow = mc_trace_input_frames = mc_trace_read_frames = 0;
    pthread_mutex_unlock(&mc_trace_mutex);
}

static id<CAMetalDrawable> mc_trace_next_drawable(CAMetalLayer *layer) {
    double start = CACurrentMediaTime();
    id<CAMetalDrawable> d = [layer nextDrawable];
    double took = CACurrentMediaTime() - start;
    pthread_mutex_lock(&mc_trace_mutex);
    mc_trace_drawable_max = MAX(mc_trace_drawable_max, took);
    if (d == nil) mc_trace_drawable_nil++;
    pthread_mutex_unlock(&mc_trace_mutex);
    if (d != nil && mc_trace_on) mc_trace_watch_drawable(d);
    return d;
}

// The GPU time per pass since the last report, as milliseconds per submit, largest first. For the hitch trace.
EXPORT void mc_profile_report(char *buf, int len) {
    buf[0] = 0;
    if (mc_profile_totals == nil) return;
    @autoreleasepool {
        @synchronized (mc_profile_totals) {
            if (mc_profile_frames == 0) return;
            NSMutableString *line = [NSMutableString new];
            int listed = 0;
            for (NSString *label in [mc_profile_totals keysSortedByValueUsingComparator:^(NSNumber *a, NSNumber *b) { return [b compare:a]; }]) {
                double perSubmit = mc_profile_totals[label].doubleValue / mc_profile_frames * 1e-6;
                if (perSubmit < 0.2 || listed++ >= 14) break;
                [line appendFormat:@"%@%@ %.1f", line.length > 0 ? @", " : @"", label, perSubmit];
            }
            strlcpy(buf, line.UTF8String, (size_t)len);
            [mc_profile_totals removeAllObjects];
            [mc_profile_counts removeAllObjects];
            mc_profile_gpu_seconds = 0;
            mc_profile_frames = 0;
        }
    }
}

// The most a single submit spent in each pass since the last call, largest first; then a newline and the slowest
// submit's breakdown. For the hitch trace: a hitch that the per-second averages smooth over shows up here.
EXPORT void mc_profile_peaks(char *buf, int len) {
    buf[0] = 0;
    if (mc_profile_peak == nil) return;
    @autoreleasepool {
        @synchronized (mc_profile_totals) {
            if (mc_profile_slowest == nil) return;
            NSMutableString *line = [NSMutableString new];
            int listed = 0;
            for (NSString *label in [mc_profile_peak keysSortedByValueUsingComparator:^(NSNumber *a, NSNumber *b) { return [b compare:a]; }]) {
                double ms = mc_profile_peak[label].doubleValue * 1e-6;
                if (ms < 0.5 || listed++ >= 10) break;
                [line appendFormat:@"%@%@ %.1f", line.length > 0 ? @", " : @"", label, ms];
            }
            [line appendFormat:@"\n%@", mc_profile_slowest];
            strlcpy(buf, line.UTF8String, (size_t)len);
            [mc_profile_peak removeAllObjects];
            mc_profile_slowest_gpu = 0;
            mc_profile_slowest = nil;
        }
    }
}

EXPORT void mc_release(void *obj) {
    @autoreleasepool {
        if (obj != NULL) CFRelease(obj);
    }
}

EXPORT void *mc_context_create(char *err, int errLen) {
    @autoreleasepool {
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (device == nil) {
            mc_write_error(nil, @"No Metal device available", err, errLen);
            return NULL;
        }
        MCContext *ctx = [MCContext new];
        ctx.device = device;
        ctx.queue = [device newCommandQueueWithMaxCommandBufferCount:64];
        ctx.queue.label = @"Ciderlight";
        pthread_mutex_init(&ctx->mutex, NULL);
        pthread_cond_init(&ctx->cond, NULL);
        ctx->completedIndex = 0;
        NSError *error = nil;
        ctx.utilityLibrary = [device newLibraryWithSource:kUtilityShaders options:nil error:&error];
        if (ctx.utilityLibrary == nil) {
            mc_write_error(error, @"Failed to compile utility shaders", err, errLen);
            return NULL;
        }
        ctx.utilityPipelines = [NSMutableDictionary new];
        MTLDepthStencilDescriptor *ds = [MTLDepthStencilDescriptor new];
        ds.depthCompareFunction = MTLCompareFunctionAlways;
        ds.depthWriteEnabled = YES;
        ctx.depthAlwaysWrite = [device newDepthStencilStateWithDescriptor:ds];
        ds.depthWriteEnabled = NO;
        ctx.depthDisabled = [device newDepthStencilStateWithDescriptor:ds];
        return (void *)CFBridgingRetain(ctx);
    }
}

EXPORT void mc_context_destroy(void *ctxPtr) {
    MCContext *ctx = (MCContext *)CFBridgingRelease(ctxPtr);
    pthread_mutex_destroy(&ctx->mutex);
    pthread_cond_destroy(&ctx->cond);
}

EXPORT void mc_context_name(void *ctxPtr, char *buf, int len) {
    MCContext *ctx = BORROW(MCContext *, ctxPtr);
    strlcpy(buf, ctx.device.name.UTF8String, (size_t)len);
}

// out: [maxBufferLength, recommendedWorkingSet, hasUnifiedMemory, isApple7]
EXPORT void mc_context_limits(void *ctxPtr, int64_t *out) {
    MCContext *ctx = BORROW(MCContext *, ctxPtr);
    out[0] = (int64_t)ctx.device.maxBufferLength;
    out[1] = (int64_t)ctx.device.recommendedMaxWorkingSetSize;
    out[2] = ctx.device.hasUnifiedMemory ? 1 : 0;
    out[3] = [ctx.device supportsFamily:MTLGPUFamilyApple7] ? 1 : 0;
}

EXPORT uint64_t mc_context_completed(void *ctxPtr) {
    MCContext *ctx = BORROW(MCContext *, ctxPtr);
    pthread_mutex_lock(&ctx->mutex);
    uint64_t v = ctx->completedIndex;
    pthread_mutex_unlock(&ctx->mutex);
    return v;
}

// Waits until submit `index` has completed on the GPU. timeoutNs < 0 waits forever.
// Returns 1 if completed, 0 on timeout.
EXPORT int mc_context_wait(void *ctxPtr, uint64_t index, int64_t timeoutNs) {
    MCContext *ctx = BORROW(MCContext *, ctxPtr);
    pthread_mutex_lock(&ctx->mutex);
    if (timeoutNs < 0) {
        while (ctx->completedIndex < index) pthread_cond_wait(&ctx->cond, &ctx->mutex);
    } else if (ctx->completedIndex < index && timeoutNs > 0) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        int64_t ns = ts.tv_nsec + timeoutNs;
        ts.tv_sec += ns / 1000000000LL;
        ts.tv_nsec = ns % 1000000000LL;
        while (ctx->completedIndex < index) {
            if (pthread_cond_timedwait(&ctx->cond, &ctx->mutex, &ts) != 0) break;
        }
    }
    int done = ctx->completedIndex >= index;
    pthread_mutex_unlock(&ctx->mutex);
    return done;
}

// ---------------------------------------------------------------------------------------------
// Resources

// storage: 0 shared (CPU visible), 1 private (GPU only)
EXPORT void *mc_buffer_create(void *ctxPtr, int64_t size, int storage) {
    @autoreleasepool {
        MCContext *ctx = BORROW(MCContext *, ctxPtr);
        MTLResourceOptions opts = storage == 1 ? MTLResourceStorageModePrivate
                                               : (MTLResourceStorageModeShared | MTLResourceCPUCacheModeDefaultCache);
        opts |= MTLResourceHazardTrackingModeTracked;
        // Round up: MSL pads std140 blocks to 16 bytes and Metal validates bindings against the padded size.
        NSUInteger length = ((NSUInteger)MAX(size, 16) + 15) & ~(NSUInteger)15;
        id<MTLBuffer> buf = [ctx.device newBufferWithLength:length options:opts];
        return buf != nil ? (void *)CFBridgingRetain(buf) : NULL;
    }
}

EXPORT void *mc_buffer_contents(void *bufPtr) {
    return BORROW(id<MTLBuffer>, bufPtr).contents;
}

EXPORT void mc_set_label(void *objPtr, const char *label) {
    @autoreleasepool {
        id obj = BORROW(id, objPtr);
        if ([obj respondsToSelector:@selector(setLabel:)]) [obj setLabel:[NSString stringWithUTF8String:label]];
    }
}

// usage bits follow GpuTexture: 1 copy dst, 2 copy src, 4 sampled, 8 render attachment, 16 cubemap
EXPORT void *mc_texture_create(void *ctxPtr, int format, int width, int height, int layers, int mips, int usage) {
    @autoreleasepool {
        MCContext *ctx = BORROW(MCContext *, ctxPtr);
        MTLPixelFormat pf = mc_pixel_format(format);
        if (pf == MTLPixelFormatInvalid) return NULL;
        MTLTextureDescriptor *d = [MTLTextureDescriptor new];
        BOOL cube = (usage & 16) != 0;
        d.textureType = cube ? MTLTextureTypeCube : (layers > 1 ? MTLTextureType2DArray : MTLTextureType2D);
        d.pixelFormat = pf;
        d.width = (NSUInteger)width;
        d.height = (NSUInteger)height;
        d.arrayLength = cube ? 1 : (NSUInteger)MAX(layers, 1);
        d.mipmapLevelCount = (NSUInteger)MAX(mips, 1);
        d.storageMode = MTLStorageModePrivate;
        // No MTLTextureUsagePixelFormatView: views keep their texture's format (mc_texture_view), and that usage would turn
        // off the GPU's lossless compression of the texture, costing bandwidth in every pass that reads or draws to it.
        MTLTextureUsage u = MTLTextureUsageShaderRead;
        if (usage & 8) u |= MTLTextureUsageRenderTarget;
        d.usage = u;
        id<MTLTexture> tex = [ctx.device newTextureWithDescriptor:d];
        return tex != nil ? (void *)CFBridgingRetain(tex) : NULL;
    }
}

EXPORT void *mc_texture_view(void *texPtr, int baseMip, int mipCount) {
    @autoreleasepool {
        id<MTLTexture> tex = BORROW(id<MTLTexture>, texPtr);
        NSUInteger slices = tex.textureType == MTLTextureTypeCube ? 6 : tex.arrayLength;
        id<MTLTexture> view = [tex newTextureViewWithPixelFormat:tex.pixelFormat
                                                     textureType:tex.textureType
                                                          levels:NSMakeRange((NSUInteger)baseMip, (NSUInteger)mipCount)
                                                          slices:NSMakeRange(0, slices)];
        return view != nil ? (void *)CFBridgingRetain(view) : NULL;
    }
}

EXPORT void *mc_texture_buffer_view(void *bufPtr, int64_t offset, int64_t length, int format) {
    @autoreleasepool {
        id<MTLBuffer> buf = BORROW(id<MTLBuffer>, bufPtr);
        MTLPixelFormat pf = mc_pixel_format(format);
        NSUInteger bpp = 0;
        switch (pf) {
            case MTLPixelFormatR8Unorm: case MTLPixelFormatR8Uint: case MTLPixelFormatR8Sint: bpp = 1; break;
            case MTLPixelFormatRG8Unorm: case MTLPixelFormatR16Uint: case MTLPixelFormatR16Sint:
            case MTLPixelFormatR16Float: case MTLPixelFormatR16Unorm: bpp = 2; break;
            case MTLPixelFormatRGBA16Float: case MTLPixelFormatRGBA16Uint: case MTLPixelFormatRGBA16Sint:
            case MTLPixelFormatRG32Float: case MTLPixelFormatRG32Uint: case MTLPixelFormatRG32Sint: bpp = 8; break;
            case MTLPixelFormatRGBA32Float: case MTLPixelFormatRGBA32Uint: case MTLPixelFormatRGBA32Sint: bpp = 16; break;
            default: bpp = 4; break;
        }
        NSUInteger width = (NSUInteger)length / bpp;
        MTLTextureDescriptor *d = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:pf
                                                                                         width:width
                                                                               resourceOptions:buf.resourceOptions
                                                                                         usage:MTLTextureUsageShaderRead];
        id<MTLTexture> tex = [buf newTextureWithDescriptor:d offset:(NSUInteger)offset bytesPerRow:width * bpp];
        return tex != nil ? (void *)CFBridgingRetain(tex) : NULL;
    }
}

EXPORT int64_t mc_texture_buffer_alignment(void *ctxPtr, int format) {
    MCContext *ctx = BORROW(MCContext *, ctxPtr);
    return (int64_t)[ctx.device minimumLinearTextureAlignmentForPixelFormat:mc_pixel_format(format)];
}

// address: 0 repeat, 1 clamp. filter: 0 nearest, 1 linear. mip: 0 not mipmapped, 1 nearest, 2 linear.
EXPORT void *mc_sampler_create(void *ctxPtr, int addrU, int addrV, int minF, int magF, int mipF, float maxLod, int aniso) {
    @autoreleasepool {
        MCContext *ctx = BORROW(MCContext *, ctxPtr);
        MTLSamplerDescriptor *d = [MTLSamplerDescriptor new];
        d.sAddressMode = addrU == 0 ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
        d.tAddressMode = addrV == 0 ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeClampToEdge;
        d.rAddressMode = MTLSamplerAddressModeClampToEdge;
        d.minFilter = minF == 0 ? MTLSamplerMinMagFilterNearest : MTLSamplerMinMagFilterLinear;
        d.magFilter = magF == 0 ? MTLSamplerMinMagFilterNearest : MTLSamplerMinMagFilterLinear;
        d.mipFilter = mipF == 0 ? MTLSamplerMipFilterNotMipmapped
                                : (mipF == 1 ? MTLSamplerMipFilterNearest : MTLSamplerMipFilterLinear);
        d.lodMaxClamp = maxLod;
        d.maxAnisotropy = (NSUInteger)MAX(1, MIN(aniso, 16));
        id<MTLSamplerState> s = [ctx.device newSamplerStateWithDescriptor:d];
        return s != nil ? (void *)CFBridgingRetain(s) : NULL;
    }
}

// ---------------------------------------------------------------------------------------------
// Shaders and pipelines

EXPORT void *mc_library_create(void *ctxPtr, const char *source, char *err, int errLen) {
    @autoreleasepool {
        MCContext *ctx = BORROW(MCContext *, ctxPtr);
        MTLCompileOptions *opts = [MTLCompileOptions new];
        opts.languageVersion = MTLLanguageVersion3_0;
        if (@available(macOS 15, *)) {
            opts.mathMode = MTLMathModeFast;
        } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
            opts.fastMathEnabled = YES;
#pragma clang diagnostic pop
        }
        NSError *error = nil;
        id<MTLLibrary> lib = [ctx.device newLibraryWithSource:[NSString stringWithUTF8String:source] options:opts error:&error];
        if (lib == nil) {
            mc_write_error(error, @"Metal shader compile failed", err, errLen);
            return NULL;
        }
        return (void *)CFBridgingRetain(lib);
    }
}

// desc layout (ints):
//   nVertexBuffers, then per buffer: slot, stride, stepRate (0 = per vertex)
//   nAttributes,    then per attribute: location, bufferSlot, offset, vertexFormatId
//   nColor,         then per target: formatId (-1 = unused), writeMask, blendEnabled,
//                                    srcRGB, dstRGB, opRGB, srcA, dstA, opA
//   depthFormatId (-1 = none), primitiveId
EXPORT void *mc_pipeline_create(void *ctxPtr, void *vertLib, const char *vertEntry, void *fragLib, const char *fragEntry,
                                const int *desc, const char *label, char *err, int errLen) {
    @autoreleasepool {
        MCContext *ctx = BORROW(MCContext *, ctxPtr);
        MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
        pd.label = [NSString stringWithUTF8String:label];
        pd.vertexFunction = [BORROW(id<MTLLibrary>, vertLib) newFunctionWithName:[NSString stringWithUTF8String:vertEntry]];
        if (fragLib != NULL) {
            pd.fragmentFunction = [BORROW(id<MTLLibrary>, fragLib) newFunctionWithName:[NSString stringWithUTF8String:fragEntry]];
        }
        if (pd.vertexFunction == nil) {
            mc_write_error(nil, @"Missing vertex entry point", err, errLen);
            return NULL;
        }
        int i = 0;
        MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
        int nBuffers = desc[i++];
        for (int b = 0; b < nBuffers; b++) {
            int slot = desc[i++], stride = desc[i++], step = desc[i++];
            MTLVertexBufferLayoutDescriptor *l = vd.layouts[MC_VERTEX_BUFFER_BASE + slot];
            l.stride = (NSUInteger)MAX(stride, 4);
            if (step > 0) {
                l.stepFunction = MTLVertexStepFunctionPerInstance;
                l.stepRate = (NSUInteger)step;
            } else {
                l.stepFunction = MTLVertexStepFunctionPerVertex;
            }
        }
        int nAttribs = desc[i++];
        for (int a = 0; a < nAttribs; a++) {
            int location = desc[i++], slot = desc[i++], offset = desc[i++], fmt = desc[i++];
            MTLVertexAttributeDescriptor *ad = vd.attributes[location];
            ad.format = mc_vertex_format(fmt);
            ad.offset = (NSUInteger)offset;
            ad.bufferIndex = (NSUInteger)(MC_VERTEX_BUFFER_BASE + slot);
        }
        if (nBuffers > 0) pd.vertexDescriptor = vd;
        int nColor = desc[i++];
        for (int c = 0; c < nColor; c++) {
            int fmt = desc[i++], mask = desc[i++], blend = desc[i++];
            int sRGB = desc[i++], dRGB = desc[i++], oRGB = desc[i++], sA = desc[i++], dA = desc[i++], oA = desc[i++];
            if (fmt < 0) continue;
            MTLRenderPipelineColorAttachmentDescriptor *ca = pd.colorAttachments[c];
            ca.pixelFormat = mc_pixel_format(fmt);
            MTLColorWriteMask wm = MTLColorWriteMaskNone;
            if (mask & 1) wm |= MTLColorWriteMaskRed;
            if (mask & 2) wm |= MTLColorWriteMaskGreen;
            if (mask & 4) wm |= MTLColorWriteMaskBlue;
            if (mask & 8) wm |= MTLColorWriteMaskAlpha;
            ca.writeMask = wm;
            ca.blendingEnabled = blend != 0;
            if (blend) {
                ca.sourceRGBBlendFactor = mc_blend_factor(sRGB);
                ca.destinationRGBBlendFactor = mc_blend_factor(dRGB);
                ca.rgbBlendOperation = mc_blend_op(oRGB);
                ca.sourceAlphaBlendFactor = mc_blend_factor(sA);
                ca.destinationAlphaBlendFactor = mc_blend_factor(dA);
                ca.alphaBlendOperation = mc_blend_op(oA);
            }
        }
        int depthFmt = desc[i++];
        if (depthFmt >= 0) {
            pd.depthAttachmentPixelFormat = mc_pixel_format(depthFmt);
            if (pd.depthAttachmentPixelFormat == MTLPixelFormatDepth32Float_Stencil8) {
                pd.stencilAttachmentPixelFormat = MTLPixelFormatDepth32Float_Stencil8;
            }
        }
        pd.inputPrimitiveTopology = mc_topology_class(desc[i++]);
        NSError *error = nil;
        id<MTLRenderPipelineState> pso = [ctx.device newRenderPipelineStateWithDescriptor:pd error:&error];
        if (pso == nil) {
            mc_write_error(error, @"Pipeline creation failed", err, errLen);
            return NULL;
        }
        return (void *)CFBridgingRetain(pso);
    }
}

EXPORT void *mc_depth_state_create(void *ctxPtr, int compare, int write) {
    @autoreleasepool {
        MCContext *ctx = BORROW(MCContext *, ctxPtr);
        MTLDepthStencilDescriptor *d = [MTLDepthStencilDescriptor new];
        d.depthCompareFunction = mc_compare(compare);
        d.depthWriteEnabled = write != 0;
        return (void *)CFBridgingRetain([ctx.device newDepthStencilStateWithDescriptor:d]);
    }
}

// kind: 0 present (BGRA8 drawable), 1 clear
static id<MTLRenderPipelineState> mc_utility_pipeline(MCContext *ctx, int kind, MTLPixelFormat color, MTLPixelFormat depth) {
    NSNumber *key = @(((uint64_t)kind << 40) | ((uint64_t)color << 20) | (uint64_t)depth);
    id<MTLRenderPipelineState> pso = ctx.utilityPipelines[key];
    if (pso != nil) return pso;
    MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
    pd.vertexFunction = [ctx.utilityLibrary newFunctionWithName:@"mc_fullscreen_vert"];
    pd.fragmentFunction = [ctx.utilityLibrary newFunctionWithName:kind == 0 ? @"mc_present_frag" : @"mc_clear_frag"];
    pd.colorAttachments[0].pixelFormat = color;
    pd.depthAttachmentPixelFormat = depth;
    if (depth == MTLPixelFormatDepth32Float_Stencil8) pd.stencilAttachmentPixelFormat = depth;
    NSError *error = nil;
    pso = [ctx.device newRenderPipelineStateWithDescriptor:pd error:&error];
    if (pso == nil) {
        NSLog(@"Ciderlight: utility pipeline failed: %@", error);
        return nil;
    }
    ctx.utilityPipelines[key] = pso;
    return pso;
}

// ---------------------------------------------------------------------------------------------
// Frames (one MTLCommandBuffer per submit) and blit work
// (Command buffers, encoders and pass descriptors come back autoreleased: see the pools at the top of the file.)

extern void *objc_autoreleasePoolPush(void);
extern void objc_autoreleasePoolPop(void *token);

// A pool around everything recorded into one frame, opened when the frame begins and drained once it is committed, so
// the many small calls that encode it need no pools of their own. Both run on the render thread.
EXPORT void *mc_pool_push(void) {
    return objc_autoreleasePoolPush();
}

EXPORT void mc_pool_pop(void *token) {
    objc_autoreleasePoolPop(token);
}

EXPORT void *mc_frame_begin(void *ctxPtr) {
    @autoreleasepool {
        MCContext *ctx = BORROW(MCContext *, ctxPtr);
        MCFrame *frame = [MCFrame new];
        frame.ctx = ctx;
        // Resource lifetimes are managed by the Java destroy queue, so skip per-command retains.
        frame.commandBuffer = [ctx.queue commandBufferWithUnretainedReferences];
        if (mc_profile_counters != nil && (mc_profile_log || mc_profile_tracing)) {
            MTLCounterSampleBufferDescriptor *d = [MTLCounterSampleBufferDescriptor new];
            d.counterSet = mc_profile_counters;
            d.storageMode = MTLStorageModeShared;
            d.sampleCount = MC_PROFILE_MAX_PASSES * 4;
            frame.samples = [ctx.device newCounterSampleBufferWithDescriptor:d error:NULL];
            frame.passLabels = [NSMutableArray new];
        }
        return (void *)CFBridgingRetain(frame);
    }
}

static void mc_end_blit(MCFrame *frame) {
    if (frame.blit != nil) {
        [frame.blit endEncoding];
        frame.blit = nil;
    }
}

// `kind` names the blit encoder in the GPU profile after the copy that opened it.
static id<MTLBlitCommandEncoder> mc_blit(MCFrame *frame, NSString *kind) {
    @autoreleasepool {
        if (frame.blit == nil) {
            int slot = mc_profile_slot(frame, kind);
            if (slot >= 0) {
                MTLBlitPassDescriptor *bp = [MTLBlitPassDescriptor blitPassDescriptor];
                bp.sampleBufferAttachments[0].sampleBuffer = frame.samples;
                bp.sampleBufferAttachments[0].startOfEncoderSampleIndex = (NSUInteger)slot * 4 + 2;
                bp.sampleBufferAttachments[0].endOfEncoderSampleIndex = (NSUInteger)slot * 4 + 3;
                frame.blit = [frame.commandBuffer blitCommandEncoderWithDescriptor:bp];
            } else {
                frame.blit = [frame.commandBuffer blitCommandEncoder];
            }
        }
        return frame.blit;
    }
}

// Commits the frame; GPU completion advances the context's completed index to `index`.
EXPORT void mc_frame_commit(void *framePtr, uint64_t index) {
    @autoreleasepool {
        MCFrame *frame = (MCFrame *)CFBridgingRelease(framePtr);
        mc_end_blit(frame);
        MCContext *ctx = frame.ctx;
        id<MTLCounterSampleBuffer> samples = frame.samples;
        NSArray<NSString *> *passLabels = frame.passLabels;
        double committed = CACurrentMediaTime();
        [frame.commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> cb) {
            if (cb.status == MTLCommandBufferStatusError) {
                NSLog(@"Ciderlight: command buffer error: %@", cb.error);
            }
            if (mc_trace_on) mc_trace_command_buffer(cb, committed);
            if (samples != nil) mc_profile_finish(samples, passLabels, cb);
            pthread_mutex_lock(&ctx->mutex);
            if (index > ctx->completedIndex) ctx->completedIndex = index;
            pthread_cond_broadcast(&ctx->cond);
            pthread_mutex_unlock(&ctx->mutex);
        }];
        [frame.commandBuffer commit];
    }
}

EXPORT void mc_blit_copy_buffer(void *framePtr, void *src, int64_t srcOff, void *dst, int64_t dstOff, int64_t size) {
    MCFrame *frame = BORROW(MCFrame *, framePtr);
    [mc_blit(frame, @"copies (buffers)") copyFromBuffer:BORROW(id<MTLBuffer>, src) sourceOffset:(NSUInteger)srcOff
                          toBuffer:BORROW(id<MTLBuffer>, dst) destinationOffset:(NSUInteger)dstOff size:(NSUInteger)size];
}

EXPORT void mc_blit_buffer_to_texture(void *framePtr, void *src, int64_t srcOff, int64_t bytesPerRow, int64_t bytesPerImage,
                                      void *tex, int slice, int mip, int x, int y, int w, int h) {
    MCFrame *frame = BORROW(MCFrame *, framePtr);
    [mc_blit(frame, @"copies (texture uploads)") copyFromBuffer:BORROW(id<MTLBuffer>, src) sourceOffset:(NSUInteger)srcOff
                 sourceBytesPerRow:(NSUInteger)bytesPerRow sourceBytesPerImage:(NSUInteger)bytesPerImage
                        sourceSize:MTLSizeMake((NSUInteger)w, (NSUInteger)h, 1)
                         toTexture:BORROW(id<MTLTexture>, tex) destinationSlice:(NSUInteger)slice
                  destinationLevel:(NSUInteger)mip destinationOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0)];
}

EXPORT void mc_blit_texture_to_buffer(void *framePtr, void *tex, int mip, int x, int y, int w, int h,
                                      void *dst, int64_t dstOff, int64_t bytesPerRow) {
    MCFrame *frame = BORROW(MCFrame *, framePtr);
    id<MTLTexture> t = BORROW(id<MTLTexture>, tex);
    MTLBlitOption opt = MTLBlitOptionNone;
    if (t.pixelFormat == MTLPixelFormatDepth32Float_Stencil8) opt = MTLBlitOptionDepthFromDepthStencil;
    [mc_blit(frame, @"copies (readbacks)") copyFromTexture:t sourceSlice:0 sourceLevel:(NSUInteger)mip
                       sourceOrigin:MTLOriginMake((NSUInteger)x, (NSUInteger)y, 0)
                         sourceSize:MTLSizeMake((NSUInteger)w, (NSUInteger)h, 1)
                           toBuffer:BORROW(id<MTLBuffer>, dst) destinationOffset:(NSUInteger)dstOff
             destinationBytesPerRow:(NSUInteger)bytesPerRow destinationBytesPerImage:(NSUInteger)(bytesPerRow * h)
                            options:opt];
}

EXPORT void mc_blit_texture_to_texture(void *framePtr, void *src, void *dst, int mip, int srcX, int srcY, int dstX, int dstY, int w, int h) {
    MCFrame *frame = BORROW(MCFrame *, framePtr);
    [mc_blit(frame, @"copies (textures)") copyFromTexture:BORROW(id<MTLTexture>, src) sourceSlice:0 sourceLevel:(NSUInteger)mip
                       sourceOrigin:MTLOriginMake((NSUInteger)srcX, (NSUInteger)srcY, 0)
                         sourceSize:MTLSizeMake((NSUInteger)w, (NSUInteger)h, 1)
                          toTexture:BORROW(id<MTLTexture>, dst) destinationSlice:0 destinationLevel:(NSUInteger)mip
                  destinationOrigin:MTLOriginMake((NSUInteger)dstX, (NSUInteger)dstY, 0)];
}

static void mc_set_depth_attachment(MTLRenderPassDescriptor *rp, id<MTLTexture> depth, MTLLoadAction load, double clear) {
    rp.depthAttachment.texture = depth;
    rp.depthAttachment.loadAction = load;
    rp.depthAttachment.storeAction = MTLStoreActionStore;
    rp.depthAttachment.clearDepth = clear;
    if (depth.pixelFormat == MTLPixelFormatDepth32Float_Stencil8) {
        rp.stencilAttachment.texture = depth;
        rp.stencilAttachment.loadAction = load;
        rp.stencilAttachment.storeAction = MTLStoreActionStore;
    }
}

// Clears whole mip levels of a texture (all levels when mip < 0) using load-action clears.
EXPORT void mc_clear_texture(void *framePtr, void *texPtr, int mip, float r, float g, float b, float a, double depth) {
    @autoreleasepool {
        MCFrame *frame = BORROW(MCFrame *, framePtr);
        mc_end_blit(frame);
        id<MTLTexture> tex = BORROW(id<MTLTexture>, texPtr);
        BOOL isDepth = tex.pixelFormat == MTLPixelFormatDepth32Float || tex.pixelFormat == MTLPixelFormatDepth16Unorm ||
                       tex.pixelFormat == MTLPixelFormatDepth32Float_Stencil8;
        NSUInteger first = mip < 0 ? 0 : (NSUInteger)mip;
        NSUInteger last = mip < 0 ? tex.mipmapLevelCount : first + 1;
        for (NSUInteger level = first; level < last; level++) {
            MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
            if (isDepth) {
                mc_set_depth_attachment(rp, tex, MTLLoadActionClear, depth);
                rp.depthAttachment.level = level;
                rp.stencilAttachment.level = level;
            } else {
                rp.colorAttachments[0].texture = tex;
                rp.colorAttachments[0].level = level;
                rp.colorAttachments[0].loadAction = MTLLoadActionClear;
                rp.colorAttachments[0].storeAction = MTLStoreActionStore;
                rp.colorAttachments[0].clearColor = MTLClearColorMake(r, g, b, a);
            }
            mc_profile_pass(frame, rp, @"clear");
            [[frame.commandBuffer renderCommandEncoderWithDescriptor:rp] endEncoding];
        }
    }
}

// Clears a sub-rectangle of a color and depth texture pair by drawing a full-screen triangle under a scissor.
EXPORT void mc_clear_region(void *framePtr, void *colorPtr, void *depthPtr, int mip, int x, int y, int w, int h,
                            float r, float g, float b, float a, float depth) {
    @autoreleasepool {
        MCFrame *frame = BORROW(MCFrame *, framePtr);
        mc_end_blit(frame);
        id<MTLTexture> color = BORROW(id<MTLTexture>, colorPtr);
        id<MTLTexture> depthTex = BORROW(id<MTLTexture>, depthPtr);
        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
        rp.colorAttachments[0].texture = color;
        rp.colorAttachments[0].level = (NSUInteger)mip;
        rp.colorAttachments[0].loadAction = MTLLoadActionLoad;
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        mc_set_depth_attachment(rp, depthTex, MTLLoadActionLoad, 0);
        rp.depthAttachment.level = (NSUInteger)mip;
        id<MTLRenderPipelineState> pso = mc_utility_pipeline(frame.ctx, 1, color.pixelFormat, depthTex.pixelFormat);
        mc_profile_pass(frame, rp, @"clear region");
        id<MTLRenderCommandEncoder> enc = [frame.commandBuffer renderCommandEncoderWithDescriptor:rp];
        if (pso != nil) {
            NSUInteger tw = MAX(color.width >> mip, 1), th = MAX(color.height >> mip, 1);
            NSUInteger sx = MIN((NSUInteger)MAX(x, 0), tw), sy = MIN((NSUInteger)MAX(y, 0), th);
            MTLScissorRect sr = {sx, sy, MIN((NSUInteger)MAX(w, 0), tw - sx), MIN((NSUInteger)MAX(h, 0), th - sy)};
            if (sr.width > 0 && sr.height > 0) {
                float values[8] = {r, g, b, a, depth, 0, 0, 0};
                [enc setRenderPipelineState:pso];
                [enc setDepthStencilState:frame.ctx.depthAlwaysWrite];
                [enc setScissorRect:sr];
                [enc setFragmentBytes:values length:sizeof(values) atIndex:0];
                [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            }
        }
        [enc endEncoding];
    }
}

// ---------------------------------------------------------------------------------------------
// Render passes

// colors: nColor texture pointers (NULL = unused slot); colorMips: mip level per attachment;
// clearMask bit c = clear color attachment c with clearColors[c*4..c*4+3]; discardMask bit c = the pass writes every
// pixel of attachment c, so its previous contents need not be loaded into tile memory.
EXPORT void *mc_pass_begin(void *framePtr, int nColor, void **colors, const int *colorMips, int clearMask, int discardMask,
                           const float *clearColors, void *depthPtr, int depthMip, int clearDepthFlag, double clearDepth,
                           int width, int height) {
    @autoreleasepool {
        MCFrame *frame = BORROW(MCFrame *, framePtr);
        mc_end_blit(frame);
        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
        for (int c = 0; c < nColor; c++) {
            if (colors[c] == NULL) continue;
            MTLRenderPassColorAttachmentDescriptor *ca = rp.colorAttachments[c];
            ca.texture = BORROW(id<MTLTexture>, colors[c]);
            ca.level = (NSUInteger)colorMips[c];
            ca.storeAction = MTLStoreActionStore;
            if (clearMask & (1 << c)) {
                ca.loadAction = MTLLoadActionClear;
                ca.clearColor = MTLClearColorMake(clearColors[c * 4], clearColors[c * 4 + 1], clearColors[c * 4 + 2], clearColors[c * 4 + 3]);
            } else {
                ca.loadAction = discardMask & (1 << c) ? MTLLoadActionDontCare : MTLLoadActionLoad;
            }
        }
        if (depthPtr != NULL) {
            mc_set_depth_attachment(rp, BORROW(id<MTLTexture>, depthPtr), clearDepthFlag ? MTLLoadActionClear : MTLLoadActionLoad, clearDepth);
            rp.depthAttachment.level = (NSUInteger)depthMip;
            rp.stencilAttachment.level = (NSUInteger)depthMip;
        }
        if (nColor == 0 && depthPtr == NULL) {
            rp.renderTargetWidth = (NSUInteger)width;
            rp.renderTargetHeight = (NSUInteger)height;
            rp.defaultRasterSampleCount = 1;
        }
        mc_profile_pass(frame, rp, @"unnamed pass");
        id<MTLRenderCommandEncoder> enc = [frame.commandBuffer renderCommandEncoderWithDescriptor:rp];
        MTLViewport vp = {0, 0, (double)width, (double)height, 0, 1};
        [enc setViewport:vp];
        // Shaders flip clip-space Y so memory layout matches OpenGL/Vulkan; that also flips winding.
        [enc setFrontFacingWinding:MTLWindingClockwise];
        return (void *)CFBridgingRetain(enc);
    }
}

EXPORT void mc_pass_end(void *encPtr) {
    id<MTLRenderCommandEncoder> enc = (id<MTLRenderCommandEncoder>)CFBridgingRelease(encPtr);
    [enc endEncoding];
}

EXPORT void mc_pass_push_debug(void *encPtr, const char *label) {
    @autoreleasepool {
        [BORROW(id<MTLRenderCommandEncoder>, encPtr) pushDebugGroup:[NSString stringWithUTF8String:label]];
    }
}

EXPORT void mc_pass_pop_debug(void *encPtr) {
    [BORROW(id<MTLRenderCommandEncoder>, encPtr) popDebugGroup];
}

// Clamps depth instead of clipping at the near and far planes: shadow casters between the light and the map's near plane
// then still land in the map, at its nearest depth.
EXPORT void mc_pass_set_depth_clamp(void *encPtr, int clamp) {
    [BORROW(id<MTLRenderCommandEncoder>, encPtr) setDepthClipMode:clamp ? MTLDepthClipModeClamp : MTLDepthClipModeClip];
}

// cull: 0 none, 1 back. fill: 0 fill, 1 lines.
EXPORT void mc_pass_set_pipeline(void *encPtr, void *pso, void *depthState, int cull, int fill, float depthBias, float slopeScale) {
    id<MTLRenderCommandEncoder> enc = BORROW(id<MTLRenderCommandEncoder>, encPtr);
    [enc setRenderPipelineState:BORROW(id<MTLRenderPipelineState>, pso)];
    if (depthState != NULL) [enc setDepthStencilState:BORROW(id<MTLDepthStencilState>, depthState)];
    [enc setCullMode:cull ? MTLCullModeBack : MTLCullModeNone];
    [enc setTriangleFillMode:fill ? MTLTriangleFillModeLines : MTLTriangleFillModeFill];
    [enc setDepthBias:depthBias slopeScale:slopeScale clamp:0];
}

EXPORT void mc_pass_set_scissor(void *encPtr, int x, int y, int w, int h, int targetW, int targetH) {
    // Metal requires the scissor rect to lie within the render target.
    int x0 = MAX(0, MIN(x, targetW)), y0 = MAX(0, MIN(y, targetH));
    int x1 = MAX(x0, MIN(x + w, targetW)), y1 = MAX(y0, MIN(y + h, targetH));
    MTLScissorRect r = {(NSUInteger)x0, (NSUInteger)y0, (NSUInteger)(x1 - x0), (NSUInteger)(y1 - y0)};
    [BORROW(id<MTLRenderCommandEncoder>, encPtr) setScissorRect:r];
}

EXPORT void mc_pass_set_vertex_buffer(void *encPtr, int slot, void *buf, int64_t offset) {
    [BORROW(id<MTLRenderCommandEncoder>, encPtr) setVertexBuffer:BORROW(id<MTLBuffer>, buf) offset:(NSUInteger)offset
                                                         atIndex:(NSUInteger)(MC_VERTEX_BUFFER_BASE + slot)];
}

// Binds a pipeline's resources in one call. kinds[i]: 0 skip, 1 buffer, 2 texture+sampler, 3 texture only.
// stages[i]: bit 0 vertex, bit 1 fragment.
EXPORT void mc_pass_bind(void *encPtr, int count, const int *kinds, const int *stages, void **objs, void **samplers, const int64_t *offsets) {
    id<MTLRenderCommandEncoder> enc = BORROW(id<MTLRenderCommandEncoder>, encPtr);
    for (int i = 0; i < count; i++) {
        int kind = kinds[i], st = stages[i];
        if (kind == 1) {
            id<MTLBuffer> b = BORROW(id<MTLBuffer>, objs[i]);
            if (st & 1) [enc setVertexBuffer:b offset:(NSUInteger)offsets[i] atIndex:(NSUInteger)i];
            if (st & 2) [enc setFragmentBuffer:b offset:(NSUInteger)offsets[i] atIndex:(NSUInteger)i];
        } else if (kind == 2 || kind == 3) {
            id<MTLTexture> t = BORROW(id<MTLTexture>, objs[i]);
            if (st & 1) [enc setVertexTexture:t atIndex:(NSUInteger)i];
            if (st & 2) [enc setFragmentTexture:t atIndex:(NSUInteger)i];
            if (kind == 2) {
                id<MTLSamplerState> s = BORROW(id<MTLSamplerState>, samplers[i]);
                if (st & 1) [enc setVertexSamplerState:s atIndex:(NSUInteger)i];
                if (st & 2) [enc setFragmentSamplerState:s atIndex:(NSUInteger)i];
            }
        }
    }
}

EXPORT void mc_pass_push_constants(void *encPtr, const void *data, int length, int stages) {
    id<MTLRenderCommandEncoder> enc = BORROW(id<MTLRenderCommandEncoder>, encPtr);
    if (stages & 1) [enc setVertexBytes:data length:(NSUInteger)length atIndex:MC_PUSH_CONSTANT_INDEX];
    if (stages & 2) [enc setFragmentBytes:data length:(NSUInteger)length atIndex:MC_PUSH_CONSTANT_INDEX];
}

EXPORT void mc_pass_draw(void *encPtr, int prim, int vertexStart, int vertexCount, int instanceCount, int baseInstance) {
    if (vertexCount <= 0 || instanceCount <= 0) return;
    [BORROW(id<MTLRenderCommandEncoder>, encPtr) drawPrimitives:mc_primitive(prim) vertexStart:(NSUInteger)vertexStart
                                                    vertexCount:(NSUInteger)vertexCount instanceCount:(NSUInteger)instanceCount
                                                   baseInstance:(NSUInteger)baseInstance];
}

// indexType: 0 = uint16, 1 = uint32
EXPORT void mc_pass_draw_indexed(void *encPtr, int prim, int indexCount, int indexType, void *indexBuf, int64_t indexOffset,
                                 int instanceCount, int baseVertex, int baseInstance) {
    if (indexCount <= 0 || instanceCount <= 0) return;
    [BORROW(id<MTLRenderCommandEncoder>, encPtr) drawIndexedPrimitives:mc_primitive(prim) indexCount:(NSUInteger)indexCount
                                                            indexType:indexType ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16
                                                          indexBuffer:BORROW(id<MTLBuffer>, indexBuf)
                                                    indexBufferOffset:(NSUInteger)indexOffset
                                                        instanceCount:(NSUInteger)instanceCount
                                                           baseVertex:baseVertex baseInstance:(NSUInteger)baseInstance];
}

// params: drawCount triples of (firstIndex, indexCount, vertexOffset), matching VkMultiDrawIndexedInfoEXT.
EXPORT void mc_pass_multi_draw_indexed(void *encPtr, int prim, const int *params, int drawCount, int indexType, void *indexBuf,
                                       int64_t indexBase, int instanceCount, int baseInstance) {
    id<MTLRenderCommandEncoder> enc = BORROW(id<MTLRenderCommandEncoder>, encPtr);
    id<MTLBuffer> ib = BORROW(id<MTLBuffer>, indexBuf);
    MTLIndexType type = indexType ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16;
    NSUInteger indexSize = indexType ? 4 : 2;
    MTLPrimitiveType p = mc_primitive(prim);
    for (int d = 0; d < drawCount; d++) {
        int first = params[d * 3], count = params[d * 3 + 1], vertexOffset = params[d * 3 + 2];
        if (count <= 0) continue;
        [enc drawIndexedPrimitives:p indexCount:(NSUInteger)count indexType:type indexBuffer:ib
                 indexBufferOffset:(NSUInteger)indexBase + (NSUInteger)first * indexSize
                     instanceCount:(NSUInteger)instanceCount baseVertex:vertexOffset baseInstance:(NSUInteger)baseInstance];
    }
}

// params: drawCount pairs of (firstVertex, vertexCount), matching VkMultiDrawInfoEXT.
EXPORT void mc_pass_multi_draw(void *encPtr, int prim, const int *params, int drawCount, int instanceCount, int baseInstance) {
    id<MTLRenderCommandEncoder> enc = BORROW(id<MTLRenderCommandEncoder>, encPtr);
    MTLPrimitiveType p = mc_primitive(prim);
    for (int d = 0; d < drawCount; d++) {
        int first = params[d * 2], count = params[d * 2 + 1];
        if (count <= 0) continue;
        [enc drawPrimitives:p vertexStart:(NSUInteger)first vertexCount:(NSUInteger)count
              instanceCount:(NSUInteger)instanceCount baseInstance:(NSUInteger)baseInstance];
    }
}

EXPORT void mc_pass_draw_indexed_indirect(void *encPtr, int prim, int indexType, void *indexBuf, int64_t indexOffset,
                                          void *argBuf, int64_t argOffset, int drawCount, int stride) {
    id<MTLRenderCommandEncoder> enc = BORROW(id<MTLRenderCommandEncoder>, encPtr);
    for (int d = 0; d < drawCount; d++) {
        [enc drawIndexedPrimitives:mc_primitive(prim) indexType:indexType ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16
                       indexBuffer:BORROW(id<MTLBuffer>, indexBuf) indexBufferOffset:(NSUInteger)indexOffset
                    indirectBuffer:BORROW(id<MTLBuffer>, argBuf) indirectBufferOffset:(NSUInteger)(argOffset + (int64_t)d * stride)];
    }
}

// mc_pass_draw_indexed_indirect for chunk sections, leaving out those that cannot land in a shadow map. Each draw's
// instance holds its section's block position (int3 at posOffset); `args` and `instances` are the CPU addresses of the
// draw arguments and of the instance data (instanceCount of them). `matrix` (column-major, orthographic) takes a position relative to the map's
// anchor to clip space, and (tx, ty, tz) takes block coordinates to that. A section is kept unless its 16-block cube,
// plus `slack` blocks, lies wholly beside the map or beyond its far plane; nearer the light than the near plane it
// still casts (depth clamp). Returns how many draws were issued.
EXPORT int mc_pass_draw_sections_culled(void *encPtr, int prim, int indexType, void *indexBuf, void *argBuf, int64_t argOffset,
                                        int drawCount, int stride, const void *args, const void *instances, int instanceCount,
                                        int instanceStride, int posOffset, const float *matrix, double tx, double ty, double tz, float slack) {
    id<MTLRenderCommandEncoder> enc = BORROW(id<MTLRenderCommandEncoder>, encPtr);
    id<MTLBuffer> ib = BORROW(id<MTLBuffer>, indexBuf), ab = BORROW(id<MTLBuffer>, argBuf);
    MTLPrimitiveType p = mc_primitive(prim);
    MTLIndexType type = indexType ? MTLIndexTypeUInt32 : MTLIndexTypeUInt16;
    const float *m = matrix;
    float half = 8.0f + slack;
    float ex = half * (fabsf(m[0]) + fabsf(m[4]) + fabsf(m[8])) + 1.0f;
    float ey = half * (fabsf(m[1]) + fabsf(m[5]) + fabsf(m[9])) + 1.0f;
    float ez = half * (fabsf(m[2]) + fabsf(m[6]) + fabsf(m[10])) + 1.0f;
    int kept = 0;
    for (int d = 0; d < drawCount; d++) {
        const uint32_t *arg = (const uint32_t *)((const char *)args + (int64_t)d * stride);
        if (arg[4] < (uint32_t)instanceCount) { // otherwise its position is not readable here: draw it
            int32_t pos[3];
            memcpy(pos, (const char *)instances + (int64_t)arg[4] * instanceStride + posOffset, sizeof(pos));
            float x = (float)(pos[0] + 8.0 + tx), y = (float)(pos[1] + 8.0 + ty), z = (float)(pos[2] + 8.0 + tz);
            if (fabsf(m[0] * x + m[4] * y + m[8] * z + m[12]) > ex) continue;
            if (fabsf(m[1] * x + m[5] * y + m[9] * z + m[13]) > ey) continue;
            if (m[2] * x + m[6] * y + m[10] * z + m[14] > ez) continue;
        }
        [enc drawIndexedPrimitives:p indexType:type indexBuffer:ib indexBufferOffset:0
                    indirectBuffer:ab indirectBufferOffset:(NSUInteger)(argOffset + (int64_t)d * stride)];
        kept++;
    }
    return kept;
}

EXPORT void mc_pass_draw_indirect(void *encPtr, int prim, void *argBuf, int64_t argOffset, int drawCount, int stride) {
    id<MTLRenderCommandEncoder> enc = BORROW(id<MTLRenderCommandEncoder>, encPtr);
    for (int d = 0; d < drawCount; d++) {
        [enc drawPrimitives:mc_primitive(prim) indirectBuffer:BORROW(id<MTLBuffer>, argBuf)
       indirectBufferOffset:(NSUInteger)(argOffset + (int64_t)d * stride)];
    }
}

// ---------------------------------------------------------------------------------------------
// Presentation

EXPORT void mc_layer_setup(void *ctxPtr, void *layerPtr) {
    MCContext *ctx = BORROW(MCContext *, ctxPtr);
    CAMetalLayer *layer = BORROW(CAMetalLayer *, layerPtr);
    layer.device = ctx.device;
    layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    layer.framebufferOnly = YES;
    layer.maximumDrawableCount = 3;
    layer.opaque = YES;
}

// nextDrawable blocks until the compositor frees a drawable, and when it holds on to them (window server busy, window
// covered) that is a full second before it gives up, freezing the game. The request runs on a queue of its own instead
// and the render thread waits at most timeoutMs for it (vsync: up to a refresh; uncapped: not at all); past that the
// frame is rendered without being presented, and the drawable that arrives late goes to the next frame.
static pthread_mutex_t mc_vsync_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t mc_vsync_cond = PTHREAD_COND_INITIALIZER;
static void *mc_vsync_ready = NULL; // +1 retained drawable not yet handed out
static bool mc_vsync_requested = false;
static uint64_t mc_vsync_generation = 0; // bumped on reconfigure, so a request still in flight is discarded

static void mc_vsync_drop_ready(void) {
    pthread_mutex_lock(&mc_vsync_mutex);
    void *stale = mc_vsync_ready;
    mc_vsync_ready = NULL;
    mc_vsync_generation++;
    pthread_mutex_unlock(&mc_vsync_mutex);
    if (stale != NULL) CFRelease(stale);
}

// The display the game window is on (for frame pacing, FramePacer), in seconds: its shortest refresh interval, and for
// a variable refresh display (ProMotion, Adaptive-Sync) its longest one and the step between them, both 0 for a fixed
// refresh rate. Asked of the screen whenever the layer is configured, not assumed.
static double mc_display_interval = 1.0 / 60.0;
static double mc_display_max_interval = 0;
static double mc_display_granularity = 0;

// out: [interval, maxInterval, granularity]
EXPORT void mc_layer_display_timing(double *out) {
    out[0] = mc_display_interval;
    out[1] = mc_display_max_interval;
    out[2] = mc_display_granularity;
}

EXPORT void mc_layer_configure(void *layerPtr, int width, int height, int vsync) {
    CAMetalLayer *layer = BORROW(CAMetalLayer *, layerPtr);
    @autoreleasepool {
        // Called on the main thread (the render thread runs there), where AppKit may be asked.
        id delegate = layer.delegate;
        NSScreen *screen = [delegate isKindOfClass:[NSView class]] ? ((NSView *)delegate).window.screen : nil;
        screen = screen ?: NSScreen.mainScreen;
        double interval = screen.minimumRefreshInterval;
        if (interval <= 0 && screen.maximumFramesPerSecond > 0) interval = 1.0 / (double)screen.maximumFramesPerSecond;
        if (interval > 0) mc_display_interval = interval;
        double longest = screen.maximumRefreshInterval, step = screen.displayUpdateGranularity;
        bool variable = longest > mc_display_interval * 1.01 && step > 0 && step < longest;
        mc_display_max_interval = variable ? longest : 0;
        mc_display_granularity = variable ? step : 0;
    }
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    layer.drawableSize = CGSizeMake(width, height);
    layer.displaySyncEnabled = vsync != 0;
    [CATransaction commit];
    // A drawable fetched ahead has the old size (or belongs to the other present mode).
    mc_vsync_drop_ready();
}

// Starts fetching the next drawable if none is ready or on its way, then waits up to timeoutMs for it.
// Called with mc_vsync_mutex held; returns with it held.
static void mc_vsync_await_locked(CAMetalLayer *layer, int timeoutMs) {
    static dispatch_queue_t queue;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ queue = dispatch_queue_create("ciderlight.drawable", DISPATCH_QUEUE_SERIAL); });
    if (mc_vsync_ready == NULL && !mc_vsync_requested) {
        mc_vsync_requested = true;
        uint64_t generation = mc_vsync_generation;
        dispatch_async(queue, ^{
            void *drawable;
            @autoreleasepool {
                id<CAMetalDrawable> d = mc_trace_next_drawable(layer);
                drawable = d != nil ? (void *)CFBridgingRetain(d) : NULL;
            }
            pthread_mutex_lock(&mc_vsync_mutex);
            if (generation == mc_vsync_generation) {
                mc_vsync_ready = drawable;
            } else if (drawable != NULL) {
                CFRelease(drawable);
            }
            mc_vsync_requested = false;
            pthread_cond_broadcast(&mc_vsync_cond);
            pthread_mutex_unlock(&mc_vsync_mutex);
        });
    }
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += timeoutMs / 1000;
    deadline.tv_nsec += (long)(timeoutMs % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec++;
        deadline.tv_nsec -= 1000000000L;
    }
    while (mc_vsync_ready == NULL && mc_vsync_requested) {
        if (pthread_cond_timedwait(&mc_vsync_cond, &mc_vsync_mutex, &deadline) != 0) break;
    }
}

// Waits for the next frame's drawable right after presenting, before the game reads input for that frame, so the
// wait for the display does not sit between reading input and drawing it. The drawable stays queued for
// mc_layer_next_drawable.
EXPORT void mc_layer_prefetch_drawable(void *layerPtr, int timeoutMs) {
    pthread_mutex_lock(&mc_vsync_mutex);
    mc_vsync_await_locked(BORROW(CAMetalLayer *, layerPtr), timeoutMs);
    pthread_mutex_unlock(&mc_vsync_mutex);
}

EXPORT void *mc_layer_next_drawable(void *layerPtr, int timeoutMs) {
    pthread_mutex_lock(&mc_vsync_mutex);
    mc_vsync_await_locked(BORROW(CAMetalLayer *, layerPtr), timeoutMs);
    void *drawable = mc_vsync_ready;
    mc_vsync_ready = NULL;
    pthread_mutex_unlock(&mc_vsync_mutex);
    return drawable;
}

// Draws `src` into the drawable flipped vertically (OpenGL-style row order -> screen order) and
// schedules presentation when the frame's command buffer completes.
// minDuration > 0: the drawable is shown no sooner than that long after the previous one (frame pacing, MetalSurface).
EXPORT void mc_present(void *framePtr, void *drawablePtr, void *srcTex, int width, int height, double minDuration) {
    @autoreleasepool {
        MCFrame *frame = BORROW(MCFrame *, framePtr);
        mc_end_blit(frame);
        id<CAMetalDrawable> drawable = BORROW(id<CAMetalDrawable>, drawablePtr);
        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
        rp.colorAttachments[0].texture = drawable.texture;
        rp.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        mc_profile_pass(frame, rp, @"present");
        id<MTLRenderCommandEncoder> enc = [frame.commandBuffer renderCommandEncoderWithDescriptor:rp];
        id<MTLRenderPipelineState> pso = mc_utility_pipeline(frame.ctx, 0, MTLPixelFormatBGRA8Unorm, MTLPixelFormatInvalid);
        if (pso != nil) {
            MTLViewport vp = {0, 0, (double)width, (double)height, 0, 1};
            [enc setViewport:vp];
            [enc setRenderPipelineState:pso];
            [enc setFragmentTexture:BORROW(id<MTLTexture>, srcTex) atIndex:0];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        }
        [enc endEncoding];
        if (minDuration > 0) {
            [frame.commandBuffer presentDrawable:drawable afterMinimumDuration:minDuration];
        } else {
            [frame.commandBuffer presentDrawable:drawable];
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Extra bindings used by the Ciderlight shader pipeline (stages: bit 0 vertex, bit 1 fragment)

EXPORT void mc_pass_set_bytes(void *encPtr, int index, const void *data, int length, int stages) {
    id<MTLRenderCommandEncoder> enc = BORROW(id<MTLRenderCommandEncoder>, encPtr);
    if (stages & 1) [enc setVertexBytes:data length:(NSUInteger)length atIndex:(NSUInteger)index];
    if (stages & 2) [enc setFragmentBytes:data length:(NSUInteger)length atIndex:(NSUInteger)index];
}

EXPORT void mc_pass_set_buffer(void *encPtr, int index, void *buf, int64_t offset, int stages) {
    id<MTLRenderCommandEncoder> enc = BORROW(id<MTLRenderCommandEncoder>, encPtr);
    id<MTLBuffer> b = BORROW(id<MTLBuffer>, buf);
    if (stages & 1) [enc setVertexBuffer:b offset:(NSUInteger)offset atIndex:(NSUInteger)index];
    if (stages & 2) [enc setFragmentBuffer:b offset:(NSUInteger)offset atIndex:(NSUInteger)index];
}

EXPORT void mc_pass_set_texture(void *encPtr, int index, void *tex, void *samp, int stages) {
    id<MTLRenderCommandEncoder> enc = BORROW(id<MTLRenderCommandEncoder>, encPtr);
    id<MTLTexture> t = BORROW(id<MTLTexture>, tex);
    if (stages & 1) [enc setVertexTexture:t atIndex:(NSUInteger)index];
    if (stages & 2) [enc setFragmentTexture:t atIndex:(NSUInteger)index];
    if (samp != NULL) {
        id<MTLSamplerState> s = BORROW(id<MTLSamplerState>, samp);
        if (stages & 1) [enc setVertexSamplerState:s atIndex:(NSUInteger)index];
        if (stages & 2) [enc setFragmentSamplerState:s atIndex:(NSUInteger)index];
    }
}

// Linear depth-comparison sampler for hardware 2x2 PCF shadow lookups.
EXPORT void *mc_sampler_create_compare(void *ctxPtr) {
    MCContext *ctx = BORROW(MCContext *, ctxPtr);
    MTLSamplerDescriptor *d = [MTLSamplerDescriptor new];
    d.minFilter = MTLSamplerMinMagFilterLinear;
    d.magFilter = MTLSamplerMinMagFilterLinear;
    d.sAddressMode = MTLSamplerAddressModeClampToEdge;
    d.tAddressMode = MTLSamplerAddressModeClampToEdge;
    d.compareFunction = MTLCompareFunctionLessEqual;
    return (void *)CFBridgingRetain([ctx.device newSamplerStateWithDescriptor:d]);
}
