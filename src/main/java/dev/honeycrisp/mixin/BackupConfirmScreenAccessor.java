package dev.honeycrisp.mixin;

import net.minecraft.client.gui.screens.BackupConfirmScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** For Benchmark, which answers the "back up this world first?" question itself in unattended runs. */
@Mixin(BackupConfirmScreen.class)
public interface BackupConfirmScreenAccessor {
    @Accessor("onProceed")
    BackupConfirmScreen.Listener honeycrisp$onProceed();
}
