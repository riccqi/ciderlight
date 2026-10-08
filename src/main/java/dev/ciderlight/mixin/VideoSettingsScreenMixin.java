package dev.ciderlight.mixin;

import dev.ciderlight.backend.ShaderSetting;
import dev.ciderlight.gui.CiderlightSettingsScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts a Ciderlight button under the graphics preset in Video Settings' quality options; it opens Ciderlight's settings
 * (CiderlightSettingsScreen): shaders, effects and render scale.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    @Unique
    private static final Component CIDERLIGHT_BUTTON = Component.literal("Ciderlight...");
    @Unique
    private static final Component CIDERLIGHT_TOOLTIP = Component.literal(
        "Shaders, shadows, waving plants, water reflections, ambient occlusion and the world's render scale.");

    private VideoSettingsScreenMixin(final Screen lastScreen, final Options options, final Component title) {
        super(lastScreen, options, title);
    }

    /**
     * Just before qualityOptions is called: addOptions adds the graphics preset and then the quality options, so this
     * lands between them however many rows come before.
     */
    @Inject(
        method = "addOptions",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/options/VideoSettingsScreen;qualityOptions(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;")
    )
    private void ciderlight$addSettingsButton(final CallbackInfo ci) {
        if (!ShaderSetting.available()) {
            return;
        }
        this.list.addBig(Button.builder(CIDERLIGHT_BUTTON, button -> this.minecraft.gui.setScreen(new CiderlightSettingsScreen(this, this.options)))
            .tooltip(Tooltip.create(CIDERLIGHT_TOOLTIP))
            .build());
    }
}
