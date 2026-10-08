package dev.ciderlight.backend;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The player's choice of shaders on the Ciderlight settings page (CiderlightSettingsScreen): off, low or high quality.
 * Without a saved choice, Quality picks low for A-series GPUs and the plain M1 to M4, and high otherwise. The choice
 * is saved to config/ciderlight.properties (CiderlightConfig) and takes effect at the next start;
 * -Dciderlight.shaders=false and -Dciderlight.quality=low|high still override it.
 */
public enum ShaderSetting {
    OFF("Off"),
    LOW("Low"),
    HIGH("High");

    private static final @Nullable ShaderSetting SAVED = load();
    /** Chosen on the settings page since the game started; applies at the next start. */
    private static @Nullable ShaderSetting chosen;

    public final String label;

    ShaderSetting(final String label) {
        this.label = label;
    }

    /** The choice saved when the game started, or null to let the GPU decide. */
    static @Nullable ShaderSetting saved() {
        return SAVED;
    }

    /** Whether the Metal backend is running, so the setting means something. */
    public static boolean available() {
        return Quality.chosen() != null;
    }

    /** What this session is running with. */
    public static ShaderSetting running() {
        if (!MetalShaders.ENABLED) {
            return OFF;
        }
        return Quality.chosen() == Quality.LOW ? LOW : HIGH;
    }

    /** What the next start will run with. */
    public static ShaderSetting current() {
        return chosen != null ? chosen : running();
    }

    public static boolean restartRequired() {
        return chosen != null && chosen != running();
    }

    public static void choose(final ShaderSetting setting) {
        chosen = setting;
        CiderlightConfig.set("shaders", setting.name().toLowerCase(Locale.ROOT));
    }

    private static @Nullable ShaderSetting load() {
        String saved = CiderlightConfig.saved("shaders");
        if (saved == null) {
            return null;
        }
        String value = saved.trim().toUpperCase(Locale.ROOT);
        for (ShaderSetting setting : values()) {
            if (setting.name().equals(value)) {
                return setting;
            }
        }
        return null;
    }
}
