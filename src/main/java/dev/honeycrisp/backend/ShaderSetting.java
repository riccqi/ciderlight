package dev.honeycrisp.backend;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The player's choice of shaders in Video Settings (VideoSettingsScreenMixin): off, low or high quality. Without a
 * saved choice, Quality picks low for A-series GPUs and high otherwise. The choice is saved to
 * config/honeycrisp.properties and takes effect at the next start; -Dhoneycrisp.shaders=false and
 * -Dhoneycrisp.quality=low|high still override it.
 */
public enum ShaderSetting {
    OFF("Off"),
    LOW("Low"),
    HIGH("High");

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("honeycrisp.properties");
    private static final @Nullable ShaderSetting SAVED = load();
    /** Chosen in Video Settings since the game started; applies at the next start. */
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
        Properties properties = new Properties();
        properties.setProperty("shaders", setting.name().toLowerCase(Locale.ROOT));
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer out = Files.newBufferedWriter(FILE)) {
                properties.store(out, "Honeycrisp: shaders = off, low or high (set in Video Settings, applies after a restart)");
            }
        } catch (IOException e) {
            LOGGER.warn("Honeycrisp: could not save {}", FILE, e);
        }
    }

    private static @Nullable ShaderSetting load() {
        if (!Files.exists(FILE)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(FILE)) {
            properties.load(in);
        } catch (IOException e) {
            LOGGER.warn("Honeycrisp: could not read {}", FILE, e);
            return null;
        }
        String value = properties.getProperty("shaders", "").trim().toUpperCase(Locale.ROOT);
        for (ShaderSetting setting : values()) {
            if (setting.name().equals(value)) {
                return setting;
            }
        }
        return null;
    }
}
