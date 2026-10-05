package dev.ciderlight.backend;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import net.minecraft.util.Util;

/**
 * Shader libraries and pipeline states built on a background thread before the render thread first draws with them.
 * With a cold Metal shader cache (the first launch after installing or updating) each takes tens of milliseconds, and
 * built where they were first needed they held up that frame; now the frame waits only for what is not done yet.
 */
final class Prebuild {
    private Prebuild() {
    }

    static CompletableFuture<Long> start(final LongSupplier build) {
        return CompletableFuture.supplyAsync(build::getAsLong, Util.backgroundExecutor());
    }

    /** The handle the build made, waiting for it if it is still under way; a failed build throws its own exception. */
    static long take(final CompletableFuture<Long> build, final String what) {
        long start = HitchTrace.ENABLED && !build.isDone() ? System.nanoTime() : 0L;
        try {
            long handle = build.join();
            if (start != 0L) {
                HitchTrace.waitedForBuild(System.nanoTime() - start, what);
            }
            return handle;
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException cause ? cause : e;
        }
    }

    /** Releases what the build made once it is done, unless that is `kept` (taken by its owner, who releases it). */
    static void releaseWhenDone(final CompletableFuture<Long> build, final long kept) {
        build.thenAccept(handle -> {
            if (handle != 0L && handle != kept) {
                MetalNative.release(handle);
            }
        });
    }
}
