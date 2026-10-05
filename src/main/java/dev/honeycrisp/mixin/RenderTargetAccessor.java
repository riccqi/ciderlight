package dev.honeycrisp.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** For GameRendererMixin, which lends the main target a smaller set of textures while the world is drawn. */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
    @Accessor("colorTexture")
    GpuTexture honeycrisp$colorTexture();

    @Accessor("colorTexture")
    void honeycrisp$setColorTexture(GpuTexture texture);

    @Accessor("colorTextureView")
    GpuTextureView honeycrisp$colorTextureView();

    @Accessor("colorTextureView")
    void honeycrisp$setColorTextureView(GpuTextureView view);

    @Accessor("depthTexture")
    GpuTexture honeycrisp$depthTexture();

    @Accessor("depthTexture")
    void honeycrisp$setDepthTexture(GpuTexture texture);

    @Accessor("depthTextureView")
    GpuTextureView honeycrisp$depthTextureView();

    @Accessor("depthTextureView")
    void honeycrisp$setDepthTextureView(GpuTextureView view);
}
