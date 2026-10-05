package dev.ciderlight.mixin;

import dev.ciderlight.backend.MetalShaders;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A light source in a player's hand lights the mobs and items around them (and the player), like the terrain,
 * which takes that light in its shader. Nothing changes without the Metal shaders, which leave the held light at 0.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    @Inject(method = "getPackedLightCoords", at = @At("RETURN"), cancellable = true)
    private void ciderlight$heldLight(final Entity entity, final float partialTick, final CallbackInfoReturnable<Integer> cir) {
        int held = MetalShaders.heldLightAt(entity.getLightProbePosition(partialTick));
        int coords = cir.getReturnValue();
        if (held > LightCoordsUtil.block(coords)) {
            cir.setReturnValue(LightCoordsUtil.pack(held, LightCoordsUtil.sky(coords)));
        }
    }
}
