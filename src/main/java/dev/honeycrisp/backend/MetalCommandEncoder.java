package dev.honeycrisp.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * Records into one MTLCommandBuffer per submit. Metal tracks hazards between passes itself, so
 * unlike the Vulkan backend no barriers are needed; we only pace the CPU to stay at most one
 * submit ahead of the GPU and defer releases until the GPU is done with them.
 */
public class MetalCommandEncoder implements CommandEncoderBackend {
    private static final long SUBMIT_TIMEOUT_NS = 5_000_000_000L;
    /**
     * Submits the GPU may still be working on while the next one is recorded. One keeps what is on screen close to the
     * mouse when the GPU is the limit; -Dhoneycrisp.framesInFlight=2 trades a frame of input lag for a busier GPU.
     */
    private static final int FRAMES_IN_FLIGHT = Math.clamp(Integer.getInteger("honeycrisp.framesInFlight", 1), 1, 3);

    private final MetalDevice device;
    private final MetalTransientMemory transientMemory;
    private final ArrayDeque<PendingDestroy> destroyQueue = new ArrayDeque<>();
    private List<Destroyable> currentDestroys = new ArrayList<>();
    private long currentSubmitIndex = 1L;
    private long completedSubmitIndex = 0L;
    private long frame;
    /** The autorelease pool opened with the current frame (MetalNative.poolPush). */
    private long framePool;
    @Nullable
    private MetalRenderPass currentRenderPass;
    // A whole-texture clear is held back until the next command: when that is a render pass drawing to the texture,
    // the clear becomes the pass's load action instead of a pass of its own that stores what the next one loads back.
    @Nullable
    private MetalTexture pendingColorClear;
    private final float[] pendingColor = new float[4];
    @Nullable
    private MetalTexture pendingDepthClear;
    private double pendingDepth;

    private static final boolean STATS = Boolean.getBoolean("honeycrisp.stats") || Integer.getInteger("honeycrisp.bench", 0) > 0;
    private long statsStart;
    private long statsWait;
    private int statsFrames;

    private void recordStats(final long waitNs) {
        long now = System.nanoTime();
        if (this.statsStart == 0L) {
            this.statsStart = now;
        }
        this.statsWait += waitNs;
        this.statsFrames++;
        if (now - this.statsStart >= 5_000_000_000L) {
            double ms = (now - this.statsStart) / 1e6 / this.statsFrames;
            org.slf4j.LoggerFactory.getLogger("Honeycrisp").info(
                String.format(java.util.Locale.ROOT, "frame %.2f ms (%.0f fps), waiting on GPU %.2f ms", ms, 1000 / ms, this.statsWait / 1e6 / this.statsFrames)
            );
            this.statsStart = now;
            this.statsWait = 0L;
            this.statsFrames = 0;
        }
    }

    // ---- one-frame trace (-Dhoneycrisp.trace=true): dumps passes and draws of the 600th submit ----
    private final StringBuilder traceLog = new StringBuilder();

    boolean tracing() {
        return this.currentSubmitIndex == 600L;
    }

    void trace(final String line) {
        this.traceLog.append(line).append('\n');
    }

    private record PendingDestroy(long submitIndex, List<Destroyable> objects) {
    }

    MetalCommandEncoder(final MetalDevice device) {
        this.device = device;
        this.transientMemory = new MetalTransientMemory(device, this);
    }

    /**
     * The command buffer being recorded for the current submit, created on first use. Clears still held back are
     * recorded first; only buffer-to-buffer copies, which cannot touch a texture, go around this with rawFrame().
     */
    long frame() {
        this.flushPendingClears();
        return this.rawFrame();
    }

    private long rawFrame() {
        if (this.frame == 0L) {
            // Metal autoreleases objects while a frame is recorded, and Java's threads never drain a pool of their own.
            this.framePool = MetalNative.poolPush();
            this.frame = MetalNative.frameBegin(this.device.context());
        }
        return this.frame;
    }

    private void flushPendingClears() {
        this.flushPendingColorClear();
        this.flushPendingDepthClear();
    }

    private void flushPendingColorClear() {
        MetalTexture color = this.pendingColorClear;
        if (color != null) {
            this.pendingColorClear = null;
            float[] c = this.pendingColor;
            MetalNative.clearTexture(this.rawFrame(), color.handle(), -1, c[0], c[1], c[2], c[3], 0.0);
        }
    }

    private void flushPendingDepthClear() {
        MetalTexture depth = this.pendingDepthClear;
        if (depth != null) {
            this.pendingDepthClear = null;
            MetalNative.clearTexture(this.rawFrame(), depth.handle(), -1, 0.0F, 0.0F, 0.0F, 0.0F, this.pendingDepth);
        }
    }

    void queueForDestroy(final Destroyable destroyable) {
        this.currentDestroys.add(destroyable);
    }

    long currentSubmitIndex() {
        return this.currentSubmitIndex;
    }

    /** The latest submit the GPU is known to have finished. */
    long completedSubmitIndex() {
        return Math.max(this.completedSubmitIndex, MetalNative.contextCompleted(this.device.context()));
    }

    private void checkNotInPass(final String what) {
        if (this.currentRenderPass != null) {
            throw new IllegalStateException("Cannot " + what + " while inside a render pass");
        }
    }

    @Override
    public void submit() {
        this.checkNotInPass("submit");
        if (MetalRenderPass.TRACE_ENABLED && this.tracing()) {
            org.slf4j.LoggerFactory.getLogger("Honeycrisp").info("Frame trace:\n{}", this.traceLog);
        }
        this.transientMemory.endSubmit();
        long index = this.currentSubmitIndex;
        MetalNative.frameCommit(this.frame(), index);
        this.frame = 0L;
        MetalNative.poolPop(this.framePool);
        this.framePool = 0L;
        this.destroyQueue.add(new PendingDestroy(index, this.currentDestroys));
        this.currentDestroys = new ArrayList<>();
        this.currentSubmitIndex++;
        long waitStart = System.nanoTime();
        long awaited = index - FRAMES_IN_FLIGHT;
        if (HitchTrace.ENABLED) {
            HitchTrace.mark("submit");
        }
        if (!this.awaitSubmitCompletion(awaited, SUBMIT_TIMEOUT_NS, HitchTrace.GPU_WAIT)) {
            throw new IllegalStateException("Honeycrisp: GPU did not finish submit " + awaited + " within 5s");
        }
        if (STATS) {
            this.recordStats(System.nanoTime() - waitStart);
        }
    }

    boolean awaitSubmitCompletion(final long submitIndex, final long timeoutNs) {
        return this.awaitSubmitCompletion(submitIndex, timeoutNs, HitchTrace.FENCE_WAIT);
    }

    /** `waitKind`: the HitchTrace category the wait is counted under. */
    private boolean awaitSubmitCompletion(final long submitIndex, final long timeoutNs, final int waitKind) {
        if (this.completedSubmitIndex < submitIndex) {
            if (submitIndex >= this.currentSubmitIndex) {
                if (timeoutNs == 0L) {
                    return false;
                }
                throw new IllegalStateException("Cannot wait on a fence for the current submit");
            }
            long waitStart = System.nanoTime();
            boolean done = MetalNative.contextWait(this.device.context(), submitIndex, timeoutNs);
            if (HitchTrace.ENABLED) {
                HitchTrace.add(waitKind, System.nanoTime() - waitStart);
            }
            if (!done) {
                return false;
            }
            this.completedSubmitIndex = submitIndex;
        }
        this.runCompletedDestroys();
        return true;
    }

    private void runCompletedDestroys() {
        long start = System.nanoTime();
        this.runCompletedDestroysNow();
        if (HitchTrace.ENABLED) {
            long took = System.nanoTime() - start;
            if (took >= 100_000L) {
                HitchTrace.add(HitchTrace.DESTROY, took);
            }
        }
    }

    private void runCompletedDestroysNow() {
        while (!this.destroyQueue.isEmpty() && this.destroyQueue.peekFirst().submitIndex() <= this.completedSubmitIndex) {
            for (Destroyable destroyable : this.destroyQueue.pollFirst().objects()) {
                destroyable.destroy();
            }
        }
    }

    void destroy() {
        if (this.frame != 0L) {
            this.submit();
        }
        MetalNative.contextWait(this.device.context(), this.currentSubmitIndex - 1L, -1L);
        this.completedSubmitIndex = this.currentSubmitIndex - 1L;
        this.runCompletedDestroys();
        this.currentDestroys.forEach(Destroyable::destroy);
        this.currentDestroys.clear();
        this.transientMemory.destroy();
    }

    @Override
    public TransientMemory transientMemory() {
        return this.transientMemory;
    }

    // ---- render passes ----

    @Override
    public RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
        this.checkNotInPass("begin a render pass");
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments = descriptor.colorAttachments();
        int count = colorAttachments.size();
        long[] colors = new long[count];
        int[] mips = new int[count];
        float[] clears = new float[Math.max(count, 1) * 4];
        int clearMask = 0;
        int width = 0;
        int height = 0;
        for (int i = 0; i < count; i++) {
            RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = colorAttachments.get(i);
            if (attachment == null) {
                continue;
            }
            MetalTextureView view = (MetalTextureView)attachment.textureView();
            colors[i] = view.texture().handle();
            mips[i] = view.baseMipLevel();
            width = view.getWidth(0);
            height = view.getHeight(0);
            if (view.texture() == this.pendingColorClear && view.texture().getMipLevels() == 1) {
                this.pendingColorClear = null;
                clearMask |= 1 << i;
                System.arraycopy(this.pendingColor, 0, clears, i * 4, 4);
            }
            if (attachment.clearValue().isPresent()) {
                Vector4fc c = attachment.clearValue().get();
                clearMask |= 1 << i;
                clears[i * 4] = c.x();
                clears[i * 4 + 1] = c.y();
                clears[i * 4 + 2] = c.z();
                clears[i * 4 + 3] = c.w();
            }
        }
        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        long depth = 0L;
        int depthMip = 0;
        boolean clearDepth = false;
        double clearDepthValue = 0.0;
        if (depthAttachment != null) {
            MetalTextureView view = (MetalTextureView)depthAttachment.textureView();
            depth = view.texture().handle();
            depthMip = view.baseMipLevel();
            if (count == 0 || width == 0) {
                width = view.getWidth(0);
                height = view.getHeight(0);
            }
            if (view.texture() == this.pendingDepthClear && view.texture().getMipLevels() == 1) {
                this.pendingDepthClear = null;
                clearDepth = true;
                clearDepthValue = this.pendingDepth;
            }
            if (depthAttachment.clearValue().isPresent()) {
                clearDepth = true;
                clearDepthValue = depthAttachment.clearValue().getAsDouble();
            }
        }
        if (MetalRenderPass.TRACE_ENABLED && this.tracing()) {
            StringBuilder b = new StringBuilder("  PASS ").append(descriptor.label().get()).append(" colors=");
            for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> a : colorAttachments) {
                b.append(a == null ? "-" : a.textureView().texture().getLabel() + "(" + a.textureView().texture().getFormat() + " " + width + "x" + height + ")").append(',');
            }
            b.append(" depth=").append(depthAttachment == null ? "-" : depthAttachment.textureView().texture().getLabel());
            this.trace(b.toString());
        }
        boolean main = MetalShaders.ENABLED && depth != 0L && count == 1 && "Main".equals(descriptor.label().get());
        long frame = this.frame();
        if (main) {
            long start = System.nanoTime();
            this.device.shaders().prepareMain(frame, colors[0], depth, width, height);
            if (HitchTrace.ENABLED) {
                HitchTrace.mark("main");
                HitchTrace.add(HitchTrace.SHADERS, System.nanoTime() - start);
            }
        }
        if (MetalNative.GPU_PROFILE || HitchTrace.profiling()) {
            MetalNative.profileLabel(descriptor.label().get());
        }
        long enc = MetalNative.passBegin(frame, colors, mips, clearMask, clears, depth, depthMip, clearDepth, clearDepthValue, width, height);
        boolean hand = MetalShaders.ENABLED && "Item in hand".equals(descriptor.label().get());
        this.currentRenderPass = new MetalRenderPass(this.device, this, enc, descriptor.renderArea(), width, height, depthAttachment != null, main, hand);
        if (hand) {
            this.device.shaders().bindHand(enc, width, height);
        }
        if (main) {
            this.currentRenderPass.setMainTargets(colors[0], depth);
            this.device.shaders().bindFrame(enc);
        }
        return this.currentRenderPass;
    }

    /**
     * Ends the current main-pass encoder, snapshots the opaque scene for reflections, and opens a new
     * encoder on the same targets (loading their contents). The caller re-applies its state.
     */
    long splitMainPass(final MetalRenderPass pass, final long color, final long depth, final int width, final int height) {
        pass.endForSplit();
        this.device.shaders().snapshotOpaque(this.frame());
        MetalNative.profileLabel("Main (translucent)");
        long enc = MetalNative.passBegin(this.frame(), new long[]{color}, new int[]{0}, 0, new float[4], depth, 0, false, 0.0, width, height);
        this.device.shaders().bindFrame(enc);
        return enc;
    }

    @Override
    public void submitRenderPass() {
        if (this.currentRenderPass == null) {
            throw new IllegalStateException("Cannot submit a renderpass if one hasn't been started!");
        }
        this.currentRenderPass.end();
        boolean main = this.currentRenderPass.isMain();
        this.currentRenderPass = null;
        if (main) {
            long start = System.nanoTime();
            this.device.shaders().endMain(this.frame());
            if (HitchTrace.ENABLED) {
                HitchTrace.mark("mainEnd");
                HitchTrace.add(HitchTrace.SHADERS, System.nanoTime() - start);
            }
        }
    }

    // ---- clears ----

    private static long handle(final GpuTexture texture) {
        return ((MetalTexture)texture).handle();
    }

    @Override
    public void clearColorTexture(final GpuTexture colorTexture, final Vector4fc clearColor) {
        this.checkNotInPass("clear");
        if (this.pendingColorClear != colorTexture) {
            this.flushPendingColorClear();
        }
        this.pendingColorClear = (MetalTexture)colorTexture;
        this.pendingColor[0] = clearColor.x();
        this.pendingColor[1] = clearColor.y();
        this.pendingColor[2] = clearColor.z();
        this.pendingColor[3] = clearColor.w();
    }

    @Override
    public void clearColorAndDepthTextures(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture, final double clearDepth) {
        this.clearColorTexture(colorTexture, clearColor);
        this.clearDepthTexture(depthTexture, clearDepth);
    }

    @Override
    public void clearColorAndDepthTextures(
        final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture, final double clearDepth,
        final int regionX, final int regionY, final int regionWidth, final int regionHeight, final int mipLevel
    ) {
        this.checkNotInPass("clear");
        MetalNative.clearRegion(
            this.frame(), handle(colorTexture), handle(depthTexture), mipLevel, regionX, regionY, regionWidth, regionHeight,
            clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(), (float)clearDepth
        );
    }

    @Override
    public void clearDepthTexture(final GpuTexture depthTexture, final double clearDepth) {
        this.checkNotInPass("clear");
        if (this.pendingDepthClear != depthTexture) {
            this.flushPendingDepthClear();
        }
        this.pendingDepthClear = (MetalTexture)depthTexture;
        this.pendingDepth = clearDepth;
    }

    // ---- copies ----

    @Override
    public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
        this.checkNotInPass("write to a buffer");
        int size = data.remaining();
        if (MetalRenderPass.TRACE_ENABLED && this.tracing()) {
            this.trace("  WRITE " + destination.buffer().getClass().getSimpleName() + " size=" + size + " bufsize=" + destination.buffer().size() + " usage=" + destination.buffer().usage());
        }
        if (HitchTrace.ENABLED) {
            HitchTrace.upload(size);
        }
        ((MetalBuffer)destination.buffer()).recordWrite(destination.offset(), data);
        GpuBufferSlice staging = this.transientMemory.uploadStaging(data, 4L, GpuBuffer.USAGE_COPY_SRC);
        ((MetalBuffer)destination.buffer()).recordGpuWrite(
            this.currentSubmitIndex, destination.offset(), size, ((MetalBuffer)staging.buffer()).contents() + staging.offset()
        );
        MetalNative.blitCopyBuffer(
            this.rawFrame(), ((MetalBuffer)staging.buffer()).handle(), staging.offset(), ((MetalBuffer)destination.buffer()).handle(), destination.offset(), size
        );
    }

    @Override
    public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
        this.checkNotInPass("copy buffers");
        if (MetalRenderPass.TRACE_ENABLED && this.tracing()) {
            this.trace("  COPY size=" + source.length());
        }
        MetalBuffer from = (MetalBuffer)source.buffer();
        long bytes = from.readable(this.currentSubmitIndex, this.completedSubmitIndex(), source.offset());
        ((MetalBuffer)target.buffer()).recordGpuWrite(
            this.currentSubmitIndex, target.offset(), source.length(), bytes != 0L && from.readableLength() >= source.length() ? bytes : 0L
        );
        MetalNative.blitCopyBuffer(
            this.rawFrame(), ((MetalBuffer)source.buffer()).handle(), source.offset(), ((MetalBuffer)target.buffer()).handle(), target.offset(), source.length()
        );
    }

    @Override
    public void writeToTexture(
        final GpuTexture destination, final ByteBuffer source, final int mipLevel, final int depthOrLayer, final int destX, final int destY,
        final int width, final int height
    ) {
        this.checkNotInPass("write to a texture");
        int bpp = destination.getFormat().blockSize();
        if (HitchTrace.ENABLED) {
            HitchTrace.upload(source.remaining());
        }
        GpuBufferSlice staging = this.transientMemory.uploadStaging(source, Math.max(16L, bpp), GpuBuffer.USAGE_COPY_SRC);
        MetalNative.blitBufferToTexture(
            this.frame(), ((MetalBuffer)staging.buffer()).handle(), staging.offset(), (long)width * bpp, (long)width * height * bpp,
            handle(destination), depthOrLayer, mipLevel, destX, destY, width, height
        );
    }

    @Override
    public void copyBufferToTexture(
        final GpuBufferSlice source, final int sourceX, final int sourceY, final int sourceWidth, final int sourceHeight, final GpuTexture destination,
        final int destinationX, final int destinationY, final int copyWidth, final int copyHeight, final int mipLevel, final int arrayLayer
    ) {
        this.checkNotInPass("copy to a texture");
        int bpp = destination.getFormat().blockSize();
        long skip = ((long)sourceX + (long)sourceY * sourceWidth) * bpp;
        MetalNative.blitBufferToTexture(
            this.frame(), ((MetalBuffer)source.buffer()).handle(), source.offset() + skip, (long)sourceWidth * bpp, (long)sourceWidth * sourceHeight * bpp,
            handle(destination), arrayLayer, mipLevel, destinationX, destinationY, copyWidth, copyHeight
        );
    }

    @Override
    public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel) {
        this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
    }

    @Override
    public void copyTextureToBuffer(
        final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel,
        final int x, final int y, final int width, final int height
    ) {
        this.checkNotInPass("copy from a texture");
        int bpp = source.getFormat() == com.mojang.renderpearl.api.GpuFormat.D32_FLOAT_S8_UINT ? 4 : source.getFormat().blockSize();
        MetalNative.blitTextureToBuffer(
            this.frame(), handle(source), mipLevel, x, y, width, height, ((MetalBuffer)destination).handle(), offset, (long)width * bpp
        );
        this.queueForDestroy(callback::run);
    }

    @Override
    public void copyTextureToTexture(
        final GpuTexture source, final GpuTexture destination, final int mipLevel, final int destX, final int destY,
        final int sourceX, final int sourceY, final int width, final int height
    ) {
        this.checkNotInPass("copy textures");
        MetalNative.blitTextureToTexture(this.frame(), handle(source), handle(destination), mipLevel, sourceX, sourceY, destX, destY, width, height);
    }

    // ---- sync and queries ----

    @Override
    public GpuFence createFence() {
        // Deliberately one submit late. Minecraft fences its ring buffers (MappableRingBuffer) right after submit(),
        // when the current submit is still empty, so this waits for the frame after the one that used the buffer.
        // That caps the CPU at about one frame ahead of the GPU despite FRAMES_IN_FLIGHT. Using
        // currentSubmitIndex - 1 for an empty submit is the exact fence, but it measured worse: 1% lows of
        // 112-118 vs 143-147 fps in the benchmark, with no gain when GPU-bound. Keep it unless that changes.
        long submitIndex = this.currentSubmitIndex;
        return new GpuFence() {
            private boolean completed;

            @Override
            public boolean awaitCompletion(final long timeoutNs) {
                if (!this.completed) {
                    this.completed = MetalCommandEncoder.this.awaitSubmitCompletion(submitIndex, timeoutNs);
                }
                return this.completed;
            }

            @Override
            public void close() {
                this.completed = true;
            }
        };
    }

    @Override
    public void writeTimestamp(final GpuQueryPool pool, final int index) {
        // GPU timestamps are not wired up yet; queries report no value.
    }

    void presentBlit(final long drawable, final GpuTextureView source, final int width, final int height, final double minDuration) {
        this.checkNotInPass("present");
        MetalNative.present(this.frame(), drawable, ((MetalTextureView)source).handle(), width, height, minDuration);
    }
}
