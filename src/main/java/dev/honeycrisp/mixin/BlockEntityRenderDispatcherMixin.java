package dev.honeycrisp.mixin;

import dev.honeycrisp.backend.MetalShaders;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A light source in a player's hand lights the chests, signs, beds and other block entities around them, like the
 * mobs (EntityRendererMixin): they are drawn apart from the terrain, whose shader takes that light. Applied once the
 * renderer has extracted its state, as some (double chests, beds) work out their own light there.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public class BlockEntityRenderDispatcherMixin {
    @Inject(method = "tryExtractRenderState", at = @At("RETURN"))
    private void honeycrisp$heldLight(final BlockEntity blockEntity, final float partialTick, final ModelFeatureRenderer.CrumblingOverlay breakProgress,
                                      final boolean offScreen, final CallbackInfoReturnable<BlockEntityRenderState> cir) {
        BlockEntityRenderState state = cir.getReturnValue();
        if (state == null) {
            return;
        }
        int held = MetalShaders.heldLightAt(Vec3.atCenterOf(state.blockPos));
        if (held > LightCoordsUtil.block(state.lightCoords)) {
            state.lightCoords = LightCoordsUtil.pack(held, LightCoordsUtil.sky(state.lightCoords));
        }
    }
}
