package dev.ciderlight.platform;

import java.nio.file.Path;
import net.neoforged.fml.loading.FMLPaths;

/** Where the mod loader keeps things; the Fabric build has its own copy of this class. */
public final class LoaderPaths {
    private LoaderPaths() {
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }
}
