package dev.honeycrisp.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

public class MetalDevice implements GpuDeviceBackend {
    private final long context;
    private final DeviceInfo deviceInfo;
    private final MetalCommandEncoder encoder;
    private final MetalShaders shaders;
    private final boolean debugging;
    private final Int2LongOpenHashMap depthStates = new Int2LongOpenHashMap();
    private final Map<TexelViewKey, Long> texelViews = new HashMap<>();
    private long texelViewsSubmit = -1L;

    private record TexelViewKey(long buffer, long offset, long length, int format) {
    }

    /** The device the game renders with, for RenderScale; null when the game is not on Honeycrisp's backend. */
    static volatile @Nullable MetalDevice current;

    MetalDevice(final long context, final boolean debugging) {
        this.context = context;
        this.debugging = debugging;
        String name = MetalNative.contextName(context);
        long[] limits = MetalNative.contextLimits(context);
        this.deviceInfo = new DeviceInfo(
            name,
            "Apple",
            "Metal (Honeycrisp)",
            true,
            "Metal",
            1.0F,
            new DeviceLimits(16, 256, 16384, limits[0], Integer.MAX_VALUE, 8, Integer.MAX_VALUE),
            new DeviceFeatures(true, true, true, false, true, true, true, true),
            Set.of("MTLGPUFamilyApple" + (limits[3] != 0 ? "7" : "?"), limits[2] != 0 ? "UnifiedMemory" : "DiscreteMemory"),
            // Apple GPUs need the explicit-depth invariance path, same as the Vulkan backend on Apple Silicon.
            new HintsAndWorkarounds(false, false, true, false),
            limits[2] != 0 ? DeviceType.INTEGRATED : DeviceType.DISCRETE
        );
        this.encoder = new MetalCommandEncoder(this);
        this.shaders = new MetalShaders(this, Quality.of(name));
        current = this;
    }

    MetalShaders shaders() {
        return this.shaders;
    }

    long context() {
        return this.context;
    }

    MetalCommandEncoder encoder() {
        return this.encoder;
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return this.deviceInfo;
    }

    @Override
    public void close() {
        if (current == this) {
            current = null;
        }
        this.encoder.destroy();
        this.shaders.destroy();
        this.depthStates.values().forEach(MetalNative::release);
        MetalNative.contextDestroy(this.context);
    }

    @Override
    public GpuSurfaceBackend createSurface(final long windowHandle, final BooleanSupplier isIconified) {
        return new MetalSurface(this, windowHandle);
    }

    @Override
    public MetalCommandEncoder createCommandEncoder() {
        return this.encoder;
    }

    @Override
    public GpuSampler createSampler(
        final AddressMode addressModeU, final AddressMode addressModeV, final FilterMode minFilter, final FilterMode magFilter,
        final int maxAnisotropy, final OptionalDouble maxLod
    ) {
        return new MetalSampler(this, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
    }

    @Override
    public GpuTexture createTexture(
        @Nullable final String label, @GpuTexture.Usage final int usage, final GpuFormat format, final int width, final int height,
        final int depthOrLayers, final int mipLevels
    ) {
        return new MetalTexture(this, usage, label != null ? label : "", format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public GpuTextureView createTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
        return new MetalTextureView(this, (MetalTexture)texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final long size) {
        long handle = MetalNative.bufferCreate(this.context, size, false);
        if (handle == 0L) {
            throw new IllegalStateException("Failed to allocate " + size + " byte buffer");
        }
        if (label != null && this.debugging) {
            MetalNative.setLabel(handle, label.get());
        }
        return new MetalBuffer(this, handle, usage, size);
    }

    @Override
    public GpuBuffer createBuffer(@Nullable final Supplier<String> label, @GpuBuffer.Usage final int usage, final ByteBuffer data) {
        MetalBuffer buffer = (MetalBuffer)this.createBuffer(label, usage, (long)data.remaining());
        // A new buffer can't be referenced by any recorded GPU work yet, so fill it directly.
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), buffer.contents(), data.remaining());
        return buffer;
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return this.debugging;
    }

    @Override
    public BackendRenderPipeline.Pending compilePipeline(final BackendRenderPipeline.CreateInfo createInfo) {
        MetalRenderPipeline pipeline = MetalRenderPipeline.compile(this, createInfo);
        return () -> pipeline;
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(final int size) {
        return new MetalQueryPool(size);
    }

    @Override
    public long getTimestampCalibrationOffset() {
        return 0L;
    }

    synchronized long depthState(final int compare, final boolean write) {
        int key = compare << 1 | (write ? 1 : 0);
        long state = this.depthStates.get(key);
        if (state == 0L) {
            state = MetalNative.depthStateCreate(this.context, compare, write);
            this.depthStates.put(key, state);
        }
        return state;
    }

    /** A texture-buffer view of a slice, cached for the current submit. */
    long texelBufferView(final GpuBufferSlice slice, @Nullable final GpuFormat format) {
        if (format == null) {
            throw new IllegalStateException("Texel buffer uniform without a format");
        }
        long submit = this.encoder.currentSubmitIndex();
        if (submit != this.texelViewsSubmit) {
            this.texelViews.clear();
            this.texelViewsSubmit = submit;
        }
        MetalBuffer buffer = (MetalBuffer)slice.buffer();
        TexelViewKey key = new TexelViewKey(buffer.handle(), slice.offset(), slice.length(), MetalConst.format(format));
        Long cached = this.texelViews.get(key);
        if (cached != null) {
            return cached;
        }
        long handle = buffer.handle();
        long offset = slice.offset();
        long alignment = MetalNative.textureBufferAlignment(this.context, key.format());
        if (offset % alignment != 0L) {
            // Metal needs aligned texture-buffer offsets; repack into an aligned transient allocation.
            GpuBufferSlice.MappedView copy = this.encoder.transientMemory().allocateGpuMapped(slice.length(), alignment, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER);
            MemoryUtil.memCopy(buffer.contents() + offset, MemoryUtil.memAddress(copy.data()), slice.length());
            handle = ((MetalBuffer)copy.slice().buffer()).handle();
            offset = copy.slice().offset();
        }
        long view = MetalNative.textureBufferView(handle, offset, slice.length(), key.format());
        if (view == 0L) {
            throw new IllegalStateException("Failed to create texel buffer view (" + format + ", " + slice.length() + " bytes)");
        }
        this.encoder.queueForDestroy(() -> MetalNative.release(view));
        this.texelViews.put(key, view);
        return view;
    }
}
