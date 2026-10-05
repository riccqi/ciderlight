package dev.ciderlight.mixin;

import dev.ciderlight.backend.MetalShaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {
    @Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
    private static void ciderlight$volumeReplacesWaterOverlay(final CallbackInfo ci) {
        if (MetalShaders.usesWaterScattering(Minecraft.getInstance().gameRenderer.mainCamera())) {
            ci.cancel();
        }
    }
}
