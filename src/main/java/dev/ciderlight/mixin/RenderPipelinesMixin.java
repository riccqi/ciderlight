package dev.ciderlight.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Has every registered pipeline compiled while resources load. Minecraft keeps its list by name, and some pipelines
 * share one (the plain, glint and special-glint item_cutout): only the last of each name was compiled up front, the
 * others on the render thread the first time something drew with them, which for the item in the player's hand is
 * the first frame of the world.
 */
@Mixin(RenderPipelines.class)
public class RenderPipelinesMixin {
    /** Every pipeline passed to register, in order. Made on first use: register runs from the class initializer. */
    @Unique private static @Nullable List<RenderPipeline> ciderlight$registered;

    @Inject(method = "register(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;", at = @At("HEAD"))
    private static void ciderlight$remember(final RenderPipeline pipeline, final CallbackInfoReturnable<RenderPipeline> cir) {
        if (ciderlight$registered == null) {
            ciderlight$registered = new ArrayList<>();
        }
        ciderlight$registered.add(pipeline);
    }

    /** The pipelines whose name another took are added to the optional ones, so that one failing stays harmless. */
    @Inject(method = "optionalPipelines", at = @At("RETURN"), cancellable = true)
    private static void ciderlight$addShadowed(final CallbackInfoReturnable<List<RenderPipeline>> cir) {
        if (ciderlight$registered == null) {
            return;
        }
        Set<RenderPipeline> listed = Collections.newSetFromMap(new IdentityHashMap<>());
        listed.addAll(RenderPipelines.requiredPipelines());
        listed.addAll(cir.getReturnValue());
        List<RenderPipeline> all = new ArrayList<>(cir.getReturnValue());
        for (RenderPipeline pipeline : ciderlight$registered) {
            if (listed.add(pipeline)) {
                all.add(pipeline);
            }
        }
        cir.setReturnValue(all);
    }
}
