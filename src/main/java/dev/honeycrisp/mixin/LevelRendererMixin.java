package dev.honeycrisp.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.honeycrisp.backend.MetalShaders;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * In first person the player's own body is submitted far below the world: the main pass never sees it, and the shadow
 * pass (entity.metal) moves it back, so it only casts a shadow.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Redirect(
        method = "submitEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"
        )
    )
    private void honeycrisp$hideOwnBody(final EntityRenderDispatcher dispatcher, final EntityRenderState state, final CameraRenderState camera,
                                        final double x, final double y, final double z, final PoseStack poseStack,
                                        final SubmitNodeCollector collector) {
        dispatcher.submit(state, camera, x, MetalShaders.isHiddenOwnBody(state, camera) ? y - MetalShaders.OWN_BODY_DROP : y, z, poseStack, collector);
    }
}
