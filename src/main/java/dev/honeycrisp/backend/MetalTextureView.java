package dev.honeycrisp.backend;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

public class MetalTextureView extends BaseGpuTextureView implements Destroyable {
    private final MetalDevice device;
    private final long handle;
    private boolean closed;

    public MetalTextureView(final MetalDevice device, final MetalTexture texture, final int baseMipLevel, final int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        this.device = device;
        this.handle = MetalNative.textureView(texture.handle(), baseMipLevel, mipLevels);
        if (this.handle == 0L) {
            throw new IllegalStateException("Failed to create texture view of " + texture.getLabel());
        }
        texture.addView();
    }

    /** View handle, restricted to this view's mip range (for sampling). */
    public long handle() {
        return this.handle;
    }

    @Override
    public MetalTexture texture() {
        return (MetalTexture)super.texture();
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
            this.texture().removeView();
        }
    }
}
