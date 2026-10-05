package dev.ciderlight.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.backend.util.TransientBlockAllocator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.util.Mth;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-submit bump allocation. Apple Silicon has unified memory, so staging, GPU and GPU-mapped
 * allocations all come from the same shared-storage block pool; blocks are recycled once the
 * submit that used them has finished on the GPU.
 */
public class MetalTransientMemory implements TransientMemory {
    private static final long BLOCK_SIZE = 4L << 20;
    private static final long MAX_ALIGNMENT = 4096L;
    /** Unused blocks kept for reuse rather than freed (64 MB). */
    private static final int MAX_SPARE_BLOCKS = 16;

    private final MetalDevice device;
    private final MetalCommandEncoder encoder;
    private final java.util.ArrayDeque<Block> spareBlocks = new java.util.ArrayDeque<>();
    private final TransientBlockAllocator<TransientBlockAllocator.Allocator.CpuBlock> cpuAllocator =
        new TransientBlockAllocator<>(BLOCK_SIZE, 16L, TransientBlockAllocator.Allocator.CpuBlock.memalloc());
    private final TransientBlockAllocator<Block> gpuAllocator;
    private long submitIndex;

    record Block(long handle, long contents, long size) implements TransientBlockAllocator.Allocator.Block {
        @Override
        public boolean suboptimal() {
            return false;
        }
    }

    MetalTransientMemory(final MetalDevice device, final MetalCommandEncoder encoder) {
        this.device = device;
        this.encoder = encoder;
        this.gpuAllocator = new TransientBlockAllocator<>(BLOCK_SIZE, MAX_ALIGNMENT, TransientBlockAllocator.Allocator.create(size -> {
            // Minecraft's allocator frees the blocks a submit left unused and allocates new ones when the next needs
            // more, which happens most frames. Freeing a Metal buffer that has been drawn from does not give its memory
            // back while anything still holds it, and something does: at 30 fps that churn kept 120 MB a second resident
            // until the system paged GPU memory out. Blocks of the usual size are kept and reused instead.
            if (size == BLOCK_SIZE && !this.spareBlocks.isEmpty()) {
                return this.spareBlocks.pop();
            }
            long handle = MetalNative.bufferCreate(device.context(), size, false);
            if (handle == 0L) {
                throw new IllegalStateException("Failed to allocate " + size + " byte transient buffer");
            }
            return new Block(handle, MetalNative.bufferContents(handle), size);
        }, block -> {
            if (block.size() == BLOCK_SIZE && this.spareBlocks.size() < MAX_SPARE_BLOCKS) {
                this.spareBlocks.push(block);
            } else {
                MetalNative.release(block.handle());
            }
        }));
    }

    void endSubmit() {
        this.cpuAllocator.rotate().run();
        this.encoder.queueForDestroy(this.gpuAllocator.rotate()::run);
        this.submitIndex++;
    }

    void destroy() {
        this.cpuAllocator.close();
        this.gpuAllocator.close();
        while (!this.spareBlocks.isEmpty()) {
            MetalNative.release(this.spareBlocks.pop().handle());
        }
    }

    @Override
    public ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<TransientBlockAllocator.Allocator.CpuBlock> alloc =
            this.cpuAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        return MemoryUtil.memByteBuffer(alloc.block().address() + alloc.offset(), (int)alloc.size());
    }

    private GpuBufferSlice.MappedView allocateShared(
        final long size, final long alignment, @GpuBuffer.Usage final int usage, final long minimumAllocation, final long elementSize
    ) {
        TransientBlockAllocator.Allocation<Block> alloc = this.gpuAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        Block block = alloc.block();
        TransientBuffer buffer = new TransientBuffer(block, usage, this.submitIndex);
        ByteBuffer data = MemoryUtil.memByteBuffer(block.contents() + alloc.offset(), (int)alloc.size());
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(buffer, alloc.offset(), alloc.size()), data, () -> {});
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(
        final long size, final long alignment, @GpuBuffer.Usage final int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.allocateShared(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice allocateGpu(
        final long size, final long alignment, @GpuBuffer.Usage final int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.allocateShared(size, alignment, usage, minimumAllocation, elementSize).slice();
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(
        final long size, final long alignment, @GpuBuffer.Usage final int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.allocateShared(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadStaging(
        final List<ByteBuffer> data, final long alignment, @GpuBuffer.Usage final int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public GpuBufferSlice uploadGpu(
        final List<ByteBuffer> data, final long alignment, @GpuBuffer.Usage final int usage, final long minimumAllocation, final long elementSize
    ) {
        return this.upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    private GpuBufferSlice upload(
        final List<ByteBuffer> data, final long alignment, @GpuBuffer.Usage final int usage, final long minimumAllocation, final long elementSize
    ) {
        long totalSize = 0L;
        for (ByteBuffer buffer : data) {
            totalSize = Mth.roundToward(totalSize + buffer.remaining(), alignment);
        }
        GpuBufferSlice.MappedView mapped = this.allocateShared(totalSize, alignment, usage, minimumAllocation, elementSize);
        long dst = MemoryUtil.memAddress(mapped.data());
        long offset = 0L;
        for (ByteBuffer buffer : data) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), dst + offset, Math.min(mapped.slice().length() - offset, (long)buffer.remaining()));
            offset = Mth.roundToward(offset + buffer.remaining(), alignment);
            if (offset >= mapped.slice().length()) {
                break;
            }
        }
        return mapped.slice();
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(final List<ByteBuffer> data, final long alignment, @GpuBuffer.Usage final int usage) {
        return this.multiUpload(data, alignment, usage);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(final List<ByteBuffer> data, final long alignment, @GpuBuffer.Usage final int usage) {
        return this.multiUpload(data, alignment, usage);
    }

    /** Packs uploads largest-first so small ones fill the gaps left in the current block. */
    private List<GpuBufferSlice> multiUpload(final List<ByteBuffer> data, final long alignment, @GpuBuffer.Usage final int usage) {
        ReferenceArrayList<GpuBufferSlice> uploaded = new ReferenceArrayList<>();
        uploaded.size(data.size());
        IntArrayList pending = IntArrayList.toList(IntStream.range(0, data.size()));
        pending.sort(IntComparator.comparing(index -> data.get(index).remaining()));
        while (!pending.isEmpty()) {
            int chosen = -1;
            for (int i = pending.size() - 1; i >= 0; i--) {
                if (this.gpuAllocator.canAllocateInCurrentBlock(data.get(pending.getInt(i)).remaining(), alignment)) {
                    chosen = pending.removeInt(i);
                    break;
                }
            }
            if (chosen == -1) {
                chosen = pending.popInt();
            }
            ByteBuffer source = data.get(chosen);
            GpuBufferSlice.MappedView view = this.allocateShared(source.remaining(), alignment, usage, source.remaining(), 1L);
            MemoryUtil.memCopy(source, view.data());
            uploaded.set(chosen, view.slice());
        }
        return uploaded;
    }

    /** A slice of a pooled block, valid only for the submit it was allocated in. */
    private final class TransientBuffer extends MetalBuffer {
        private final long bufferSubmitIndex;
        private boolean closed;

        TransientBuffer(final Block block, @GpuBuffer.Usage final int usage, final long submitIndex) {
            super(MetalTransientMemory.this.device, block.handle(), block.contents(), usage, block.size());
            this.bufferSubmitIndex = submitIndex;
        }

        @Override
        public void destroy() {
        }

        @Override
        public boolean isClosed() {
            if (!this.closed) {
                this.closed = this.bufferSubmitIndex < MetalTransientMemory.this.submitIndex;
            }
            return this.closed;
        }

        @Override
        public void close() {
            this.closed = true;
        }

        @Override
        public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
            throw new IllegalStateException("Cannot map transient buffer");
        }

        @Override
        public GpuBufferSlice slice(final long offset, final long length) {
            throw new IllegalStateException("Cannot slice transient buffer");
        }

        @Override
        public GpuBufferSlice slice() {
            throw new IllegalStateException("Cannot slice transient buffer");
        }
    }
}
