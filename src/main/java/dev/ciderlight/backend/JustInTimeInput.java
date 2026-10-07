package dev.ciderlight.backend;

import java.util.concurrent.locks.LockSupport;

/**
 * Reads the mouse and keyboard as late as the GPU allows.
 *
 * <p>When the GPU is what limits the frame rate, the render thread used to read input for the next frame right after
 * handing the current one to the GPU, build the next frame, and then wait for the GPU to finish the current one before
 * the next could start: every key and mouse movement waited a whole GPU frame before the GPU even began drawing it.
 * Waiting here instead, between frames, until the GPU is about to finish the current frame (less the time a frame takes
 * to build) lets the next frame read input that much later and still be ready when the GPU is. The GPU stays as busy as
 * before; what reaches the screen is a frame fresher. -Dciderlight.jitInput=false turns it off.
 *
 * <p>The guesses err towards starting early: the shortest GPU time and the longest build time of the last few frames,
 * and a margin. Starting too early only gives back some of the gain; starting too late would leave the GPU idle.
 */
final class JustInTimeInput {
    static final boolean ENABLED = !"false".equals(System.getProperty("ciderlight.jitInput"));
    private static final int HISTORY = 8;
    /** How much before the GPU is expected to finish the next frame is committed. */
    private static final double MARGIN = 0.0015;
    /** Never waits longer than this: a frame that slow is a hitch, and waiting would only add to it. */
    private static final double MAX_WAIT = 0.05;

    private final double[] gpuTimes = new double[HISTORY];
    private final double[] buildTimes = new double[HISTORY];
    private int gpuCount;
    private int buildCount;
    private final double[] timing = new double[5];
    private long lastFinished = -1L;
    private long lastCommitted = -1L;
    /** When the frame being built read its input (mediaTime clock), 0 before the first. */
    private double inputRead;
    /** When each recent frame read its input, by frame index, for the trace's input-to-GPU-done time. */
    private final long[] readIndex = new long[HISTORY];
    private final double[] readAt = new double[HISTORY];

    /** Called between frames, once the last one has been presented: returns when the next one should read input. */
    void beforeNextFrame() {
        MetalNative.frameTiming(this.timing);
        long committed = (long)this.timing[0];
        double committedAt = this.timing[1];
        long finished = (long)this.timing[2];
        double gpuStart = this.timing[3];
        double gpuEnd = this.timing[4];
        if (finished != this.lastFinished && finished > 0L && gpuEnd > gpuStart) {
            this.lastFinished = finished;
            this.gpuTimes[this.gpuCount++ % HISTORY] = gpuEnd - gpuStart;
            int slot = (int)(finished % HISTORY);
            if (HitchTrace.ENABLED && this.readIndex[slot] == finished) {
                HitchTrace.inputToGpuDone(gpuEnd - this.readAt[slot]);
            }
        }
        if (committed != this.lastCommitted && this.inputRead > 0.0 && committedAt > this.inputRead) {
            this.lastCommitted = committed;
            this.buildTimes[this.buildCount++ % HISTORY] = committedAt - this.inputRead;
            int slot = (int)(committed % HISTORY);
            this.readIndex[slot] = committed;
            this.readAt[slot] = this.inputRead;
        }
        double now = MetalNative.mediaTime();
        // Only while the GPU is still on the latest frame, which it began when that was committed or, if the GPU was
        // busy then, when it finished the one before.
        if (ENABLED && finished == committed - 1L && this.gpuCount >= HISTORY && this.buildCount >= HISTORY) {
            double start = Math.max(committedAt, gpuEnd);
            double target = start + min(this.gpuTimes) - max(this.buildTimes) - MARGIN;
            double wait = Math.min(target - now, MAX_WAIT);
            if (wait > 0.0005) {
                long waitStart = System.nanoTime();
                LockSupport.parkNanos((long)(wait * 1e9));
                if (HitchTrace.ENABLED) {
                    HitchTrace.add(HitchTrace.INPUT_WAIT, System.nanoTime() - waitStart);
                }
            }
        }
        this.inputRead = MetalNative.mediaTime();
    }

    private static double min(final double[] values) {
        double result = Double.MAX_VALUE;
        for (double v : values) {
            result = Math.min(result, v);
        }
        return result;
    }

    private static double max(final double[] values) {
        double result = 0.0;
        for (double v : values) {
            result = Math.max(result, v);
        }
        return result;
    }
}
