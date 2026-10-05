package dev.ciderlight.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** For GameRendererMixin, which lends the main target a smaller set of textures while the world is drawn. */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
    @Accessor("colorTexture")
    GpuTexture ciderlight$colorTexture();

    @Accessor("colorTexture")
    void ciderlight$setColorTexture(GpuTexture texture);

    @Accessor("colorTextureView")
    GpuTextureView ciderlight$colorTextureView();

    @Accessor("colorTextureView")
    void ciderlight$setColorTextureView(GpuTextureView view);

    @Accessor("depthTexture")
    GpuTexture ciderlight$depthTexture();

    @Accessor("depthTexture")
    void ciderlight$setDepthTexture(GpuTexture texture);

    @Accessor("depthTextureView")
    GpuTextureView ciderlight$depthTextureView();

    @Accessor("depthTextureView")
    void ciderlight$setDepthTextureView(GpuTextureView view);
}
