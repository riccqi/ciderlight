package dev.ciderlight.backend;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * How much the shader pipeline spends on a frame. LOW is for phone-class GPUs (the A-series chip in the MacBook Neo),
 * which have about a third of an M-series GPU, and for the plain M1 to M4, with less GPU than an M1 Pro and mostly in
 * fanless MacBook Airs that slow down as they heat up: smaller shadow maps, a coarser fog and ambient-occlusion buffer, and shorter fog and reflection marches, and the world drawn at two thirds
 * of the window's resolution (RenderScale). The Shaders setting in Video Settings (ShaderSetting) overrides the choice
 * made from the GPU's name, and -Dciderlight.quality=low|high overrides both.
 */
enum Quality {
    //   shadow, far, far beyond FAR_SHADOW_FINE_RADIUS, fog rows, AO rows, render scale
    LOW(2048, 1024, 2048, 320, 400, 0.67F),
    HIGH(4096, 2048, 4096, 480, 640, 1.0F);

    private static final Logger LOGGER = LogUtils.getLogger();
    private static @Nullable Quality chosen;

    /** The near shadow map's resolution (-Dciderlight.shadowSize overrides it). */
    final int shadowSize;
    /** The distant shadow map's resolution, and its resolution when it covers a long render distance. */
    final int farShadowSize;
    final int farShadowFineSize;
    /** About how many rows the volumetric fog and the ambient occlusion are worked out at, whatever the window's size. */
    final int fogRows;
    final int aoRows;
    /**
     * The world's resolution as a fraction of the window's, in each direction (-Dciderlight.renderScale overrides it,
     * 0.25 to 1). The HUD and menus are always drawn at the window's full resolution.
     */
    final float renderScale;

    Quality(final int shadowSize, final int farShadowSize, final int farShadowFineSize, final int fogRows, final int aoRows,
            final float renderScale) {
        this.shadowSize = Integer.getInteger("ciderlight.shadowSize", shadowSize);
        this.farShadowSize = farShadowSize;
        this.farShadowFineSize = farShadowFineSize;
        this.fogRows = fogRows;
        this.aoRows = aoRows;
        String forced = System.getProperty("ciderlight.renderScale");
        this.renderScale = Math.clamp(forced != null ? Float.parseFloat(forced) : renderScale, 0.25F, 1.0F);
    }

    /** Prepended to every shader source: the march lengths in composite.metal and reflection.metal follow it. */
    String defines() {
        return this == LOW ? "#define MC_QUALITY_LOW 1\n" : "";
    }

    /** The quality chosen at startup, or null before the Metal device exists (or when it never does). */
    static synchronized @Nullable Quality chosen() {
        return chosen;
    }

    /** The quality for this GPU, chosen (and logged) once. */
    static synchronized Quality of(final String deviceName) {
        if (chosen == null) {
            chosen = detect(deviceName);
        }
        return chosen;
    }

    private static Quality detect(final String deviceName) {
        String forced = System.getProperty("ciderlight.quality", "auto").toLowerCase(Locale.ROOT);
        Quality quality = switch (forced) {
            case "low" -> LOW;
            case "high" -> HIGH;
            default -> ShaderSetting.saved() == ShaderSetting.LOW ? LOW
                : ShaderSetting.saved() == ShaderSetting.HIGH ? HIGH
                : lowEnd(deviceName) ? LOW : HIGH;
        };
        LOGGER.info("Ciderlight: {} quality for {}, world at {}% resolution{}", quality.name().toLowerCase(Locale.ROOT), deviceName,
            Math.round(quality.renderScale * 100.0F), forced.equals("auto") ? " (Video Settings > Shaders to change)" : "");
        return quality;
    }

    /**
     * "Apple A18 Pro" and the like, and the plain "Apple M1" to "Apple M4" (7 to 10 GPU cores). Their Pro, Max and
     * Ultra versions, and the plain M5 onwards, keep HIGH.
     */
    static boolean lowEnd(final String deviceName) {
        return deviceName.startsWith("Apple A") || deviceName.matches("Apple M[1-4]");
    }
}
