package dev.ciderlight.platform;

import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Where the mod loader keeps things; the NeoForge build has its own copy of this class. */
public final class LoaderPaths {
    private LoaderPaths() {
    }

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }
}
