package dev.honeycrisp.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.util.TextureViewAndSampler;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

public class MetalRenderPass implements RenderPassBackend {
    private static final int MAX_UNIFORMS = 15;

    private final MetalDevice device;
    private final MetalCommandEncoder encoder;
    private long enc;
    private final RenderPass.@Nullable RenderArea renderArea;
    private final int width;
    private final int height;
    private final boolean hasDepth;
    private final boolean main;
    /** The first-person "Item in hand" pass: item pipelines use their variant with the metal shine. */
    private final boolean hand;
    /** Set when another pipeline has bound its own resources over the slots MetalShaders.bindFrame fills for terrain. */
    private boolean frameSlotsTaken;
    private int debugGroups;
    private final long[] vertexBuffers = new long[4];
    private final long[] vertexOffsets = new long[4];
    private final MetalBuffer[] vertexBufferObjects = new MetalBuffer[4];
    private long mainColorTexture;
    private long mainDepthTexture;
    private boolean split;
    private int scissorX;
    private int scissorY;
    private int scissorW;
    private int scissorH;
    private boolean scissorSet;

    @Nullable
    private MetalRenderPipeline pipeline;
    @Nullable
    private MetalRenderPipeline appliedPipeline;
    private int primitive;
    private int dirtyUniforms;
    private boolean skyDrawn;
    private final Object[] uniforms = new Object[MAX_UNIFORMS];
    private final MetalBindingCache bindings = new MetalBindingCache(MAX_UNIFORMS);

    @Nullable
    private MetalBuffer indexBuffer;
    private int indexType;
    private int indexSize;

    static final boolean TRACE_ENABLED = Boolean.getBoolean("honeycrisp.trace");

    private void trace(final String what) {
        if (TRACE_ENABLED && this.encoder.tracing()) {
            this.encoder.trace("    " + what + (this.pipeline != null ? " [" + this.pipeline.name() + "]" : ""));
        }
    }

    MetalRenderPass(
        final MetalDevice device, final MetalCommandEncoder encoder, final long enc, final RenderPass.@Nullable RenderArea renderArea,
        final int width, final int height, final boolean hasDepth, final boolean main, final boolean hand
    ) {
        this.main = main;
        this.hand = hand;
        this.device = device;
        this.encoder = encoder;
        this.enc = enc;
        this.renderArea = renderArea;
        this.width = width;
        this.height = height;
        this.hasDepth = hasDepth;
        this.disableScissor();
    }

    boolean isMain() {
        return this.main;
    }

    void setMainTargets(final long color, final long depth) {
        this.mainColorTexture = color;
        this.mainDepthTexture = depth;
    }

    /** Snapshot the opaque scene before the first translucent terrain draw, then restore encoder state. */
    private void splitForReflections() {
        this.split = true;
        this.enc = this.encoder.splitMainPass(this, this.mainColorTexture, this.mainDepthTexture, this.width, this.height);
        this.appliedPipeline = null;
        this.applyPipeline();
        MetalNative.passSetScissor(this.enc, this.scissorX, this.scissorY, this.scissorW, this.scissorH, this.width, this.height);
        for (int slot = 0; slot < this.vertexBuffers.length; slot++) {
            if (this.vertexBuffers[slot] != 0L) {
                MetalNative.passSetVertexBuffer(this.enc, slot, this.vertexBuffers[slot], this.vertexOffsets[slot]);
            }
        }
        this.invalidateBindings();
    }

    void end() {
        if (this.main) {
            this.device.shaders().finishMain(this.enc);
        }
        MetalNative.passEnd(this.enc);
    }

    /** Ends the encoder in the middle of the main pass (it is re-opened on the same targets). */
    void endForSplit() {
        MetalNative.passEnd(this.enc);
    }

    @Override
    public void pushDebugGroup(final Supplier<String> label) {
        this.debugGroups++;
        if (this.device.isDebuggingEnabled()) {
            MetalNative.passPushDebug(this.enc, label.get());
        }
    }

    @Override
    public void popDebugGroup() {
        if (this.debugGroups == 0) {
            throw new IllegalStateException("Can't pop more debug groups than was pushed!");
        }
        this.debugGroups--;
        if (this.device.isDebuggingEnabled()) {
            MetalNative.passPopDebug(this.enc);
        }
    }

    @Override
    public void setPipeline(final BackendRenderPipeline pipeline) {
        if (!(pipeline instanceof MetalRenderPipeline metalPipeline)) {
            throw new IllegalArgumentException("Pipeline must be instance of MetalRenderPipeline");
        }
        this.pipeline = metalPipeline;
        this.primitive = metalPipeline.primitive();
        this.applyPipeline();
        // Preserve the API's requirement to supply uniforms after setting a pipeline. Metal's
        // resource bindings survive pipeline changes; the cache compares resource kinds and
        // shader stages as well as handles before reusing them for the new pipeline.
        java.util.Arrays.fill(this.uniforms, null);
        this.dirtyUniforms = (1 << MAX_UNIFORMS) - 1;
    }

    private void applyPipeline() {
        MetalRenderPipeline p = this.pipeline;
        if (p != null && this.appliedPipeline != p) {
            MetalNative.passSetPipeline(this.enc, p.stateFor(this.hasDepth, this.hand), p.depthState(), p.cull(), p.wireframe(), p.depthBias(), p.depthSlopeScale());
            this.appliedPipeline = p;
        }
    }

    private void invalidateBindings() {
        this.bindings.invalidate();
        this.dirtyUniforms = (1 << MAX_UNIFORMS) - 1;
    }

    @Override
    public void setUniform(final int index, @Nullable final Object value) {
        if (this.uniforms[index] != value) {
            this.uniforms[index] = value;
            this.dirtyUniforms |= 1 << index;
        }
    }

    @Override
    public void pushConstants(final ByteBuffer value) {
        if (this.pipeline != null && this.pipeline.pushConstantStages() != 0) {
            MetalNative.passPushConstants(this.enc, MemorySegment.ofBuffer(value), value.remaining(), this.pipeline.pushConstantStages());
        }
    }

    @Override
    public void enableScissor(final int x, final int y, final int width, final int height) {
        if (this.scissorSet && this.scissorX == x && this.scissorY == y && this.scissorW == width && this.scissorH == height) {
            return;
        }
        this.scissorX = x;
        this.scissorY = y;
        this.scissorW = width;
        this.scissorH = height;
        MetalNative.passSetScissor(this.enc, x, y, width, height, this.width, this.height);
        this.scissorSet = true;
    }

    @Override
    public void disableScissor() {
        if (this.renderArea != null) {
            this.enableScissor(this.renderArea.x(), this.renderArea.y(), this.renderArea.width(), this.renderArea.height());
        } else {
            this.enableScissor(0, 0, this.width, this.height);
        }
    }

    @Override
    public void setVertexBuffer(final int slot, @Nullable final GpuBufferSlice vertexBuffer) {
        if (vertexBuffer != null) {
            long handle = ((MetalBuffer)vertexBuffer.buffer()).handle();
            if (slot < this.vertexBuffers.length && this.vertexBuffers[slot] == handle && this.vertexOffsets[slot] == vertexBuffer.offset()) {
                return;
            }
            MetalNative.passSetVertexBuffer(this.enc, slot, handle, vertexBuffer.offset());
            if (slot < this.vertexBuffers.length) {
                this.vertexBuffers[slot] = handle;
                this.vertexOffsets[slot] = vertexBuffer.offset();
                this.vertexBufferObjects[slot] = (MetalBuffer)vertexBuffer.buffer();
            }
        }
    }

    @Override
    public void setIndexBuffer(final GpuBuffer indexBuffer, final IndexType indexType) {
        this.indexBuffer = (MetalBuffer)indexBuffer;
        this.indexType = indexType == IndexType.INT ? 1 : 0;
        this.indexSize = indexType.bytes;
    }

    private void flushBindings() {
        this.applyPipeline();
        if (this.dirtyUniforms == 0 || this.pipeline == null) {
            return;
        }
        MetalRenderPipeline p = this.pipeline;
        int count = p.uniformCount();
        this.bindings.begin();
        for (int i = 0; i < count; i++) {
            if ((this.dirtyUniforms & (1 << i)) == 0) {
                continue;
            }
            Object value = this.uniforms[i];
            int stages = p.uniformStages(i);
            if (value == null || stages == 0) {
                if (value == null && stages != 0) {
                    throw new IllegalStateException("Missing uniform " + p.uniformName(i) + " (should be " + p.uniformType(i) + ")");
                }
                continue;
            }
            UniformType type = p.uniformType(i);
            if (type == UniformType.UNIFORM_BUFFER) {
                GpuBufferSlice slice = (GpuBufferSlice)value;
                this.bindings.set(i, 1, stages, ((MetalBuffer)slice.buffer()).handle(), 0L, slice.offset());
            } else if (type == UniformType.COMBINED_IMAGE_SAMPLER) {
                TextureViewAndSampler tvs = (TextureViewAndSampler)value;
                this.bindings.set(i, 2, stages, ((MetalTextureView)tvs.view()).handle(), ((MetalSampler)tvs.sampler()).handle(), 0L);
            } else {
                this.bindings.set(i, 3, stages, this.device.texelBufferView((GpuBufferSlice)value, p.uniformFormat(i)), 0L, 0L);
            }
        }
        if (this.bindings.changed()) {
            if (this.main && p.terrainKind() == MetalShaders.KIND_NONE && count > MetalShaders.FIRST_FRAME_SLOT) {
                this.frameSlotsTaken = true;
            }
            MetalNative.passBind(this.enc, count, this.bindings.kinds, this.bindings.stages, this.bindings.objects, this.bindings.samplers, this.bindings.offsets);
        }
        this.dirtyUniforms = 0;
    }

    // ---- draws ----

    @Override
    public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
        this.trace("draw verts=" + vertexCount + " inst=" + instanceCount);
        this.flushBindings();
        if (this.pipeline != null) {
            if (this.skyDrawn && this.pipeline.isSunrise() && MetalShaders.usesAtmosphere()) return;
            int[] sky = this.pipeline.skyUniforms();
            if (sky != null && MetalShaders.usesAtmosphere()) {
                if (this.skyDrawn) return; // skip vanilla's later black underside disc
                if (this.device.shaders().drawSky(this.enc, this.pipeline.depthState(), this.hasDepth, sky, this.uniforms)) {
                    // drawSky writes a different pipeline and buffer bytes directly to this encoder.
                    this.appliedPipeline = null;
                    this.invalidateBindings();
                    this.skyDrawn = true;
                    return;
                }
            }
        }
        if (this.pipeline != null && this.pipeline.isFan()) {
            this.drawFan(null, 0L, vertexCount, instanceCount, firstVertex, firstInstance);
            return;
        }
        MetalNative.passDraw(this.enc, this.primitive, firstVertex, vertexCount, instanceCount, firstInstance);
    }

    @Override
    public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        this.trace("drawIndexed indices=" + indexCount + " inst=" + instanceCount);
        if (this.pipeline != null && this.pipeline.isBlobShadow()) {
            return; // the shadow map already shadows entities
        }
        this.flushBindings();
        MetalBuffer ib = this.requireIndexBuffer();
        if (this.main && this.pipeline != null && this.pipeline.entityUniforms() != null) {
            this.captureEntity(ib, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
        }
        if (this.main && this.pipeline != null && this.pipeline.isLitParticle() && this.frameSlotsTaken) {
            // particle.metal reads the shadow map and frame data from the slots bindFrame fills.
            this.frameSlotsTaken = false;
            this.device.shaders().bindFrame(this.enc);
        }
        if (this.pipeline != null && this.pipeline.cloudUniforms() != null) {
            if (this.main) {
                this.captureClouds(ib, indexCount, firstIndex, vertexOffset);
            }
            // The cloud shader reads where the sun is from the frame slot (MetalRenderPipeline.sunlitClouds).
            this.frameSlotsTaken = true;
            this.device.shaders().bindCloudLight(this.enc);
        }
        if (this.pipeline != null && this.pipeline.isFan()) {
            this.drawFan(ib, (long)firstIndex * this.indexSize, indexCount, instanceCount, vertexOffset, firstInstance);
            return;
        }
        MetalNative.passDrawIndexed(
            this.enc, this.primitive, indexCount, this.indexType, ib.handle(), (long)firstIndex * this.indexSize, instanceCount, vertexOffset, firstInstance
        );
    }

    @Override
    public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
        this.trace("multiDrawIndexed draws=" + drawCount + " inst=" + instanceCount);
        this.flushBindings();
        MetalBuffer ib = this.requireIndexBuffer();
        MetalNative.passMultiDrawIndexed(
            this.enc, this.primitive, MemorySegment.ofBuffer(drawParameters), drawCount, this.indexType, ib.handle(), 0L, instanceCount, firstInstance
        );
    }

    @Override
    public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
        throw new UnsupportedOperationException("Metal backend does not support the multiDrawDirectSeparate device feature");
    }

    @Override
    public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
        this.trace("drawIndexedIndirect draws=" + drawCount);
        this.flushBindings();
        MetalBuffer ib = this.requireIndexBuffer();
        if (this.main && this.pipeline != null && this.pipeline.terrainKind() != MetalShaders.KIND_NONE) {
            if (this.frameSlotsTaken) {
                this.frameSlotsTaken = false;
                this.device.shaders().bindFrame(this.enc);
            }
            if (this.pipeline.terrainKind() == MetalShaders.KIND_TRANSLUCENT && !this.split) {
                this.splitForReflections();
                this.flushBindings();
            }
            this.captureTerrain(ib, commands, drawCount);
            if (this.drawSeenSections(ib, commands, drawCount)) {
                return;
            }
        }
        MetalNative.passDrawIndexedIndirect(
            this.enc, this.primitive, this.indexType, ib.handle(), 0L, ((MetalBuffer)commands.buffer()).handle(), commands.offset(), drawCount, 20
        );
    }

    @Override
    public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
        this.trace("multiDraw draws=" + drawCount);
        this.flushBindings();
        MetalNative.passMultiDraw(this.enc, this.primitive, MemorySegment.ofBuffer(drawParameters), drawCount, instanceCount, firstInstance);
    }

    @Override
    public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
        throw new UnsupportedOperationException("Metal backend does not support the multiDrawDirectSeparate device feature");
    }

    @Override
    public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
        this.trace("drawIndirect draws=" + drawCount);
        this.flushBindings();
        MetalNative.passDrawIndirect(this.enc, this.primitive, ((MetalBuffer)commands.buffer()).handle(), commands.offset(), drawCount, 16);
    }

    @Override
    public void writeTimestamp(final GpuQueryPool pool, final int index) {
    }

    /** The main pass draws only the sections the camera sees (-Dhoneycrisp.mainCull=false: also those that only cast shadows). */
    private static final boolean MAIN_CULL = !"false".equals(System.getProperty("honeycrisp.mainCull"));
    // Where captureTerrain found the last terrain draw's arguments and section positions on the CPU (0: not readable).
    private long terrainCommands;
    private long terrainSections;
    private int terrainSectionCount;

    /**
     * Draws a terrain multidraw of the main pass without the sections that are only in it to cast shadows
     * (SectionOcclusionGraphMixin): the camera cannot see them, so there they would be vertex work for nothing. The
     * shadow passes still get the whole draw (captureTerrain). False when nothing was drawn and the caller must.
     */
    private boolean drawSeenSections(final MetalBuffer ib, final GpuBufferSlice commands, final int drawCount) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet casterOnly = MetalShaders.casterOnlySections();
        if (!MAIN_CULL || casterOnly == null || casterOnly.isEmpty() || this.terrainCommands == 0L) {
            return false;
        }
        MetalRenderPipeline p = this.pipeline;
        long handle = ((MetalBuffer)commands.buffer()).handle();
        int runStart = 0;
        int drawn = 0;
        for (int d = 0; d <= drawCount; d++) {
            boolean keep = false;
            if (d < drawCount) {
                int instance = MemoryUtil.memGetInt(this.terrainCommands + d * 20L + 16L);
                keep = true;
                if (instance >= 0 && instance < this.terrainSectionCount) {
                    long pos = this.terrainSections + (long)instance * p.sectionStride() + p.sectionPosOffset();
                    keep = !casterOnly.contains(MetalShaders.sectionKey(MemoryUtil.memGetInt(pos), MemoryUtil.memGetInt(pos + 4L), MemoryUtil.memGetInt(pos + 8L)));
                }
            }
            if (!keep) {
                // Consecutive kept draws go out as one call.
                if (d > runStart) {
                    MetalNative.passDrawIndexedIndirect(
                        this.enc, this.primitive, this.indexType, ib.handle(), 0L, handle, commands.offset() + runStart * 20L, d - runStart, 20
                    );
                    drawn += d - runStart;
                }
                runStart = d + 1;
            }
        }
        if (HitchTrace.ENABLED) {
            HitchTrace.mainSections(drawCount, drawn);
        }
        return true;
    }

    private void captureTerrain(final MetalBuffer ib, final GpuBufferSlice commands, final int drawCount) {
        this.terrainCommands = 0L;
        MetalRenderPipeline p = this.pipeline;
        int[] idx = p.terrainUniforms();
        int[] shadowDesc = p.shadowDescriptor();
        if (idx == null || shadowDesc == null) {
            return;
        }
        GpuBufferSlice globals = (GpuBufferSlice)this.uniforms[idx[0]];
        GpuBufferSlice projection = (GpuBufferSlice)this.uniforms[idx[1]];
        GpuBufferSlice terrain = (GpuBufferSlice)this.uniforms[idx[2]];
        GpuBufferSlice fog = (GpuBufferSlice)this.uniforms[idx[3]];
        TextureViewAndSampler atlas = (TextureViewAndSampler)this.uniforms[idx[5]];
        // The shadow passes leave out sections outside their map; for that they read the draw arguments and section
        // positions on the CPU, from wherever this frame's copy of them is (MetalBuffer.readable).
        MetalBuffer commandBuffer = (MetalBuffer)commands.buffer();
        MetalBuffer sections = this.vertexBufferObjects[1];
        long commandsAddress = 0L;
        long sectionsAddress = 0L;
        int sectionCount = 0;
        if (p.sectionStride() > 0 && sections != null && sections.handle() == this.vertexBuffers[1]) {
            long submit = this.encoder.currentSubmitIndex();
            long completed = this.encoder.completedSubmitIndex();
            commandsAddress = commandBuffer.readable(submit, completed, commands.offset());
            if (commandsAddress != 0L && commandBuffer.readableLength() < drawCount * 20L) {
                commandsAddress = 0L;
            }
            sectionsAddress = sections.readable(submit, completed, this.vertexOffsets[1]);
            sectionCount = (int)Math.min(sections.readableLength() / p.sectionStride(), Integer.MAX_VALUE);
            if (sectionsAddress == 0L) {
                commandsAddress = 0L;
            }
        }
        this.terrainCommands = commandsAddress;
        this.terrainSections = sectionsAddress;
        this.terrainSectionCount = sectionCount;
        this.device.shaders().capture(new MetalShaders.TerrainDraw(
            p.terrainKind(), shadowDesc, this.vertexBuffers[0], this.vertexOffsets[0], this.vertexBuffers[1], this.vertexOffsets[1], ib.handle(),
            this.indexType, ((MetalBuffer)commands.buffer()).handle(), commands.offset(), drawCount,
            ((MetalBuffer)globals.buffer()).handle(), globals.offset(),
            ((MetalBuffer)projection.buffer()).latestContents() + projection.offset(), ((MetalBuffer)terrain.buffer()).latestContents() + terrain.offset(),
            ((MetalBuffer)fog.buffer()).latestContents() + fog.offset(),
            ((MetalTextureView)atlas.view()).handle(), ((MetalSampler)atlas.sampler()).handle(),
            commandsAddress, sectionsAddress, sectionCount, p.sectionStride(), p.sectionPosOffset()
        ));
    }

    private void captureEntity(final MetalBuffer ib, final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
        MetalRenderPipeline p = this.pipeline;
        int[] idx = p.entityUniforms();
        int[] shadowDesc = p.shadowDescriptor();
        if (idx == null || shadowDesc == null) {
            return;
        }
        GpuBufferSlice transforms = (GpuBufferSlice)this.uniforms[idx[0]];
        TextureViewAndSampler atlas = (TextureViewAndSampler)this.uniforms[idx[1]];
        if (transforms == null || atlas == null) {
            return;
        }
        this.device.shaders().capture(new MetalShaders.EntityDraw(
            shadowDesc, this.vertexBuffers[0], this.vertexOffsets[0], ib.handle(), this.indexType, indexCount, firstIndex, vertexOffset,
            instanceCount, firstInstance, ((MetalBuffer)transforms.buffer()).handle(), transforms.offset(),
            ((MetalTextureView)atlas.view()).handle(), ((MetalSampler)atlas.sampler()).handle(), p.isLitParticle(),
            p.isLitParticle() && this.device.shaders().isParticleAtlas(atlas.view().texture()), p.isEmissiveLayer()
        ));
    }

    private void captureClouds(final MetalBuffer ib, final int indexCount, final int firstIndex, final int vertexOffset) {
        int[] idx = this.pipeline.cloudUniforms();
        GpuBufferSlice transforms = (GpuBufferSlice)this.uniforms[idx[0]];
        GpuBufferSlice info = (GpuBufferSlice)this.uniforms[idx[1]];
        long faces = this.bindings.objects[idx[2]]; // texel buffer view made by flushBindings, alive until this submit completes
        if (transforms == null || info == null || faces == 0L) {
            return;
        }
        this.device.shaders().capture(new MetalShaders.CloudDraw(
            ib.handle(), this.indexType, indexCount, firstIndex, vertexOffset, ((MetalBuffer)transforms.buffer()).handle(), transforms.offset(),
            ((MetalBuffer)info.buffer()).handle(), info.offset(), faces
        ));
    }

    private MetalBuffer requireIndexBuffer() {
        if (this.indexBuffer == null) {
            throw new IllegalStateException("Indexed draw without an index buffer");
        }
        return this.indexBuffer;
    }

    /**
     * Metal has no triangle fans, so expand one into a triangle list. Index data lives in shared
     * memory, which lets us read a fan's indices on the CPU (fans are only used for a few sky draws).
     */
    private void drawFan(@Nullable final MetalBuffer source, final long sourceOffset, final int count, final int instanceCount, final int baseVertex, final int firstInstance) {
        int triangles = count - 2;
        if (triangles <= 0) {
            return;
        }
        GpuBufferSlice.MappedView view = this.encoder.transientMemory().allocateGpuMapped((long)triangles * 3 * 4, 4L, GpuBuffer.USAGE_INDEX);
        IntBuffer out = view.data().asIntBuffer();
        long src = source != null ? source.contents() + sourceOffset : 0L;
        for (int t = 0; t < triangles; t++) {
            out.put(this.fanIndex(source, src, 0));
            out.put(this.fanIndex(source, src, t + 1));
            out.put(this.fanIndex(source, src, t + 2));
        }
        GpuBufferSlice slice = view.slice();
        MetalNative.passDrawIndexed(
            this.enc, MetalConst.PRIM_TRIANGLES, triangles * 3, 1, ((MetalBuffer)slice.buffer()).handle(), slice.offset(), instanceCount, baseVertex, firstInstance
        );
    }

    private int fanIndex(@Nullable final MetalBuffer source, final long src, final int i) {
        if (source == null) {
            return i;
        }
        return this.indexSize == 4 ? MemoryUtil.memGetInt(src + 4L * i) : Short.toUnsignedInt(MemoryUtil.memGetShort(src + 2L * i));
    }
}
