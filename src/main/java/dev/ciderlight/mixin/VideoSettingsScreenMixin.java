package dev.ciderlight.mixin;

import dev.ciderlight.backend.ShaderSetting;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Puts a Shaders button (off, low, high) first among Video Settings' quality options, under the graphics preset. */
@Mixin(VideoSettingsScreen.class)
public class VideoSettingsScreenMixin {
    @Unique
    private static final Component CIDERLIGHT_TOOLTIP = Component.literal(
        "Ciderlight's shadows, light shafts, water, sky and waving plants. Low is lighter on the GPU (smaller shadow maps, "
            + "coarser fog, the world at two thirds of the window's resolution) and is the default on A-series chips and the plain M1 to M4. "
            + "Off keeps the Metal renderer with Minecraft's own look.");
    @Unique
    private static final Component CIDERLIGHT_RESTART = Component.literal("Applies after restarting the game").withStyle(ChatFormatting.RED);

    @Inject(method = "qualityOptions", at = @At("RETURN"), cancellable = true)
    private static void ciderlight$addShaders(final Options options, final CallbackInfoReturnable<OptionInstance<?>[]> cir) {
        if (!ShaderSetting.available()) {
            return;
        }
        OptionInstance<ShaderSetting> shaders = new OptionInstance<>(
            "Shaders",
            value -> {
                List<Component> lines = new ArrayList<>();
                if (value != ShaderSetting.running()) {
                    lines.add(CIDERLIGHT_RESTART);
                    lines.add(CommonComponents.EMPTY);
                }
                lines.add(CIDERLIGHT_TOOLTIP);
                return Tooltip.create(CommonComponents.joinLines(lines));
            },
            (caption, value) -> Component.literal(value.label),
            new OptionInstance.Enum<>(List.of(ShaderSetting.values()), com.mojang.serialization.Codec.STRING.xmap(ShaderSetting::valueOf, ShaderSetting::name)),
            ShaderSetting.current(),
            ShaderSetting::choose
        );
        OptionInstance<?>[] vanilla = cir.getReturnValue();
        OptionInstance<?>[] all = new OptionInstance<?>[vanilla.length + 1];
        all[0] = shaders;
        System.arraycopy(vanilla, 0, all, 1, vanilla.length);
        cir.setReturnValue(all);
    }
}
