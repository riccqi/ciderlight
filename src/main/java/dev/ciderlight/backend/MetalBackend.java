package dev.ciderlight.backend;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;

public class MetalBackend implements GpuBackend {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Why this machine cannot run Ciderlight, or null when it can. The native library is built for Apple Silicon
     * only, and the shaders read the framebuffer in place, which only Apple GPUs can do (not Intel or AMD ones).
     */
    public static @Nullable String unsupportedReason() {
        String os = System.getProperty("os.name", "");
        if (!os.startsWith("Mac")) {
            return "it only runs on macOS (this is " + os + ")";
        }
        String arch = System.getProperty("os.arch", "");
        if (!arch.equals("aarch64") && !arch.equals("arm64")) {
            // Also the case for an Intel Java running under Rosetta on an Apple Silicon Mac.
            return "it needs an Apple Silicon Mac and an arm64 Java (this Java is " + arch + ")";
        }
        // The native library is built for macOS 15 and newer (-mmacosx-version-min in build.gradle).
        String version = System.getProperty("os.version", "");
        try {
            if (Integer.parseInt(version.split("\\.")[0]) < 15) {
                return "it needs macOS 15 or newer (this is macOS " + version + ")";
            }
        } catch (NumberFormatException e) {
            // Unknown version string: try anyway, loadLibrary still falls back if the library will not load.
        }
        return null;
    }

    @Override
    public String getName() {
        return "Metal";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        try {
            MetalConst.verify();
            MetalNative.load();
        } catch (Throwable t) {
            throw new BackendCreationException("Failed to load Ciderlight native library: " + t, BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }

    @Override
    public void unloadLibrary() {
    }

    @Override
    public long createWindow(@Nullable final String title, final int width, final int height, final long flags) {
        return SDLVideo.SDL_CreateWindow(title, width, height, SDLVideo.SDL_WINDOW_METAL | flags);
    }

    @Override
    public GpuDevice createDevice(final GpuDebugOptions debugOptions) throws BackendCreationException {
        long context;
        try {
            context = MetalNative.contextCreate();
        } catch (RuntimeException e) {
            throw new BackendCreationException(e.getMessage(), BackendCreationException.Reason.PLATFORM_ERROR);
        }
        if (MetalNative.contextLimits(context)[3] == 0) {
            MetalNative.contextDestroy(context);
            throw new BackendCreationException(
                "Ciderlight needs an Apple Silicon GPU (Apple7 family or newer)", BackendCreationException.Reason.PLATFORM_ERROR
            );
        }
        // What the GPU and the display did with each frame: for the hitch trace and frame pacing only.
        MetalNative.traceEnable(HitchTrace.ENABLED || FramePacer.ENABLED);
        if ((MetalNative.GPU_PROFILE || HitchTrace.ENABLED) && !MetalNative.profileEnable(context, MetalNative.GPU_PROFILE)) {
            LOGGER.warn("Ciderlight: this GPU has no timestamp counters, no GPU profile");
        }
        MetalDevice device = new MetalDevice(context, debugOptions.useLabels());
        LOGGER.info("Ciderlight: created Metal device {}", device.getDeviceInfo().name());
        return new FrontendGpuDevice(device);
    }
}
