package dev.honeycrisp.mixin;

import dev.honeycrisp.backend.MetalShaders;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Water extinction is integrated once in the Metal composite, for terrain and entities alike. */
@Mixin(WaterFogEnvironment.class)
public class WaterFogEnvironmentMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void honeycrisp$waterVolume(final FogData fog, final Camera camera, final ClientLevel level,
                                       final float distance, final DeltaTracker delta, final CallbackInfo ci) {
        if (MetalShaders.usesWaterScattering(camera)) {
            fog.environmentalStart = 1_000_000.0F;
            fog.environmentalEnd = 1_000_001.0F;
            fog.skyEnd = distance;
            fog.cloudEnd = distance;
        }
    }
}
