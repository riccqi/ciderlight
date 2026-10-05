package dev.honeycrisp.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

public class MetalTexture extends BaseGpuTexture implements Destroyable {
    private final MetalDevice device;
    private final long handle;
    private boolean closed;
    private int views;

    public MetalTexture(
        final MetalDevice device, @GpuTexture.Usage final int usage, final String label, final GpuFormat format,
        final int width, final int height, final int depthOrLayers, final int mipLevels
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        if (!MetalConst.isTextureFormatSupported(format)) {
            throw new IllegalArgumentException("Metal has no texture format equivalent to " + format);
        }
        this.handle = MetalNative.textureCreate(device.context(), MetalConst.format(format), width, height, depthOrLayers, mipLevels, usage);
        if (this.handle == 0L) {
            throw new IllegalStateException("Failed to create " + width + "x" + height + " " + format + " texture '" + label + "'");
        }
        if (!label.isEmpty()) {
            MetalNative.setLabel(this.handle, label);
        }
        this.views = 1;
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
            this.removeView();
        }
    }

    void addView() {
        this.views++;
    }

    void removeView() {
        if (--this.views < 0) {
            throw new IllegalStateException("Too many views removed from texture");
        }
        if (this.closed && this.views == 0) {
            this.device.encoder().queueForDestroy(this);
        }
    }
}
