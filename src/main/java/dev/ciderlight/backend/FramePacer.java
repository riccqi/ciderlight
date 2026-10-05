package dev.ciderlight.backend;

import java.util.Arrays;

/**
 * Keeps every frame on screen for the same length of time under vsync.
 *
 * <p>A game rendering at 85 fps on a 120 Hz display has its frames shown for one refresh, then two, in no fixed order,
 * while the camera turns by the same amount every frame: on screen it speeds up and slows down, which reads as
 * stutter although no frame is slow (fullscreen, where macOS shows frames directly, shows it most). Holding each frame
 * for the same time gives even motion instead.
 *
 * <p>Off unless -Dciderlight.framePacing=true: it holds the frame rate below what the game's own settings allow, and a
 * player who sets Max Framerate to Unlimited expects to see how high it goes. Max Framerate is the way to ask for an
 * even rate (60 on a 120 Hz display) without it.
 *
 * <p>The paces on offer are those the display can show evenly: whole refreshes on a fixed rate display, and any step of
 * its granularity between its shortest and longest refresh interval on a variable refresh one (ProMotion, Adaptive-Sync),
 * which makes 80 and 48 fps as even as 60 on a 120 Hz ProMotion display. The display is asked, not assumed.
 *
 * <p>The pace slows when a fifth of recent frames no longer fit it: a few slow ones (a garbage collection, a burst of chunk
 * uploads) are a hitch whatever the pace, and a slower pace for them would only cost frames afterwards, while work done
 * every few frames (the distant shadow map is redrawn every fourth) makes misses that recur. It quickens, straight to the
 * fastest pace that fits, once nearly all frames have fitted that with room to spare for a second. Evenness is worth a
 * third of the frame rate at most: a pace more than half as long again as what frames cost is not taken, and they run
 * unpaced instead (on a fixed 60 Hz display, at an uneven 45 fps rather than an even 30). Nor is a pace taken that is
 * more than four of the display's steps long: unpaced frames then differ by a step at most, a quarter of their length or
 * less (16.7 and 20.8 ms at 50 fps on a ProMotion display), which hardly shows, and a slower pace would cost frames for
 * nothing. On a display with fine steps no pace is taken at all, since frames already go on screen as they are done.
 *
 * <p>What a frame costs depends on the pace. Unpaced, frames follow each other and the render thread's time per frame
 * (recording plus waiting for the GPU) is the whole cost. Paced, the GPU may finish the previous frame while the render
 * thread waits for the display, so that time can shrink to the recording alone; the GPU's own time per frame has to fit
 * as well before the pace quickens. A faster pace given up again within a few seconds is tried next only after twice as
 * long. Loading screens and a hidden window say nothing about the frames of play: pacing starts afresh when a world
 * opens, and a hidden window changes nothing and forgives the failed tries.
 */
public final class FramePacer {
    static final boolean ENABLED = Boolean.getBoolean("ciderlight.framePacing");
    /** Frames whose cost decides the pace. */
    private static final int WINDOW = 30;
    private static final long MARGIN_NS = 500_000L;
    /** How far inside a faster pace nearly all frames have to finish before it is tried. */
    private static final long STEP_DOWN_MARGIN_NS = 1_500_000L;
    /** How long frames have to fit a faster pace before it is tried; doubled after each try that fails. */
    private static final long SETTLE_NS = 1_000_000_000L;
    private static final long MAX_SETTLE_NS = 16_000_000_000L;
    /** A faster pace given up within this long counts as a failed try. */
    private static final long FAILED_WITHIN_NS = 4_000_000_000L;
    /** How long a faster pace has to hold before failed tries are forgotten. */
    private static final long HELD_NS = 20_000_000_000L;
    /** With frames dropped and none shown for this long, the window is hidden. */
    private static final long HIDDEN_AFTER_NS = 250_000_000L;
    /** On a fixed rate display, the slowest pace: frames slower than this are past helping by pacing. */
    private static final double SLOWEST_FIXED_PACE = 1.0 / 24.0;
    private static final int MAX_PACES = 256;
    /** The longest pace taken, as a multiple of what frames cost. */
    private static final double MAX_SLOWDOWN = 1.5;
    /** The longest pace taken, in display steps. */
    private static final int MAX_STEPS = 4;

    /** A world is open and its loading screen has gone (set every client tick by MinecraftMixin). */
    private static volatile boolean playing;
    /** Counts the times a world has opened. */
    private static volatile int worldsOpened;

    /** Render thread time per frame, and that or the GPU's time per frame if longer. */
    private final long[] costs = new long[WINDOW];
    private final long[] fullCosts = new long[WINDOW];
    private final long[] sorted = new long[WINDOW];
    private final double[] stats = new double[3];
    /** The frame times the display can hold frames for evenly, shortest (its fastest refresh: no pacing) first. */
    private long[] paces = {16_666_667L};
    /** How much under a pace's frame time presentation is asked for, so that timing jitter does not push it a step on. */
    private double slack = 0.001;
    /** The display's step: the time frames stay on screen grows in. */
    private long stepNs = 16_666_667L;
    /** Index into paces. */
    private int pace;
    private int count;
    private int next;
    private int world;
    private long frameStart;
    private long lastShown;
    private long lastDropped;
    /** Since when frames have fitted a faster pace (-1: they do not), and the slowest of the paces they fitted since. */
    private long fitsSince = -1L;
    private int fitting;
    private long settleNs = SETTLE_NS;
    /** When the pace last quickened, or -1 when it has slowed since. */
    private long quickenedAt = -1L;

    /** Called every client tick. */
    public static void worldTick(final boolean inWorld, final boolean noScreen) {
        if (!inWorld) {
            playing = false;
        } else if (!playing && noScreen) {
            playing = true;
            worldsOpened++;
        }
    }

    /**
     * @param interval the display's shortest refresh interval, in seconds
     * @param maxInterval its longest refresh interval if it has a variable refresh rate, else 0
     * @param granularity the step between its refresh intervals if it has a variable refresh rate, else 0
     */
    void reset(final double interval, final double maxInterval, final double granularity) {
        double shortest = interval > 0.0 ? interval : 1.0 / 60.0;
        boolean variable = granularity > 0.0 && maxInterval > shortest;
        double step = variable ? Math.max(granularity, (maxInterval - shortest) / (MAX_PACES - 1)) : shortest;
        double longest = variable ? maxInterval : Math.max(SLOWEST_FIXED_PACE, shortest);
        int n = (int)Math.min(Math.floor((longest - shortest) / step + 1e-6) + 1, MAX_PACES);
        this.paces = new long[n];
        for (int i = 0; i < n; i++) {
            this.paces[i] = (long)((shortest + i * step) * 1e9);
        }
        this.slack = Math.min(0.001, step / 2.0);
        this.stepNs = (long)(step * 1e9);
        this.pace = 0;
        this.frameStart = 0L;
        this.forgive();
    }

    /** Forgets what recent frames cost and the failed tries. */
    private void forgive() {
        this.count = 0;
        this.fitsSince = -1L;
        this.settleNs = SETTLE_NS;
        this.quickenedAt = -1L;
    }

    /** The frame begins: the wait for the display is over. */
    void frameStart() {
        this.frameStart = System.nanoTime();
    }

    /** The frame has been recorded and submitted; the render thread's cost is everything since frameStart. */
    void frameDone() {
        if (!ENABLED || this.frameStart == 0L) {
            return;
        }
        long now = System.nanoTime();
        long cost = now - this.frameStart;
        MetalNative.pacingTake(this.stats);
        if (this.stats[1] > 0.0) {
            this.lastShown = now;
        }
        if (this.stats[2] > 0.0) {
            this.lastDropped = now;
        }
        if (!playing || this.world != worldsOpened) {
            this.world = worldsOpened;
            if (this.pace != 0) {
                this.change(0);
            }
            this.forgive();
            return;
        }
        if (this.lastDropped > this.lastShown && now - this.lastShown > HIDDEN_AFTER_NS) {
            this.forgive();
            return;
        }
        this.costs[this.next] = cost;
        this.fullCosts[this.next] = Math.max(cost, (long)(this.stats[0] * 1e9));
        this.next = (this.next + 1) % WINDOW;
        this.count = Math.min(this.count + 1, WINDOW);
        if (this.quickenedAt >= 0L && now - this.quickenedAt >= HELD_NS) {
            this.quickenedAt = -1L;
            this.settleNs = SETTLE_NS;
        }
        if (this.count < WINDOW) {
            return;
        }
        int needed = this.paceFor(this.percentile(this.costs, 80) + MARGIN_NS);
        if (needed > this.pace) {
            if (this.quickenedAt >= 0L && now - this.quickenedAt < FAILED_WITHIN_NS) {
                this.settleNs = Math.min(this.settleNs * 2L, MAX_SETTLE_NS);
            }
            this.quickenedAt = -1L;
            this.change(needed);
            return;
        }
        int fits = this.paceFor(this.percentile(this.fullCosts, 90) + MARGIN_NS + STEP_DOWN_MARGIN_NS);
        if (fits < this.pace) {
            if (this.fitsSince < 0L) {
                this.fitsSince = now;
                this.fitting = fits;
            } else {
                this.fitting = Math.max(this.fitting, fits);
            }
            if (now - this.fitsSince >= this.settleNs) {
                this.quickenedAt = now;
                this.change(this.fitting);
            }
        } else {
            this.fitsSince = -1L;
        }
    }

    /**
     * The fastest pace frames costing this long fit, or 0 (unpaced) when that would slow them down too much or their
     * unevenness unpaced would hardly show.
     */
    private int paceFor(final long cost) {
        int i = 0;
        while (i < this.paces.length - 1 && cost > this.paces[i]) {
            i++;
        }
        return i > 0 && (this.paces[i] > cost * MAX_SLOWDOWN || this.paces[i] > MAX_STEPS * this.stepNs + this.stepNs / 100) ? 0 : i;
    }

    private long percentile(final long[] values, final int percent) {
        System.arraycopy(values, 0, this.sorted, 0, WINDOW);
        Arrays.sort(this.sorted);
        return this.sorted[Math.min(WINDOW * percent / 100, WINDOW - 1)];
    }

    private void change(final int pace) {
        this.pace = pace;
        // What the last frames cost belongs to the old pace.
        this.count = 0;
        this.fitsSince = -1L;
        if (HitchTrace.ENABLED) {
            HitchTrace.event(String.format(java.util.Locale.ROOT, "frame pacing: %s (faster tries wait %.0fs)",
                pace == 0 ? String.format(java.util.Locale.ROOT, "off, up to %.0f fps", 1e9 / this.paces[0])
                    : String.format(java.util.Locale.ROOT, "%.1f fps (%.2fms)", 1e9 / this.paces[pace], this.paces[pace] / 1e6),
                this.settleNs / 1e9));
        }
    }

    /** How long the previous frame has to stay on screen before the one being presented replaces it; 0 for no limit. */
    double minDuration() {
        return ENABLED && this.pace > 0 ? this.paces[this.pace] / 1e9 - this.slack : 0.0;
    }
}
