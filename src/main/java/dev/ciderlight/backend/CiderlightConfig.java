package dev.ciderlight.backend;

import com.mojang.logging.LogUtils;
import dev.ciderlight.platform.LoaderPaths;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * config/ciderlight.properties: what the Ciderlight settings page (CiderlightSettingsScreen) saves. Every setting there
 * applies at the next start, so the file is read once when the game starts and a change only rewrites it: this
 * session keeps running with the values it started with.
 */
final class CiderlightConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Path FILE = LoaderPaths.configDir().resolve("ciderlight.properties");
    /** The file as the game started. */
    private static final Properties SAVED = load();
    /** The file with this session's changes, written out on every change. */
    private static final Properties CHANGED = copy(SAVED);

    private CiderlightConfig() {
    }

    /** The value saved when the game started, or null if there was none. */
    static @Nullable String saved(final String key) {
        return SAVED.getProperty(key);
    }

    static synchronized void set(final String key, final String value) {
        CHANGED.setProperty(key, value);
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer out = Files.newBufferedWriter(FILE)) {
                CHANGED.store(out, "Ciderlight settings (Options > Video Settings > Ciderlight...), applied after a restart");
            }
        } catch (IOException e) {
            LOGGER.warn("Ciderlight: could not save {}", FILE, e);
        }
    }

    private static Properties load() {
        Properties properties = new Properties();
        if (Files.exists(FILE)) {
            try (Reader in = Files.newBufferedReader(FILE)) {
                properties.load(in);
            } catch (IOException e) {
                LOGGER.warn("Ciderlight: could not read {}", FILE, e);
            }
        }
        return properties;
    }

    private static Properties copy(final Properties source) {
        Properties properties = new Properties();
        properties.putAll(source);
        return properties;
    }
}
