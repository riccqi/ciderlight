package dev.ciderlight.devrun.mixin;

import net.neoforged.neoforge.common.VersionChecker;
import net.neoforged.neoforge.internal.NeoForgeVersionCheck;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dev client only. NeoForge 26.3's title screen switches on its own version-check status, which is null when the check
 * has nothing to check (always so in a dev environment), and crashes. A failed check shows nothing instead.
 */
@Mixin(NeoForgeVersionCheck.class)
public class NeoForgeVersionCheckMixin {
    @Inject(method = "getStatus", at = @At("RETURN"), cancellable = true, remap = false)
    private static void ciderlightDev$noNullStatus(final CallbackInfoReturnable<VersionChecker.Status> cir) {
        if (cir.getReturnValue() == null) {
            cir.setReturnValue(VersionChecker.Status.FAILED);
        }
    }
}
