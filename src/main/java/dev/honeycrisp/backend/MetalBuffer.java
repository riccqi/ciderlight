package dev.honeycrisp.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** A Metal buffer in shared storage: on Apple Silicon this is the same memory the GPU reads. */
public class MetalBuffer extends BaseGpuBuffer implements Destroyable {
    protected final MetalDevice device;
    private final long handle;
    private final long contents;
    private boolean closed;
    private int mappings;
    /** Copy of the buffer holding the bytes most recently written through the command encoder; 0 until the first such write. */
    private long written;
    // Encoder copies into this buffer: the submit of the latest one, and those of that submit as (offset, length, CPU
    // address of the bytes copied; 0 when unknown) triples. Until the GPU has run them, contents() lags behind.
    private long gpuWriteSubmit;
    private long[] gpuWrites = new long[0];
    private int gpuWriteCount;
    private long readableLength;

    public MetalBuffer(final MetalDevice device, final long handle, @GpuBuffer.Usage final int usage, final long size) {
        this(device, handle, MetalNative.bufferContents(handle), usage, size);
    }

    protected MetalBuffer(final MetalDevice device, final long handle, final long contents, @GpuBuffer.Usage final int usage, final long size) {
        super(usage, size);
        this.device = device;
        this.handle = handle;
        this.contents = contents;
    }

    public long handle() {
        return this.handle;
    }

    /** CPU address of the buffer start (valid for shared-storage buffers). */
    public long contents() {
        return this.contents;
    }

    /**
     * CPU address of the bytes most recently written to this buffer. Encoder writes are blits, which only reach
     * {@link #contents()} when the GPU executes them, so until then contents() still holds an earlier frame's data.
     */
    public long latestContents() {
        return this.written != 0L ? this.written : this.contents;
    }

    /** Notes an encoder copy of `length` bytes into this buffer at `offset`, from CPU-visible memory at `source` (0: unknown). */
    void recordGpuWrite(final long submit, final long offset, final long length, final long source) {
        if (submit != this.gpuWriteSubmit) {
            this.gpuWriteSubmit = submit;
            this.gpuWriteCount = 0;
        }
        if (this.gpuWriteCount * 3 == this.gpuWrites.length) {
            this.gpuWrites = java.util.Arrays.copyOf(this.gpuWrites, Math.max(12, this.gpuWrites.length * 2));
        }
        int i = this.gpuWriteCount++ * 3;
        this.gpuWrites[i] = offset;
        this.gpuWrites[i + 1] = length;
        this.gpuWrites[i + 2] = source;
    }

    /**
     * CPU address of the bytes a draw recorded now will find at `offset`, or 0 when they cannot be read on the CPU:
     * contents() once every encoder copy into the buffer has run on the GPU, and the source of the copy that covers
     * the offset while one from the current submit is pending. {@link #readableLength()} then says how many bytes.
     */
    long readable(final long submit, final long completedSubmit, final long offset) {
        if (this.gpuWriteSubmit == 0L || this.gpuWriteSubmit <= completedSubmit) {
            this.readableLength = this.size() - offset;
            return this.contents + offset;
        }
        if (this.gpuWriteSubmit != submit) {
            return 0L;
        }
        for (int i = (this.gpuWriteCount - 1) * 3; i >= 0; i -= 3) {
            long start = this.gpuWrites[i];
            long length = this.gpuWrites[i + 1];
            if (offset >= start && offset < start + length) {
                this.readableLength = start + length - offset;
                return this.gpuWrites[i + 2] != 0L ? this.gpuWrites[i + 2] + (offset - start) : 0L;
            }
        }
        return 0L;
    }

    /** Bytes readable at the address the last successful {@link #readable} call returned. */
    long readableLength() {
        return this.readableLength;
    }

    /** Mirrors an encoder write to a small (uniform block sized) buffer for {@link #latestContents()}. */
    void recordWrite(final long offset, final ByteBuffer data) {
        if (this.size() > 65536L) {
            return;
        }
        if (this.written == 0L) {
            this.written = MemoryUtil.nmemAlloc(this.size());
            MemoryUtil.memCopy(this.contents, this.written, this.size());
        }
        MemoryUtil.memByteBuffer(this.written + offset, data.remaining()).put(data.duplicate());
    }

    @Override
    public void destroy() {
        MetalNative.release(this.handle);
        if (this.written != 0L) {
            MemoryUtil.nmemFree(this.written);
            this.written = 0L;
        }
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            if (this.mappings != 0) {
                throw new IllegalStateException("Attempt to close a mapped buffer");
            }
            this.device.encoder().queueForDestroy(this);
        }
    }

    @Override
    public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
        if (this.isClosed()) {
            throw new IllegalStateException("Buffer already closed");
        } else if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        } else if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        } else if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        } else if (offset < 0L || length < 0L || offset + length > this.size()) {
            throw new IllegalArgumentException("Cannot map " + length + " bytes at offset " + offset + " from " + this.size() + " size buffer");
        } else if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Mapping buffer slice larger than 2GB is not supported");
        }
        this.mappings++;
        return new GpuBufferSlice.MappedView(this.slice(offset, length), MemoryUtil.memByteBuffer(this.contents + offset, (int)length), new Runnable() {
            private boolean done;

            @Override
            public void run() {
                if (!this.done) {
                    this.done = true;
                    MetalBuffer.this.mappings--;
                    if (write && MetalBuffer.this.written != 0L) {
                        MemoryUtil.memCopy(MetalBuffer.this.contents + offset, MetalBuffer.this.written + offset, length);
                    }
                }
            }
        });
    }
}
