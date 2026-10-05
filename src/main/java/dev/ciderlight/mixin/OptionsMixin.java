package dev.ciderlight.mixin;

import dev.ciderlight.backend.ShaderSetting;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Video Settings shows its "restart required" notice when the Shaders choice differs from what is running. */
@Mixin(Options.class)
public class OptionsMixin {
    @Inject(method = "isRestartRequiredToApplyVideoSettings", at = @At("RETURN"), cancellable = true)
    private void ciderlight$shadersNeedRestart(final CallbackInfoReturnable<Boolean> cir) {
        if (ShaderSetting.restartRequired()) {
            cir.setReturnValue(true);
        }
    }
}
