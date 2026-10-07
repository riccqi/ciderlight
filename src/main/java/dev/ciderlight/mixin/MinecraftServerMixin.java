package dev.ciderlight.mixin;

import dev.ciderlight.backend.HitchTrace;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times the integrated server's ticks for the hitch trace: a slow tick delays block breaking and placing, not frames. */
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
    @Unique
    private long ciderlight$tickStart;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void ciderlight$traceTickStart(final BooleanSupplier haveTime, final CallbackInfo ci) {
        if (HitchTrace.ENABLED) {
            this.ciderlight$tickStart = System.nanoTime();
        }
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void ciderlight$traceTickEnd(final BooleanSupplier haveTime, final CallbackInfo ci) {
        if (HitchTrace.ENABLED) {
            HitchTrace.serverTick(System.nanoTime() - this.ciderlight$tickStart);
        }
    }
}
