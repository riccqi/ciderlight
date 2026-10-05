package dev.ciderlight.backend;

import com.mojang.renderpearl.api.textures.GpuTexture;

/**
 * The world drawn at a fraction of the window's resolution (Quality.renderScale) and stretched over it before the HUD
 * is drawn, which stays sharp. GameRendererMixin swaps the smaller target in for the world and calls upscale after.
 */
public final class RenderScale {
    private RenderScale() {
    }

    /** The fraction in each direction, or 1 when the game is not running on Ciderlight's backend. */
    public static float scale() {
        MetalDevice device = MetalDevice.current;
        return device == null ? 1.0F : device.shaders().renderScale();
    }

    public static int scaled(final int size) {
        return Math.max(1, Math.round(size * scale()));
    }

    /** Stretches what was drawn into `source` over `target`, which is `width` by `height`. */
    public static void upscale(final GpuTexture source, final GpuTexture target, final int width, final int height) {
        MetalDevice device = MetalDevice.current;
        if (device != null) {
            device.shaders().upscale(device.encoder().frame(), ((MetalTexture)source).handle(), ((MetalTexture)target).handle(), width, height);
        }
    }
}
