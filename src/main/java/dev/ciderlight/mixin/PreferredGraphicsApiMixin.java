package dev.ciderlight.mixin;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.GpuBackend;
import dev.ciderlight.backend.MetalBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;

/**
 * Tries Metal first, falling back to the vanilla backends. Set -Dciderlight.disable=true to skip it; it is skipped
 * on machines that cannot run it (see MetalBackend.unsupportedReason), so the game starts with its usual renderer.
 */
@Mixin(PreferredGraphicsApi.class)
public class PreferredGraphicsApiMixin {
    @Unique
    private static final Logger CIDERLIGHT_LOGGER = LogUtils.getLogger();
    @Unique
    private static boolean ciderlight$loggedUnsupported;

    @Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
    private void ciderlight$preferMetal(final CallbackInfoReturnable<GpuBackend[]> cir) {
        if (Boolean.getBoolean("ciderlight.disable")) {
            return;
        }
        String unsupported = MetalBackend.unsupportedReason();
        if (unsupported != null) {
            if (!ciderlight$loggedUnsupported) {
                ciderlight$loggedUnsupported = true;
                CIDERLIGHT_LOGGER.warn("Ciderlight is turned off because {}; using the default renderer", unsupported);
            }
            return;
        }
        GpuBackend[] vanilla = cir.getReturnValue();
        GpuBackend[] backends = new GpuBackend[vanilla.length + 1];
        backends[0] = new MetalBackend();
        System.arraycopy(vanilla, 0, backends, 1, vanilla.length);
        cir.setReturnValue(backends);
    }
}
