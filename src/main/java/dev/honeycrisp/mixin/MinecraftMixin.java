package dev.honeycrisp.mixin;

import dev.honeycrisp.Benchmark;
import dev.honeycrisp.backend.FramePacer;
import dev.honeycrisp.backend.HitchTrace;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void honeycrisp$benchTick(final CallbackInfo ci) {
        if (Benchmark.enabled()) {
            Benchmark.onTick((Minecraft)(Object)this);
        }
        Minecraft minecraft = (Minecraft)(Object)this;
        if (Benchmark.enabled() || Benchmark.autoMoves()) {
            Benchmark.skipBackupPrompt(minecraft);
        }
        FramePacer.worldTick(minecraft.level != null, minecraft.gui.screen() == null);
        if (HitchTrace.ENABLED) {
            HitchTrace.tickStart(minecraft.level != null, minecraft.gui.screen() == null);
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void honeycrisp$traceTickEnd(final CallbackInfo ci) {
        if (HitchTrace.ENABLED) {
            HitchTrace.tickEnd();
        }
    }

    @Inject(method = "runTick", at = @At("HEAD"))
    private void honeycrisp$benchFrame(final boolean advanceGameTime, final CallbackInfo ci) {
        if (Benchmark.enabled()) {
            Benchmark.onFrame((Minecraft)(Object)this);
        }
        if (Benchmark.autoMoves()) {
            Benchmark.autoMove((Minecraft)(Object)this);
        }
    }
}
