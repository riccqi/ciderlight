package dev.honeycrisp.backend;

/**
 * -Dhoneycrisp.debug=true (dev client: -Pdebug) turns on the diagnostics, all off in normal play: the hitch trace (with
 * GPU time per pass and how long frames stay on screen) and the details of shader setup in the game log. Debug views
 * that change the picture keep flags of their own (honeycrisp.shaderDebug, aoDebug, wavingDebug).
 */
public final class MetalDebug {
    public static final boolean ENABLED = Boolean.getBoolean("honeycrisp.debug");

    private MetalDebug() {
    }
}
