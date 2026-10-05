package dev.honeycrisp.backend;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLMetal;

/** Presents through the CAMetalLayer that SDL attaches to the game window. */
public class MetalSurface implements GpuSurfaceBackend {
    private static final Set<GpuSurface.PresentMode> PRESENT_MODES = EnumSet.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.MAILBOX, GpuSurface.PresentMode.IMMEDIATE);

    /** How long a vsync frame waits for a drawable before it is rendered without being shown. */
    private static final int VSYNC_TIMEOUT_MS = 50;

    private final MetalDevice device;
    private final long view;
    private final long layer;
    private int width;
    private int height;
    private long drawable;
    private boolean vsync = true;
    private final FramePacer pacer = new FramePacer();

    MetalSurface(final MetalDevice device, final long windowHandle) {
        this.device = device;
        this.view = SDLMetal.SDL_Metal_CreateView(windowHandle);
        if (this.view == 0L) {
            throw new IllegalStateException("Failed to create Metal view: " + SDLError.SDL_GetError());
        }
        this.layer = SDLMetal.SDL_Metal_GetLayer(this.view);
        MetalNative.layerSetup(device.context(), this.layer);
    }

    @Override
    public void configure(final GpuSurface.Configuration config) throws SurfaceException {
        this.width = config.width();
        this.height = config.height();
        this.vsync = config.presentMode() == GpuSurface.PresentMode.FIFO || config.presentMode() == GpuSurface.PresentMode.FIFO_RELAXED;
        MetalNative.layerConfigure(this.layer, this.width, this.height, this.vsync);
        double[] timing = new double[3];
        MetalNative.layerDisplayTiming(timing);
        this.pacer.reset(timing[0], timing[1], timing[2]);
        if (HitchTrace.ENABLED) {
            HitchTrace.event(String.format(java.util.Locale.ROOT, "display refresh %.2fms%s", timing[0] * 1e3, timing[2] > 0.0
                ? String.format(java.util.Locale.ROOT, " to %.2fms in steps of %.2fms", timing[1] * 1e3, timing[2] * 1e3) : " (fixed rate)"));
        }
        if (HitchTrace.ENABLED) {
            HitchTrace.event("surface configured " + this.width + "x" + this.height + " " + config.presentMode());
        }
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        if (this.vsync) {
            // Normally this waits for the next refresh; if the compositor keeps every drawable for longer, this frame
            // is not presented rather than the game freezing until it lets go (see mc_layer_next_drawable).
            this.drawable = MetalNative.layerNextDrawable(this.layer, VSYNC_TIMEOUT_MS);
        } else {
            // Uncapped: if every drawable is still queued for display, render this frame without presenting it.
            this.drawable = MetalNative.layerTryNextDrawable(this.layer);
        }
        if (HitchTrace.ENABLED) {
            HitchTrace.acquired(this.drawable != 0L);
        }
    }

    @Override
    public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
        if (this.drawable == 0L) {
            return;
        }
        int w = Math.min(this.width, textureView.getWidth(0));
        int h = Math.min(this.height, textureView.getHeight(0));
        ((MetalCommandEncoder)commandEncoder).presentBlit(this.drawable, textureView, w, h, this.vsync ? this.pacer.minDuration() : 0.0);
    }

    @Override
    public void present() {
        this.releaseDrawable();
        if (this.vsync) {
            this.pacer.frameDone();
            // Wait for the display here, between frames, instead of in acquireNextTexture: by then the next frame has
            // already read the mouse and keyboard, so every frame spent waiting there was a frame of input lag.
            MetalNative.layerPrefetchDrawable(this.layer, VSYNC_TIMEOUT_MS);
            this.pacer.frameStart();
        }
        if (HitchTrace.ENABLED) {
            HitchTrace.endFrame();
        }
    }

    private void releaseDrawable() {
        // Presentation was scheduled on the frame's command buffer during blitFromTexture.
        if (this.drawable != 0L) {
            MetalNative.release(this.drawable);
            this.drawable = 0L;
        }
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return PRESENT_MODES;
    }

    @Override
    public void close() {
        this.releaseDrawable();
        SDLMetal.SDL_Metal_DestroyView(this.view);
    }
}
