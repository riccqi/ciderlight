package dev.ciderlight.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import dev.ciderlight.backend.MetalShaders;
import dev.ciderlight.backend.RenderScale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws the world at a fraction of the window's resolution (RenderScale), and the HUD and menus at the full one.
 * While the world is drawn, from the level through the hand and post effects, the main target lends out the textures
 * of a smaller one: everything that draws the world (the sky renderer keeps its own reference to the main target, and
 * Ciderlight's passes size themselves by the "Main" pass) then renders smaller without knowing. Afterwards the main
 * target gets its own textures back and the world is stretched over them before the HUD is drawn.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow
    @Final
    private RenderTarget mainRenderTarget;
    @Shadow
    @Final
    private RenderTarget hud3DTarget;
    @Shadow
    @Final
    private Minecraft minecraft;
    /** Holds the smaller textures between frames, and the main target's own while the world is drawn. */
    @Unique
    private @Nullable RenderTarget ciderlight$world;
    @Unique
    private boolean ciderlight$swapped;

    /**
     * Improved Transparency draws the world in a "Solid" pass and the translucents in separate OIT passes, which
     * Ciderlight's lighting (keyed to the "Main" pass) and water don't follow; with shaders on the classic path is used.
     */
    @Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
    private void ciderlight$classicTransparency(final CallbackInfoReturnable<Boolean> cir) {
        if (MetalShaders.ENABLED) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "resize", at = @At("RETURN"))
    private void ciderlight$resizeWorld(final int width, final int height, final CallbackInfo ci) {
        if (RenderScale.scale() < 1.0F) {
            this.ciderlight$sizeWorld(width, height);
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void ciderlight$beginWorld(final CallbackInfo ci) {
        if (this.ciderlight$swapped) {
            this.ciderlight$swap(); // a frame that failed half way left them swapped
        }
        if (RenderScale.scale() >= 1.0F) {
            return;
        }
        RenderTarget world = this.ciderlight$world;
        if (world == null || world.width != RenderScale.scaled(this.mainRenderTarget.width)
            || world.height != RenderScale.scaled(this.mainRenderTarget.height)) {
            this.ciderlight$sizeWorld(this.mainRenderTarget.width, this.mainRenderTarget.height);
        }
        this.ciderlight$swap();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
    private void ciderlight$endWorld(final CallbackInfo ci) {
        if (!this.ciderlight$swapped) {
            return;
        }
        this.ciderlight$swap();
        RenderTarget world = this.ciderlight$world;
        RenderScale.upscale(world.getColorTexture(), this.mainRenderTarget.getColorTexture(), this.mainRenderTarget.width, this.mainRenderTarget.height);
    }

    /** Sizes the smaller target, and the others drawn alongside the world (the hand's depth, the entity outline). */
    @Unique
    private void ciderlight$sizeWorld(final int width, final int height) {
        int w = RenderScale.scaled(width);
        int h = RenderScale.scaled(height);
        if (this.ciderlight$world == null) {
            this.ciderlight$world = new TextureTarget("Main (world)", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        } else {
            this.ciderlight$world.resize(w, h);
        }
        this.hud3DTarget.resize(w, h);
        this.minecraft.levelRenderer.resize(w, h);
    }

    /** Exchanges the main target's textures and size with the smaller target's. */
    @Unique
    private void ciderlight$swap() {
        RenderTarget world = this.ciderlight$world;
        RenderTargetAccessor a = (RenderTargetAccessor)this.mainRenderTarget;
        RenderTargetAccessor b = (RenderTargetAccessor)world;
        var color = a.ciderlight$colorTexture();
        var colorView = a.ciderlight$colorTextureView();
        var depth = a.ciderlight$depthTexture();
        var depthView = a.ciderlight$depthTextureView();
        a.ciderlight$setColorTexture(b.ciderlight$colorTexture());
        a.ciderlight$setColorTextureView(b.ciderlight$colorTextureView());
        a.ciderlight$setDepthTexture(b.ciderlight$depthTexture());
        a.ciderlight$setDepthTextureView(b.ciderlight$depthTextureView());
        b.ciderlight$setColorTexture(color);
        b.ciderlight$setColorTextureView(colorView);
        b.ciderlight$setDepthTexture(depth);
        b.ciderlight$setDepthTextureView(depthView);
        int width = this.mainRenderTarget.width;
        int height = this.mainRenderTarget.height;
        this.mainRenderTarget.width = world.width;
        this.mainRenderTarget.height = world.height;
        world.width = width;
        world.height = height;
        this.ciderlight$swapped = !this.ciderlight$swapped;
    }
}
