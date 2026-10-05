package dev.honeycrisp.backend;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * How much the shader pipeline spends on a frame. LOW is for phone-class GPUs (the A-series chip in the MacBook Neo),
 * which have about a third of an M-series GPU: smaller shadow maps, a coarser fog and ambient-occlusion buffer, and
 * shorter fog and reflection marches.
 * -Dhoneycrisp.quality=low|high overrides the choice made from the GPU's name.
 */
enum Quality {
    //   shadow, far, far beyond FAR_SHADOW_FINE_RADIUS, fog rows, AO rows
    LOW(2048, 1024, 2048, 320, 400),
    HIGH(4096, 2048, 4096, 480, 640);

    private static final Logger LOGGER = LogUtils.getLogger();
    private static @Nullable Quality chosen;

    /** The near shadow map's resolution (-Dhoneycrisp.shadowSize overrides it). */
    final int shadowSize;
    /** The distant shadow map's resolution, and its resolution when it covers a long render distance. */
    final int farShadowSize;
    final int farShadowFineSize;
    /** About how many rows the volumetric fog and the ambient occlusion are worked out at, whatever the window's size. */
    final int fogRows;
    final int aoRows;

    Quality(final int shadowSize, final int farShadowSize, final int farShadowFineSize, final int fogRows, final int aoRows) {
        this.shadowSize = Integer.getInteger("honeycrisp.shadowSize", shadowSize);
        this.farShadowSize = farShadowSize;
        this.farShadowFineSize = farShadowFineSize;
        this.fogRows = fogRows;
        this.aoRows = aoRows;
    }

    /** Prepended to every shader source: the march lengths in composite.metal and reflection.metal follow it. */
    String defines() {
        return this == LOW ? "#define MC_QUALITY_LOW 1\n" : "";
    }

    /** The quality for this GPU, chosen (and logged) once. */
    static synchronized Quality of(final String deviceName) {
        if (chosen == null) {
            chosen = detect(deviceName);
        }
        return chosen;
    }

    private static Quality detect(final String deviceName) {
        String forced = System.getProperty("honeycrisp.quality", "auto").toLowerCase(Locale.ROOT);
        Quality quality = switch (forced) {
            case "low" -> LOW;
            case "high" -> HIGH;
            // "Apple A18 Pro" and the like; the Macs' own chips are "Apple M1" onwards.
            default -> deviceName.startsWith("Apple A") ? LOW : HIGH;
        };
        LOGGER.info("Honeycrisp: {} quality for {}{}", quality.name().toLowerCase(Locale.ROOT), deviceName,
            forced.equals("auto") ? " (-Dhoneycrisp.quality=low|high to change)" : "");
        return quality;
    }
}
