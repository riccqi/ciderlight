package dev.honeycrisp.backend;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * FFM bindings to libhoneycrisp (src/main/native/honeycrisp.m).
 * Native object pointers travel as Java longs; arrays go through heap segments on critical calls.
 */
public final class MetalNative {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final Linker.Option CRITICAL = Linker.Option.critical(true);
    private static SymbolLookup lookup;

    private MetalNative() {
    }

    public static synchronized void load() throws IOException {
        if (lookup != null) {
            return;
        }
        Path dir = Files.createTempDirectory("honeycrisp");
        Path lib = dir.resolve("libhoneycrisp.dylib");
        try (InputStream in = MetalNative.class.getResourceAsStream("/natives/libhoneycrisp.dylib")) {
            if (in == null) {
                throw new IOException("libhoneycrisp.dylib is missing from the mod jar");
            }
            Files.copy(in, lib, StandardCopyOption.REPLACE_EXISTING);
        }
        lib.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        lookup = SymbolLookup.libraryLookup(lib, Arena.global());
        Handles.init();
    }

    private static MethodHandle fn(String name, FunctionDescriptor desc, Linker.Option... options) {
        MemorySegment symbol = lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError("Missing native symbol " + name));
        return LINKER.downcallHandle(symbol, desc, options);
    }

    private static FunctionDescriptor v(MemoryLayout... args) {
        return FunctionDescriptor.ofVoid(args);
    }

    private static FunctionDescriptor r(MemoryLayout ret, MemoryLayout... args) {
        return FunctionDescriptor.of(ret, args);
    }

    private static final ValueLayout P = JAVA_LONG; // native pointer passed by value
    private static final ValueLayout I = JAVA_INT;

    private static final class Handles {
        static MethodHandle release, contextCreate, contextDestroy, contextName, contextLimits, contextCompleted, contextWait;
        static MethodHandle bufferCreate, bufferContents, setLabel, textureCreate, textureView, textureBufferView, textureBufferAlignment;
        static MethodHandle samplerCreate, libraryCreate, pipelineCreate, depthStateCreate;
        static MethodHandle poolPush, poolPop, frameBegin, frameCommit, blitCopyBuffer, blitBufferToTexture, blitTextureToBuffer, blitTextureToTexture;
        static MethodHandle clearTexture, clearRegion, passBegin, passEnd, passPushDebug, passPopDebug, passSetPipeline, passSetDepthClamp, passSetScissor;
        static MethodHandle passSetVertexBuffer, passBind, passPushConstants, passDraw, passDrawIndexed, passMultiDrawIndexed, passMultiDraw;
        static MethodHandle passDrawIndexedIndirect, passDrawSectionsCulled, passDrawIndirect, layerSetup, layerConfigure, layerNextDrawable, layerPrefetchDrawable, present;
        static MethodHandle passSetBytes, passSetBuffer, passSetTexture, samplerCreateCompare, layerDisplayTiming, pacingTake;
        static MethodHandle profileEnable, profileLabel, profileTrace, profileReport, profilePeaks, traceStats, traceEnable;

        static void init() {
            release = fn("mc_release", v(P), CRITICAL);
            contextCreate = fn("mc_context_create", r(P, ADDRESS, I));
            contextDestroy = fn("mc_context_destroy", v(P));
            contextName = fn("mc_context_name", v(P, ADDRESS, I));
            contextLimits = fn("mc_context_limits", v(P, ADDRESS));
            contextCompleted = fn("mc_context_completed", r(JAVA_LONG, P), CRITICAL);
            contextWait = fn("mc_context_wait", r(I, P, JAVA_LONG, JAVA_LONG));
            bufferCreate = fn("mc_buffer_create", r(P, P, JAVA_LONG, I));
            bufferContents = fn("mc_buffer_contents", r(P, P), CRITICAL);
            setLabel = fn("mc_set_label", v(P, ADDRESS));
            profileEnable = fn("mc_profile_enable", r(I, P, I));
            profileTrace = fn("mc_profile_trace", v(I));
            traceEnable = fn("mc_trace_enable", v(I));
            profileReport = fn("mc_profile_report", v(ADDRESS, I));
            profilePeaks = fn("mc_profile_peaks", v(ADDRESS, I));
            profileLabel = fn("mc_profile_label", v(ADDRESS));
            traceStats = fn("mc_trace_stats", v(ADDRESS));
            textureCreate = fn("mc_texture_create", r(P, P, I, I, I, I, I, I));
            textureView = fn("mc_texture_view", r(P, P, I, I));
            textureBufferView = fn("mc_texture_buffer_view", r(P, P, JAVA_LONG, JAVA_LONG, I));
            textureBufferAlignment = fn("mc_texture_buffer_alignment", r(JAVA_LONG, P, I));
            samplerCreate = fn("mc_sampler_create", r(P, P, I, I, I, I, I, JAVA_FLOAT, I));
            libraryCreate = fn("mc_library_create", r(P, P, ADDRESS, ADDRESS, I));
            pipelineCreate = fn("mc_pipeline_create", r(P, P, P, ADDRESS, P, ADDRESS, ADDRESS, ADDRESS, ADDRESS, I));
            depthStateCreate = fn("mc_depth_state_create", r(P, P, I, I));
            poolPush = fn("mc_pool_push", r(P));
            poolPop = fn("mc_pool_pop", v(P));
            frameBegin = fn("mc_frame_begin", r(P, P));
            frameCommit = fn("mc_frame_commit", v(P, JAVA_LONG));
            blitCopyBuffer = fn("mc_blit_copy_buffer", v(P, P, JAVA_LONG, P, JAVA_LONG, JAVA_LONG), CRITICAL);
            blitBufferToTexture = fn("mc_blit_buffer_to_texture", v(P, P, JAVA_LONG, JAVA_LONG, JAVA_LONG, P, I, I, I, I, I, I), CRITICAL);
            blitTextureToBuffer = fn("mc_blit_texture_to_buffer", v(P, P, I, I, I, I, I, P, JAVA_LONG, JAVA_LONG), CRITICAL);
            blitTextureToTexture = fn("mc_blit_texture_to_texture", v(P, P, P, I, I, I, I, I, I, I), CRITICAL);
            clearTexture = fn("mc_clear_texture", v(P, P, I, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_DOUBLE));
            clearRegion = fn("mc_clear_region", v(P, P, P, I, I, I, I, I, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
            passBegin = fn("mc_pass_begin", r(P, P, I, ADDRESS, ADDRESS, I, I, ADDRESS, P, I, I, JAVA_DOUBLE, I, I), CRITICAL);
            passEnd = fn("mc_pass_end", v(P), CRITICAL);
            passPushDebug = fn("mc_pass_push_debug", v(P, ADDRESS));
            passPopDebug = fn("mc_pass_pop_debug", v(P), CRITICAL);
            passSetPipeline = fn("mc_pass_set_pipeline", v(P, P, P, I, I, JAVA_FLOAT, JAVA_FLOAT), CRITICAL);
            passSetDepthClamp = fn("mc_pass_set_depth_clamp", v(P, I), CRITICAL);
            passSetScissor = fn("mc_pass_set_scissor", v(P, I, I, I, I, I, I), CRITICAL);
            passSetVertexBuffer = fn("mc_pass_set_vertex_buffer", v(P, I, P, JAVA_LONG), CRITICAL);
            passBind = fn("mc_pass_bind", v(P, I, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS), CRITICAL);
            passPushConstants = fn("mc_pass_push_constants", v(P, ADDRESS, I, I), CRITICAL);
            passDraw = fn("mc_pass_draw", v(P, I, I, I, I, I), CRITICAL);
            passDrawIndexed = fn("mc_pass_draw_indexed", v(P, I, I, I, P, JAVA_LONG, I, I, I), CRITICAL);
            passMultiDrawIndexed = fn("mc_pass_multi_draw_indexed", v(P, I, ADDRESS, I, I, P, JAVA_LONG, I, I), CRITICAL);
            passMultiDraw = fn("mc_pass_multi_draw", v(P, I, ADDRESS, I, I, I), CRITICAL);
            passDrawIndexedIndirect = fn("mc_pass_draw_indexed_indirect", v(P, I, I, P, JAVA_LONG, P, JAVA_LONG, I, I), CRITICAL);
            passDrawSectionsCulled = fn(
                "mc_pass_draw_sections_culled",
                r(I, P, I, I, P, P, JAVA_LONG, I, I, P, P, I, I, I, ADDRESS, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_FLOAT), CRITICAL
            );
            passDrawIndirect = fn("mc_pass_draw_indirect", v(P, I, P, JAVA_LONG, I, I), CRITICAL);
            layerSetup = fn("mc_layer_setup", v(P, P));
            layerConfigure = fn("mc_layer_configure", v(P, I, I, I));
            layerNextDrawable = fn("mc_layer_next_drawable", r(P, P, I));
            layerPrefetchDrawable = fn("mc_layer_prefetch_drawable", v(P, I));
            present = fn("mc_present", v(P, P, P, I, I, JAVA_DOUBLE));
            layerDisplayTiming = fn("mc_layer_display_timing", v(ADDRESS));
            pacingTake = fn("mc_pacing_take", v(ADDRESS));
            passSetBytes = fn("mc_pass_set_bytes", v(P, I, ADDRESS, I, I), CRITICAL);
            passSetBuffer = fn("mc_pass_set_buffer", v(P, I, P, JAVA_LONG, I), CRITICAL);
            passSetTexture = fn("mc_pass_set_texture", v(P, I, P, P, I), CRITICAL);
            samplerCreateCompare = fn("mc_sampler_create_compare", r(P, P));
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }

    // ---- context ----

    public static long contextCreate() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment err = arena.allocate(1024);
            long ctx = (long) Handles.contextCreate.invokeExact(err, 1024);
            if (ctx == 0L) {
                throw new IllegalStateException(err.getString(0));
            }
            return ctx;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void contextDestroy(long ctx) {
        try {
            Handles.contextDestroy.invokeExact(ctx);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static String contextName(long ctx) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(256);
            Handles.contextName.invokeExact(ctx, buf, 256);
            return buf.getString(0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long[] contextLimits(long ctx) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_LONG, 4);
            Handles.contextLimits.invokeExact(ctx, out);
            return out.toArray(JAVA_LONG);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long contextCompleted(long ctx) {
        try {
            return (long) Handles.contextCompleted.invokeExact(ctx);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static boolean contextWait(long ctx, long index, long timeoutNs) {
        try {
            return (int) Handles.contextWait.invokeExact(ctx, index, timeoutNs) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void release(long obj) {
        try {
            Handles.release.invokeExact(obj);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- resources ----

    public static long bufferCreate(long ctx, long size, boolean gpuOnly) {
        try {
            return (long) Handles.bufferCreate.invokeExact(ctx, size, gpuOnly ? 1 : 0);
        } catch (Throwable t) {
            throw rethrow(t);
        } finally {
            if (HitchTrace.ENABLED) {
                HitchTrace.buffer(size);
            }
        }
    }

    public static long bufferContents(long buffer) {
        try {
            return (long) Handles.bufferContents.invokeExact(buffer);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** GPU time per render pass is logged every few hundred frames with -Dhoneycrisp.gpuProfile=true. */
    public static final boolean GPU_PROFILE = Boolean.getBoolean("honeycrisp.gpuProfile");

    /** Sets up GPU timing per pass; `log` also logs it every few hundred frames, otherwise only HitchTrace reads it. */
    public static boolean profileEnable(long ctx, boolean log) {
        try {
            return (int) Handles.profileEnable.invokeExact(ctx, log ? 1 : 0) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Turns on recording what the GPU and the display did with each frame (mc_trace_enable). */
    public static void traceEnable(boolean on) {
        try {
            Handles.traceEnable.invokeExact(on ? 1 : 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Turns GPU timing per pass on or off for the hitch trace. */
    public static void profileTrace(boolean on) {
        try {
            Handles.profileTrace.invokeExact(on ? 1 : 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** GPU milliseconds per submit for the costliest passes since the last call; empty when there is no profile. */
    public static String profileReport() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(2048);
            Handles.profileReport.invokeExact(buf, 2048);
            return buf.getString(0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The most one submit spent in each pass since the last call, then a newline and the slowest submit's breakdown;
     * empty when there is no profile.
     */
    public static String profilePeaks() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(2048);
            Handles.profilePeaks.invokeExact(buf, 2048);
            return buf.getString(0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Names the next render pass in the GPU profile; does nothing unless profiling. */
    public static void profileLabel(String label) {
        if (!GPU_PROFILE && !HitchTrace.profiling()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            Handles.profileLabel.invokeExact(arena.allocateFrom(label));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void setLabel(long obj, String label) {
        try (Arena arena = Arena.ofConfined()) {
            Handles.setLabel.invokeExact(obj, arena.allocateFrom(label));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** GPU and display counters since the last call (mc_trace_stats), for HitchTrace. */
    public static void traceStats(long[] out) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(JAVA_LONG, out.length);
            Handles.traceStats.invokeExact(seg);
            MemorySegment.copy(seg, JAVA_LONG, 0L, out, 0, out.length);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long textureCreate(long ctx, int format, int width, int height, int layers, int mips, int usage) {
        long start = System.nanoTime();
        try {
            return (long) Handles.textureCreate.invokeExact(ctx, format, width, height, layers, mips, usage);
        } catch (Throwable t) {
            throw rethrow(t);
        } finally {
            if (HitchTrace.ENABLED) {
                HitchTrace.texture(System.nanoTime() - start, width, height);
            }
        }
    }

    public static long textureView(long texture, int baseMip, int mipCount) {
        try {
            return (long) Handles.textureView.invokeExact(texture, baseMip, mipCount);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long textureBufferView(long buffer, long offset, long length, int format) {
        try {
            return (long) Handles.textureBufferView.invokeExact(buffer, offset, length, format);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long textureBufferAlignment(long ctx, int format) {
        try {
            return (long) Handles.textureBufferAlignment.invokeExact(ctx, format);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long samplerCreate(long ctx, int addrU, int addrV, int minF, int magF, int mipF, float maxLod, int aniso) {
        try {
            return (long) Handles.samplerCreate.invokeExact(ctx, addrU, addrV, minF, magF, mipF, maxLod, aniso);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Returns the library pointer, or throws with the Metal compiler's message. */
    public static long libraryCreate(long ctx, String source) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment err = arena.allocate(8192);
            long start = System.nanoTime();
            long lib = (long) Handles.libraryCreate.invokeExact(ctx, arena.allocateFrom(source), err, 8192);
            if (HitchTrace.ENABLED) {
                HitchTrace.compiled(HitchTrace.LIBRARY, System.nanoTime() - start, source.length() + " chars");
            }
            if (lib == 0L) {
                throw new IllegalStateException(err.getString(0));
            }
            return lib;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long pipelineCreate(long ctx, long vertLib, String vertEntry, long fragLib, String fragEntry, int[] desc, String label) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment err = arena.allocate(8192);
            MemorySegment descSeg = arena.allocateFrom(JAVA_INT, desc);
            long start = System.nanoTime();
            long pso = (long) Handles.pipelineCreate.invokeExact(
                ctx, vertLib, arena.allocateFrom(vertEntry), fragLib, arena.allocateFrom(fragEntry), descSeg, arena.allocateFrom(label), err, 8192
            );
            if (HitchTrace.ENABLED) {
                HitchTrace.compiled(HitchTrace.PIPELINE, System.nanoTime() - start, label);
            }
            if (pso == 0L) {
                throw new IllegalStateException(err.getString(0));
            }
            return pso;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long depthStateCreate(long ctx, int compare, boolean write) {
        try {
            return (long) Handles.depthStateCreate.invokeExact(ctx, compare, write ? 1 : 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- frames and blits ----

    /** Opens an autorelease pool on this thread (mc_pool_push); {@link #poolPop} drains it. */
    public static long poolPush() {
        try {
            return (long) Handles.poolPush.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void poolPop(long token) {
        try {
            Handles.poolPop.invokeExact(token);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long frameBegin(long ctx) {
        try {
            return (long) Handles.frameBegin.invokeExact(ctx);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void frameCommit(long frame, long index) {
        try {
            Handles.frameCommit.invokeExact(frame, index);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void blitCopyBuffer(long frame, long src, long srcOff, long dst, long dstOff, long size) {
        try {
            Handles.blitCopyBuffer.invokeExact(frame, src, srcOff, dst, dstOff, size);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void blitBufferToTexture(
        long frame, long src, long srcOff, long bytesPerRow, long bytesPerImage, long tex, int slice, int mip, int x, int y, int w, int h
    ) {
        try {
            Handles.blitBufferToTexture.invokeExact(frame, src, srcOff, bytesPerRow, bytesPerImage, tex, slice, mip, x, y, w, h);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void blitTextureToBuffer(long frame, long tex, int mip, int x, int y, int w, int h, long dst, long dstOff, long bytesPerRow) {
        try {
            Handles.blitTextureToBuffer.invokeExact(frame, tex, mip, x, y, w, h, dst, dstOff, bytesPerRow);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void blitTextureToTexture(long frame, long src, long dst, int mip, int srcX, int srcY, int dstX, int dstY, int w, int h) {
        try {
            Handles.blitTextureToTexture.invokeExact(frame, src, dst, mip, srcX, srcY, dstX, dstY, w, h);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void clearTexture(long frame, long tex, int mip, float r, float g, float b, float a, double depth) {
        try {
            Handles.clearTexture.invokeExact(frame, tex, mip, r, g, b, a, depth);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void clearRegion(long frame, long color, long depth, int mip, int x, int y, int w, int h, float r, float g, float b, float a, float d) {
        try {
            Handles.clearRegion.invokeExact(frame, color, depth, mip, x, y, w, h, r, g, b, a, d);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- render passes (hot path) ----

    public static long passBegin(
        long frame, long[] colors, int[] colorMips, int clearMask, float[] clearColors, long depth, int depthMip, boolean clearDepth,
        double clearDepthValue, int width, int height
    ) {
        return passBegin(frame, colors, colorMips, clearMask, 0, clearColors, depth, depthMip, clearDepth, clearDepthValue, width, height);
    }

    /**
     * A pass without depth whose full-screen draw writes every pixel of each color target (mip 0) without reading it:
     * their previous contents are not loaded into tile memory.
     */
    public static long passBeginOverwrite(long frame, long[] colors, int width, int height) {
        return passBegin(frame, colors, new int[colors.length], 0, (1 << colors.length) - 1, new float[colors.length * 4], 0L, 0, false, 0.0,
            width, height);
    }

    private static long passBegin(
        long frame, long[] colors, int[] colorMips, int clearMask, int discardMask, float[] clearColors, long depth, int depthMip,
        boolean clearDepth, double clearDepthValue, int width, int height
    ) {
        try {
            return (long) Handles.passBegin.invokeExact(
                frame,
                colors.length,
                MemorySegment.ofArray(colors),
                MemorySegment.ofArray(colorMips),
                clearMask,
                discardMask,
                MemorySegment.ofArray(clearColors),
                depth,
                depthMip,
                clearDepth ? 1 : 0,
                clearDepthValue,
                width,
                height
            );
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passEnd(long enc) {
        try {
            Handles.passEnd.invokeExact(enc);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passPushDebug(long enc, String label) {
        try (Arena arena = Arena.ofConfined()) {
            Handles.passPushDebug.invokeExact(enc, arena.allocateFrom(label));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passPopDebug(long enc) {
        try {
            Handles.passPopDebug.invokeExact(enc);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passSetPipeline(long enc, long pso, long depthState, boolean cull, boolean wireframe, float depthBias, float slopeScale) {
        try {
            Handles.passSetPipeline.invokeExact(enc, pso, depthState, cull ? 1 : 0, wireframe ? 1 : 0, depthBias, slopeScale);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passSetDepthClamp(long enc, boolean clamp) {
        try {
            Handles.passSetDepthClamp.invokeExact(enc, clamp ? 1 : 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passSetScissor(long enc, int x, int y, int w, int h, int targetW, int targetH) {
        try {
            Handles.passSetScissor.invokeExact(enc, x, y, w, h, targetW, targetH);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passSetVertexBuffer(long enc, int slot, long buffer, long offset) {
        try {
            Handles.passSetVertexBuffer.invokeExact(enc, slot, buffer, offset);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passBind(long enc, int count, int[] kinds, int[] stages, long[] objs, long[] samplers, long[] offsets) {
        try {
            Handles.passBind.invokeExact(
                enc,
                count,
                MemorySegment.ofArray(kinds),
                MemorySegment.ofArray(stages),
                MemorySegment.ofArray(objs),
                MemorySegment.ofArray(samplers),
                MemorySegment.ofArray(offsets)
            );
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passPushConstants(long enc, MemorySegment data, int length, int stages) {
        try {
            Handles.passPushConstants.invokeExact(enc, data, length, stages);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passDraw(long enc, int prim, int vertexStart, int vertexCount, int instanceCount, int baseInstance) {
        try {
            Handles.passDraw.invokeExact(enc, prim, vertexStart, vertexCount, instanceCount, baseInstance);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passDrawIndexed(
        long enc, int prim, int indexCount, int indexType, long indexBuffer, long indexOffset, int instanceCount, int baseVertex, int baseInstance
    ) {
        try {
            Handles.passDrawIndexed.invokeExact(enc, prim, indexCount, indexType, indexBuffer, indexOffset, instanceCount, baseVertex, baseInstance);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passMultiDrawIndexed(
        long enc, int prim, MemorySegment params, int drawCount, int indexType, long indexBuffer, long indexBase, int instanceCount, int baseInstance
    ) {
        try {
            Handles.passMultiDrawIndexed.invokeExact(enc, prim, params, drawCount, indexType, indexBuffer, indexBase, instanceCount, baseInstance);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passMultiDraw(long enc, int prim, MemorySegment params, int drawCount, int instanceCount, int baseInstance) {
        try {
            Handles.passMultiDraw.invokeExact(enc, prim, params, drawCount, instanceCount, baseInstance);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passDrawIndexedIndirect(
        long enc, int prim, int indexType, long indexBuffer, long indexOffset, long argBuffer, long argOffset, int drawCount, int stride
    ) {
        try {
            Handles.passDrawIndexedIndirect.invokeExact(enc, prim, indexType, indexBuffer, indexOffset, argBuffer, argOffset, drawCount, stride);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * passDrawIndexedIndirect for chunk sections, without those that cannot land in the shadow map of `matrix` (see
     * mc_pass_draw_sections_culled). Returns the number of draws issued.
     */
    public static int passDrawSectionsCulled(
        long enc, int prim, int indexType, long indexBuffer, long argBuffer, long argOffset, int drawCount, int stride, long args, long instances,
        int instanceCount, int instanceStride, int posOffset, MemorySegment matrix, double tx, double ty, double tz, float slack
    ) {
        try {
            return (int) Handles.passDrawSectionsCulled.invokeExact(
                enc, prim, indexType, indexBuffer, argBuffer, argOffset, drawCount, stride, args, instances, instanceCount, instanceStride, posOffset, matrix, tx, ty, tz, slack
            );
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passDrawIndirect(long enc, int prim, long argBuffer, long argOffset, int drawCount, int stride) {
        try {
            Handles.passDrawIndirect.invokeExact(enc, prim, argBuffer, argOffset, drawCount, stride);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- presentation ----

    public static void layerSetup(long ctx, long layer) {
        try {
            Handles.layerSetup.invokeExact(ctx, layer);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void layerConfigure(long layer, int width, int height, boolean vsync) {
        try {
            Handles.layerConfigure.invokeExact(layer, width, height, vsync ? 1 : 0);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The next drawable, or 0 when none came within timeoutMs (the frame then goes unpresented). */
    public static long layerNextDrawable(long layer, int timeoutMs) {
        try {
            long start = System.nanoTime();
            long drawable = (long) Handles.layerNextDrawable.invokeExact(layer, timeoutMs);
            if (HitchTrace.ENABLED) {
                HitchTrace.add(HitchTrace.DRAWABLE_ACQUIRE, System.nanoTime() - start);
            }
            return drawable;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Starts fetching the next drawable and waits up to timeoutMs for it without taking it (0: only starts it);
     * layerNextDrawable then returns it at once.
     */
    public static void layerPrefetchDrawable(long layer, int timeoutMs) {
        try {
            long start = System.nanoTime();
            Handles.layerPrefetchDrawable.invokeExact(layer, timeoutMs);
            if (HitchTrace.ENABLED) {
                HitchTrace.add(HitchTrace.DRAWABLE_PREFETCH, System.nanoTime() - start);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** `minDuration` > 0: the drawable goes on screen no sooner than that many seconds after the previous one. */
    public static void present(long frame, long drawable, long srcTexture, int width, int height, double minDuration) {
        try {
            Handles.present.invokeExact(frame, drawable, srcTexture, width, height, minDuration);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Since the last call: the longest GPU time of a command buffer in seconds, the frames that reached the screen and
     * the frames that were dropped (mc_pacing_take).
     */
    public static void pacingTake(double[] out) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(JAVA_DOUBLE, 3);
            Handles.pacingTake.invokeExact(seg);
            MemorySegment.copy(seg, JAVA_DOUBLE, 0L, out, 0, 3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The display the game window was on when the layer was last configured, in seconds: its shortest refresh interval,
     * and for a variable refresh display its longest one and the step between them (both 0 for a fixed refresh rate).
     */
    public static void layerDisplayTiming(double[] out) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(JAVA_DOUBLE, 3);
            Handles.layerDisplayTiming.invokeExact(seg);
            MemorySegment.copy(seg, JAVA_DOUBLE, 0L, out, 0, 3);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ---- shader pipeline bindings ----

    public static void passSetBytes(long enc, int index, MemorySegment data, int length, int stages) {
        try {
            Handles.passSetBytes.invokeExact(enc, index, data, length, stages);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passSetBuffer(long enc, int index, long buffer, long offset, int stages) {
        try {
            Handles.passSetBuffer.invokeExact(enc, index, buffer, offset, stages);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void passSetTexture(long enc, int index, long texture, long sampler, int stages) {
        try {
            Handles.passSetTexture.invokeExact(enc, index, texture, sampler, stages);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static long samplerCreateCompare(long ctx) {
        try {
            return (long) Handles.samplerCreateCompare.invokeExact(ctx);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
