package dev.ciderlight.mixin;

import dev.ciderlight.backend.RenderScaleSetting;
import dev.ciderlight.backend.ShaderSetting;
import dev.ciderlight.backend.ShaderToggle;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Video Settings shows its "restart required" notice when a Ciderlight setting differs from what is running. */
@Mixin(Options.class)
public class OptionsMixin {
    @Inject(method = "isRestartRequiredToApplyVideoSettings", at = @At("RETURN"), cancellable = true)
    private void ciderlight$shadersNeedRestart(final CallbackInfoReturnable<Boolean> cir) {
        if (ShaderSetting.restartRequired() || ShaderToggle.anyRestartRequired() || RenderScaleSetting.restartRequired()) {
            cir.setReturnValue(true);
        }
    }
}
