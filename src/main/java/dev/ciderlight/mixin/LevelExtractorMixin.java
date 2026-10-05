package dev.ciderlight.mixin;

import dev.ciderlight.backend.MetalShaders;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Minecraft leaves the player's own body out of the frame in first person, so it would cast no shadow. While shadows
 * are on it is extracted like in third person; LevelRendererMixin then keeps it out of sight.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
    @Redirect(
        method = "extractVisibleEntities",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;isDetached()Z")
    )
    private boolean ciderlight$extractOwnBody(final Camera camera) {
        return camera.isDetached() || MetalShaders.castsOwnShadow(camera);
    }
}
