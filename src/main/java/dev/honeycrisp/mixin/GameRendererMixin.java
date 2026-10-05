package dev.honeycrisp.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import dev.honeycrisp.backend.RenderScale;
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

/**
 * Draws the world at a fraction of the window's resolution (RenderScale), and the HUD and menus at the full one.
 * While the world is drawn, from the level through the hand and post effects, the main target lends out the textures
 * of a smaller one: everything that draws the world (the sky renderer keeps its own reference to the main target, and
 * Honeycrisp's passes size themselves by the "Main" pass) then renders smaller without knowing. Afterwards the main
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
    private @Nullable RenderTarget honeycrisp$world;
    @Unique
    private boolean honeycrisp$swapped;

    @Inject(method = "resize", at = @At("RETURN"))
    private void honeycrisp$resizeWorld(final int width, final int height, final CallbackInfo ci) {
        if (RenderScale.scale() < 1.0F) {
            this.honeycrisp$sizeWorld(width, height);
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void honeycrisp$beginWorld(final CallbackInfo ci) {
        if (this.honeycrisp$swapped) {
            this.honeycrisp$swap(); // a frame that failed half way left them swapped
        }
        if (RenderScale.scale() >= 1.0F) {
            return;
        }
        RenderTarget world = this.honeycrisp$world;
        if (world == null || world.width != RenderScale.scaled(this.mainRenderTarget.width)
            || world.height != RenderScale.scaled(this.mainRenderTarget.height)) {
            this.honeycrisp$sizeWorld(this.mainRenderTarget.width, this.mainRenderTarget.height);
        }
        this.honeycrisp$swap();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
    private void honeycrisp$endWorld(final CallbackInfo ci) {
        if (!this.honeycrisp$swapped) {
            return;
        }
        this.honeycrisp$swap();
        RenderTarget world = this.honeycrisp$world;
        RenderScale.upscale(world.getColorTexture(), this.mainRenderTarget.getColorTexture(), this.mainRenderTarget.width, this.mainRenderTarget.height);
    }

    /** Sizes the smaller target, and the others drawn alongside the world (the hand's depth, the entity outline). */
    @Unique
    private void honeycrisp$sizeWorld(final int width, final int height) {
        int w = RenderScale.scaled(width);
        int h = RenderScale.scaled(height);
        if (this.honeycrisp$world == null) {
            this.honeycrisp$world = new TextureTarget("Main (world)", w, h, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        } else {
            this.honeycrisp$world.resize(w, h);
        }
        this.hud3DTarget.resize(w, h);
        this.minecraft.levelRenderer.resize(w, h);
    }

    /** Exchanges the main target's textures and size with the smaller target's. */
    @Unique
    private void honeycrisp$swap() {
        RenderTarget world = this.honeycrisp$world;
        RenderTargetAccessor a = (RenderTargetAccessor)this.mainRenderTarget;
        RenderTargetAccessor b = (RenderTargetAccessor)world;
        var color = a.honeycrisp$colorTexture();
        var colorView = a.honeycrisp$colorTextureView();
        var depth = a.honeycrisp$depthTexture();
        var depthView = a.honeycrisp$depthTextureView();
        a.honeycrisp$setColorTexture(b.honeycrisp$colorTexture());
        a.honeycrisp$setColorTextureView(b.honeycrisp$colorTextureView());
        a.honeycrisp$setDepthTexture(b.honeycrisp$depthTexture());
        a.honeycrisp$setDepthTextureView(b.honeycrisp$depthTextureView());
        b.honeycrisp$setColorTexture(color);
        b.honeycrisp$setColorTextureView(colorView);
        b.honeycrisp$setDepthTexture(depth);
        b.honeycrisp$setDepthTextureView(depthView);
        int width = this.mainRenderTarget.width;
        int height = this.mainRenderTarget.height;
        this.mainRenderTarget.width = world.width;
        this.mainRenderTarget.height = world.height;
        world.width = width;
        world.height = height;
        this.honeycrisp$swapped = !this.honeycrisp$swapped;
    }
}
