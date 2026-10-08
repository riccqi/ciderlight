package dev.ciderlight.backend;

import org.jspecify.annotations.Nullable;

/**
 * The on/off effects on the Ciderlight settings page (CiderlightSettingsScreen), all on by default. Each is saved to
 * config/ciderlight.properties under its key and, like the Shaders choice, takes effect at the next start: the
 * pipelines are built with it. -Dciderlight.KEY=true|false still overrides the saved value.
 */
public enum ShaderToggle {
    SHADOWS("shadows", "Shadows",
        "The sun and moon cast shadows, and light shafts form in the haze. Off skips the shadow maps, the biggest single "
            + "cost on the GPU, and lights everything as if it stood in the open."),
    WAVING("waving", "Waving Plants", "Leaves, grass, flowers and crops sway in the wind."),
    WATER_REFLECTIONS("waterReflections", "Water Reflections",
        "Water mirrors the hills, trees and buildings around it. Off: water reflects the sky only, which costs less."),
    AMBIENT_OCCLUSION("ao", "Ambient Occlusion", "Soft shade in corners, under ledges and between blocks.");

    /** The property key, both in ciderlight.properties and as -Dciderlight.KEY. */
    public final String key;
    public final String label;
    public final String description;
    /** What this session is running with. */
    private final boolean running;
    /** Chosen on the settings page since the game started; applies at the next start. */
    private @Nullable Boolean chosen;

    ShaderToggle(final String key, final String label, final String description) {
        this.key = key;
        this.label = label;
        this.description = description;
        String forced = System.getProperty("ciderlight." + key);
        this.running = !"false".equals(forced != null ? forced : CiderlightConfig.saved(key));
    }

    /** Whether this session runs with the effect. */
    public boolean enabled() {
        return this.running;
    }

    /** What the next start will run with. */
    public boolean current() {
        return this.chosen != null ? this.chosen : this.running;
    }

    public boolean restartRequired() {
        return this.chosen != null && this.chosen != this.running;
    }

    public void choose(final boolean on) {
        this.chosen = on;
        CiderlightConfig.set(this.key, Boolean.toString(on));
    }

    public static boolean anyRestartRequired() {
        for (ShaderToggle toggle : values()) {
            if (toggle.restartRequired()) {
                return true;
            }
        }
        return false;
    }
}
