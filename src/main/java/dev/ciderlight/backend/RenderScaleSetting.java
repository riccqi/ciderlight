package dev.ciderlight.backend;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Render Scale choice on the Ciderlight settings page (CiderlightSettingsScreen): the world's resolution as a
 * fraction of the window's (RenderScale), or Auto, the quality's own (two thirds on Low, full on High). Saved to
 * config/ciderlight.properties as "renderScale" (auto, or the fraction) and applied at the next start;
 * -Dciderlight.renderScale still overrides it.
 */
public enum RenderScaleSetting {
    AUTO(0.0F, "Auto"),
    HALF(0.5F, "50%"),
    TWO_THIRDS(0.67F, "67%"),
    THREE_QUARTERS(0.75F, "75%"),
    MOST(0.85F, "85%"),
    FULL(1.0F, "100%");

    private static final RenderScaleSetting SAVED = load();
    /** Chosen on the settings page since the game started; applies at the next start. */
    private static @Nullable RenderScaleSetting chosen;

    /** The fraction in each direction, or 0 for the quality's own. */
    final float scale;
    public final String label;

    RenderScaleSetting(final float scale, final String label) {
        this.scale = scale;
        this.label = label;
    }

    /** The saved fraction, or null for the quality's own (Quality.renderScale). */
    static @Nullable Float savedScale() {
        return SAVED == AUTO ? null : SAVED.scale;
    }

    /** What this session started with. */
    public static RenderScaleSetting running() {
        return SAVED;
    }

    /** What the next start will run with. */
    public static RenderScaleSetting current() {
        return chosen != null ? chosen : SAVED;
    }

    public static boolean restartRequired() {
        return chosen != null && chosen != SAVED;
    }

    public static void choose(final RenderScaleSetting setting) {
        chosen = setting;
        CiderlightConfig.set("renderScale", setting == AUTO ? "auto" : Float.toString(setting.scale));
    }

    private static RenderScaleSetting load() {
        String saved = CiderlightConfig.saved("renderScale");
        if (saved == null || saved.trim().toLowerCase(Locale.ROOT).equals("auto")) {
            return AUTO;
        }
        try {
            float value = Float.parseFloat(saved.trim());
            for (RenderScaleSetting setting : values()) {
                if (setting != AUTO && Math.abs(setting.scale - value) < 0.005F) {
                    return setting;
                }
            }
        } catch (NumberFormatException ignored) {
        }
        return AUTO;
    }
}
