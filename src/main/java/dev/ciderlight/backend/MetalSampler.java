package dev.ciderlight.backend;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;

public class MetalSampler implements GpuSampler, Destroyable {
    private final MetalDevice device;
    private final long handle;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private boolean closed;

    public MetalSampler(
        final MetalDevice device, final AddressMode addressModeU, final AddressMode addressModeV, final FilterMode minFilter,
        final FilterMode magFilter, final int maxAnisotropy, final OptionalDouble maxLod
    ) {
        this.device = device;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
        // Same mip selection rule as the Vulkan backend: linear mip filtering unless maxLod pins level 0.
        double lod = maxLod.orElse(1000.0);
        int mipFilter = lod > 0.25 ? 2 : 1;
        this.handle = MetalNative.samplerCreate(
            device.context(), addressModeU == AddressMode.REPEAT ? 0 : 1, addressModeV == AddressMode.REPEAT ? 0 : 1,
            minFilter == FilterMode.NEAREST ? 0 : 1, magFilter == FilterMode.NEAREST ? 0 : 1, mipFilter,
            (float)Math.max(0.25, lod), maxAnisotropy
        );
    }

    public long handle() {
        return this.handle;
    }

    @Override
    public void destroy() {
        MetalNative.release(this.handle);
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.device.encoder().queueForDestroy(this);
        }
    }

    @Override
    public AddressMode getAddressModeU() {
        return this.addressModeU;
    }

    @Override
    public AddressMode getAddressModeV() {
        return this.addressModeV;
    }

    @Override
    public FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.maxAnisotropy;
    }

    @Override
    public OptionalDouble getMaxLod() {
        return this.maxLod;
    }
}
