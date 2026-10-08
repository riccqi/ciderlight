package dev.ciderlight.gui;

import com.mojang.serialization.Codec;
import dev.ciderlight.backend.RenderScaleSetting;
import dev.ciderlight.backend.ShaderSetting;
import dev.ciderlight.backend.ShaderToggle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Ciderlight's settings, opened from the Ciderlight button in Video Settings (VideoSettingsScreenMixin): the Shaders
 * quality, the effects that can be turned off one by one (ShaderToggle) and the world's render scale. Everything here
 * is saved to config/ciderlight.properties and applies after restarting the game, as the shader pipelines are built
 * with it; a changed value says so in its tooltip, and Video Settings shows its restart notice. An option set by its
 * -Dciderlight.* override is greyed out, as the override would win after the restart.
 */
public class CiderlightSettingsScreen extends OptionsSubScreen {
    private static final Component TITLE = Component.literal("Ciderlight Settings");
    private static final Component RESTART = Component.literal("Applies after restarting the game").withStyle(ChatFormatting.RED);
    private static final Component SHADERS_TOOLTIP = Component.literal(
        "Ciderlight's shadows, light shafts, water, sky and waving plants. Low is lighter on the GPU (smaller shadow maps, "
            + "coarser fog, the world at two thirds of the window's resolution) and is the default on A-series chips and the plain M1 to M4. "
            + "Off keeps the Metal renderer with Minecraft's own look.");
    private static final Component RENDER_SCALE_TOOLTIP = Component.literal(
        "The world's resolution as a share of the window's; the HUD and menus stay sharp. Lower is faster. "
            + "Auto: two thirds on Low, full on High.");

    /** The options that only mean something with shaders on: greyed out while Shaders is Off. */
    private final List<OptionInstance<?>> effects = new ArrayList<>();
    /** The effects set by a -Dciderlight.* override: always greyed out. */
    private final Set<OptionInstance<?>> forced = new HashSet<>();

    public CiderlightSettingsScreen(final Screen lastScreen, final Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        OptionInstance<ShaderSetting> shaders = new OptionInstance<>(
            "Shaders",
            value -> tooltip(SHADERS_TOOLTIP, value != ShaderSetting.running() ? RESTART : null),
            (caption, value) -> Component.literal(value.label),
            new OptionInstance.Enum<>(List.of(ShaderSetting.values()), Codec.STRING.xmap(ShaderSetting::valueOf, ShaderSetting::name)),
            ShaderSetting.current(),
            value -> {
                ShaderSetting.choose(value);
                this.updateEffects();
            }
        );
        this.list.addBig(shaders);
        this.effects.clear();
        this.forced.clear();
        for (ShaderToggle toggle : ShaderToggle.values()) {
            Component description = Component.literal(toggle.description);
            Component note = toggle.forced() ? forcedNote(toggle.key) : RESTART;
            OptionInstance<Boolean> option = OptionInstance.createBoolean(
                toggle.label, value -> tooltip(description, toggle.forced() || value != toggle.enabled() ? note : null), toggle.current(), toggle::choose
            );
            this.effects.add(option);
            if (toggle.forced()) {
                this.forced.add(option);
            }
        }
        Component renderScaleNote = RenderScaleSetting.forced() ? forcedNote("renderScale") : RESTART;
        OptionInstance<RenderScaleSetting> renderScale = new OptionInstance<>(
            "Render Scale",
            value -> tooltip(RENDER_SCALE_TOOLTIP, RenderScaleSetting.forced() || value != RenderScaleSetting.running() ? renderScaleNote : null),
            (caption, value) -> Component.literal(value.label),
            new OptionInstance.Enum<>(List.of(RenderScaleSetting.values()), Codec.STRING.xmap(RenderScaleSetting::valueOf, RenderScaleSetting::name)),
            RenderScaleSetting.current(),
            RenderScaleSetting::choose
        );
        this.effects.add(renderScale);
        if (RenderScaleSetting.forced()) {
            this.forced.add(renderScale);
        }
        this.list.addSmall(this.effects.toArray(new OptionInstance<?>[0]));
        this.updateEffects();
    }

    private void updateEffects() {
        boolean on = ShaderSetting.current() != ShaderSetting.OFF;
        for (OptionInstance<?> option : this.effects) {
            AbstractWidget widget = this.list.findOption(option);
            if (widget != null) {
                widget.active = on && !this.forced.contains(option);
            }
        }
    }

    private static Component forcedNote(final String key) {
        return Component.literal("Set by -Dciderlight." + key + " in the JVM arguments").withStyle(ChatFormatting.YELLOW);
    }

    /** The description, under a note (restart needed, or set by an override) if there is one. */
    private static Tooltip tooltip(final Component description, final @Nullable Component note) {
        List<Component> lines = new ArrayList<>();
        if (note != null) {
            lines.add(note);
            lines.add(CommonComponents.EMPTY);
        }
        lines.add(description);
        return Tooltip.create(CommonComponents.joinLines(lines));
    }
}
