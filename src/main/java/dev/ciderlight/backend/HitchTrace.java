package dev.ciderlight.backend;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Says where the time went in the first seconds after a world is entered, when the game tends to stutter: a summary
 * line per second and a line for every slow frame, written to the game log under "Ciderlight trace". A frame runs from
 * one present to the next; the columns are the things that can hold the render thread up (shader and pipeline
 * compiles, waiting for a drawable, waiting for the GPU, client ticks, garbage collection), next to what the GPU and
 * the display did in that time. Costs a few timer reads per frame outside that window.
 * <p>
 * Off unless -Dciderlight.debug=true (or -Dciderlight.hitchTrace=true); -Dciderlight.hitchTraceSeconds=N sets the window (30).
 */
public final class HitchTrace {
    public static final boolean ENABLED = MetalDebug.ENABLED || Boolean.getBoolean("ciderlight.hitchTrace");
    private static final Logger LOGGER = LoggerFactory.getLogger("Ciderlight trace");
    private static final long WINDOW_NS = Integer.getInteger("ciderlight.hitchTraceSeconds", 30) * 1_000_000_000L;
    private static final long SLOW_FRAME_NS = 25_000_000L;
    private static final int MAX_FRAME_LINES_PER_SECOND = 60;

    public static final int LIBRARY = 0;
    public static final int PIPELINE = 1;
    public static final int TEXTURE = 2;
    public static final int DRAWABLE_ACQUIRE = 3;
    public static final int DRAWABLE_PREFETCH = 4;
    public static final int GPU_WAIT = 5;
    public static final int FENCE_WAIT = 6;
    public static final int SHADERS = 7;
    public static final int TICK = 8;
    public static final int DESTROY = 9;
    public static final int SECTIONS = 10;
    private static final int KINDS = 11;
    private static final String[] NAMES = {
        "shader compile", "pipeline compile", "texture create", "drawable acquire", "drawable prefetch", "gpu wait (submit)", "gpu wait (fence)",
        "shader passes", "client ticks", "destroys", "section selection"
    };

    private static Thread renderThread;
    private static final long[] frameNs = new long[KINDS];
    private static final int[] frameCount = new int[KINDS];
    private static final long[] secondNs = new long[KINDS];
    private static final int[] secondCount = new int[KINDS];
    private static long frameUploadBytes;
    private static long frameBufferBytes;
    private static int frameBuffers;
    private static long secondUploadBytes;
    private static long secondBufferBytes;
    private static int secondBuffers;
    private static long secondShadowOffered;
    private static long secondShadowDrawn;
    private static int listSections;
    private static int listCasterOnly;
    private static int listNotMeshed;
    private static int listAirInView;
    private static long secondMainOffered;
    private static long secondMainDrawn;
    private static final StringBuilder frameEvents = new StringBuilder();
    private static final StringBuilder frameMarks = new StringBuilder();
    private static final StringBuilder out = new StringBuilder();
    private static boolean framePresented = true;

    private static boolean active;
    private static boolean inWorld;
    private static boolean visible;
    private static long origin;
    private static long windowEnd;
    private static long frameStart;
    private static long tickStart;
    private static long secondStart;
    private static int secondFrames;
    private static int secondNotPresented;
    private static int secondFrameLines;
    private static long secondMaxFrame;
    private static long secondCpu;
    private static long lastCpu;
    private static long lastGc;
    private static long lastProcessCpu;
    private static final long[] gpu = new long[17];
    private static final long[] secondGpu = new long[17];
    /** Input events read this frame and second, and the most time between one happening and the game reading it. */
    private static long frameInputAge;
    private static long secondInputAge;
    private static int secondInputs;
    /** SDL timestamp of the oldest input not yet in a frame that was presented, 0 for none. */
    private static long pendingInput;
    private static final double[] inputToScreen = new double[3];
    /** Integrated server ticks this second (written on the server thread). */
    private static long secondServerMax;
    private static int secondServerTicks;
    private static int secondServerSlow;
    private static final long INPUT_LATE_NS = 50_000_000L;
    private static final long SERVER_SLOW_NS = 50_000_000L;

    private HitchTrace() {
    }

    /** GPU time per pass is being collected (MetalNative.profileLabel names the passes). */
    static boolean profiling() {
        return active;
    }

    private static boolean onRenderThread() {
        return renderThread == null || Thread.currentThread() == renderThread;
    }

    public static void add(final int kind, final long ns) {
        if (onRenderThread()) {
            frameNs[kind] += ns;
            frameCount[kind]++;
        }
    }

    /** A shader library or pipeline state was compiled. */
    static void compiled(final int kind, final long ns, final String what) {
        add(kind, ns);
        if (active) {
            note(String.format(Locale.ROOT, "%s '%s' %.1fms%s", kind == LIBRARY ? "compiled shader" : "compiled pipeline", what, ns / 1e6,
                onRenderThread() ? "" : " on " + Thread.currentThread().getName()));
        }
    }

    /** The render thread waited for a library or pipeline state still being built in the background (Prebuild). */
    static void waitedForBuild(final long ns, final String what) {
        add(PIPELINE, ns);
        if (active) {
            note(String.format(Locale.ROOT, "waited for '%s' %.1fms", what, ns / 1e6));
        }
    }

    static void texture(final long ns, final int width, final int height) {
        add(TEXTURE, ns);
        if (active && (long)width * height >= 512L * 512L) {
            note(String.format(Locale.ROOT, "texture %dx%d %.1fms", width, height, ns / 1e6));
        }
    }

    static void buffer(final long size) {
        if (onRenderThread()) {
            frameBuffers++;
            frameBufferBytes += size;
        }
    }

    /** Chunk section draws offered to the shadow passes and those left after culling. */
    static void shadowSections(final int offered, final int drawn) {
        secondShadowOffered += offered;
        secondShadowDrawn += drawn;
    }

    /** The chunk sections Minecraft was last told to draw, and how many of them are there only to cast shadows. */
    public static void sectionList(final int sections, final int casterOnly, final int notMeshed, final int airInView) {
        listSections = sections;
        listCasterOnly = casterOnly;
        listNotMeshed = notMeshed;
        listAirInView = airInView;
    }

    /** Chunk section draws Minecraft issued in the main pass and those left without the ones that only cast shadows. */
    static void mainSections(final int offered, final int drawn) {
        secondMainOffered += offered;
        secondMainDrawn += drawn;
    }

    /**
     * A key, mouse button, wheel or mouse movement event is being read; timestamp is when it happened (SDL_GetTicksNS
     * clock). How long it waited to be read, and later how long until a frame with it reached the screen, are what the
     * player feels as input lag even when every frame is quick.
     */
    public static void input(final long timestamp) {
        if (!active || timestamp == 0L) {
            return;
        }
        long age = org.lwjgl.sdl.SDLTimer.SDL_GetTicksNS() - timestamp;
        frameInputAge = Math.max(frameInputAge, age);
        secondInputAge = Math.max(secondInputAge, age);
        secondInputs++;
        if (pendingInput == 0L || timestamp < pendingInput) {
            pendingInput = timestamp;
        }
    }

    /** For a frame about to be presented: when its oldest input happened, on MetalNative.mediaTime's clock, or 0. */
    static double takeInput() {
        if (pendingInput == 0L) {
            return 0.0;
        }
        long age = org.lwjgl.sdl.SDLTimer.SDL_GetTicksNS() - pendingInput;
        pendingInput = 0L;
        return MetalNative.mediaTime() - age / 1e9;
    }

    /** One tick of the integrated server took this long (server thread). */
    public static void serverTick(final long ns) {
        if (!active) {
            return;
        }
        synchronized (HitchTrace.class) {
            secondServerMax = Math.max(secondServerMax, ns);
            secondServerTicks++;
            if (ns >= SERVER_SLOW_NS) {
                secondServerSlow++;
            }
        }
        if (ns >= 2 * SERVER_SLOW_NS) {
            note(String.format(Locale.ROOT, "server tick %.0fms", ns / 1e6));
        }
    }

    static void upload(final long bytes) {
        if (onRenderThread()) {
            frameUploadBytes += bytes;
        }
    }

    /** Something worth a line of its own: logged at once outside the trace window, with the frame inside it. */
    static void event(final String what) {
        if (active) {
            note(what);
        } else {
            LOGGER.info(what);
        }
    }

    private static synchronized void note(final String what) {
        frameEvents.append(frameEvents.isEmpty() ? "" : "; ").append(what);
    }

    /** Where in the frame a step happened, as milliseconds since the frame began. */
    static void mark(final String name) {
        if (active && onRenderThread() && frameMarks.length() < 200) {
            frameMarks.append(' ').append(name).append('@').append(String.format(Locale.ROOT, "%.1f", (System.nanoTime() - frameStart) / 1e6));
        }
    }

    static void acquired(final boolean gotDrawable) {
        framePresented = gotDrawable;
        mark("acquire");
    }

    public static void tickStart(final boolean nowInWorld, final boolean noScreen) {
        long now = System.nanoTime();
        tickStart = now;
        if (nowInWorld && !inWorld) {
            begin(now);
        }
        if (nowInWorld && noScreen && !visible) {
            visible = true;
            // The stutter is in the seconds after the loading screen goes away, so the window is counted from here.
            windowEnd = now + WINDOW_NS;
            note("WORLD VISIBLE (loading screen closed)");
        }
        if (!nowInWorld) {
            visible = false;
        }
        inWorld = nowInWorld;
    }

    public static void tickEnd() {
        add(TICK, System.nanoTime() - tickStart);
    }

    private static void begin(final long now) {
        active = true;
        visible = false;
        origin = now;
        windowEnd = now + WINDOW_NS;
        secondStart = now;
        secondFrames = 0;
        secondNotPresented = 0;
        secondFrameLines = 0;
        secondMaxFrame = 0L;
        secondCpu = 0L;
        java.util.Arrays.fill(secondNs, 0L);
        java.util.Arrays.fill(secondCount, 0);
        java.util.Arrays.fill(secondGpu, 0L);
        secondUploadBytes = 0L;
        secondBufferBytes = 0L;
        secondBuffers = 0;
        lastCpu = threadCpu();
        lastGc = gcTime();
        lastProcessCpu = processCpu();
        MetalNative.traceStats(gpu); // discard what piled up before the window
        MetalNative.traceInputTake(inputToScreen);
        pendingInput = 0L;
        frameInputAge = 0L;
        secondInputAge = 0L;
        secondInputs = 0;
        MetalNative.profileTrace(true);
        MetalNative.profileReport();
        MetalNative.profilePeaks();
        Runtime runtime = Runtime.getRuntime();
        out.setLength(0);
        out.append(String.format(Locale.ROOT, "WORLD JOINED, tracing. %d cores, heap %d of %d MB\n", runtime.availableProcessors(),
            (runtime.totalMemory() - runtime.freeMemory()) >> 20, runtime.maxMemory() >> 20));
    }

    private static long threadCpu() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        return threads.isCurrentThreadCpuTimeSupported() ? threads.getCurrentThreadCpuTime() : 0L;
    }

    private static long gcTime() {
        long total = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(gc.getCollectionTime(), 0L);
        }
        return total;
    }

    private static long processCpu() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os ? os.getProcessCpuTime() : 0L;
    }

    private static double ms(final long ns) {
        return ns / 1e6;
    }

    /** Called once per frame, after the frame was presented and the wait for the next drawable is over. */
    static void endFrame() {
        long now = System.nanoTime();
        renderThread = Thread.currentThread();
        if (active) {
            record(now);
        }
        java.util.Arrays.fill(frameNs, 0L);
        java.util.Arrays.fill(frameCount, 0);
        frameUploadBytes = 0L;
        frameBufferBytes = 0L;
        frameBuffers = 0;
        frameMarks.setLength(0);
        synchronized (HitchTrace.class) {
            frameEvents.setLength(0);
        }
        framePresented = true;
        frameStart = now;
    }

    private static void record(final long now) {
        long frame = now - frameStart;
        long cpuNow = threadCpu();
        long cpu = cpuNow - lastCpu;
        lastCpu = cpuNow;
        long gcNow = gcTime();
        long gc = gcNow - lastGc;
        lastGc = gcNow;
        MetalNative.traceStats(gpu);
        secondGpu[0] += gpu[0];
        secondGpu[1] += gpu[1];
        secondGpu[4] += gpu[4];
        secondGpu[5] += gpu[5];
        secondGpu[8] += gpu[8];
        for (int i = 9; i < 15; i++) {
            secondGpu[i] += gpu[i];
        }
        secondGpu[15] = gpu[15];
        secondGpu[16] = gpu[16];
        for (int i : new int[]{2, 3, 6, 7}) {
            secondGpu[i] = Math.max(secondGpu[i], gpu[i]);
        }
        secondFrames++;
        secondMaxFrame = Math.max(secondMaxFrame, frame);
        secondCpu += cpu;
        if (!framePresented) {
            secondNotPresented++;
        }
        for (int i = 0; i < KINDS; i++) {
            secondNs[i] += frameNs[i];
            secondCount[i] += frameCount[i];
        }
        secondUploadBytes += frameUploadBytes;
        secondBufferBytes += frameBufferBytes;
        secondBuffers += frameBuffers;

        if (frameInputAge >= INPUT_LATE_NS) {
            note(String.format(Locale.ROOT, "input read %.0fms after it happened", ms(frameInputAge)));
        }
        frameInputAge = 0L;
        String events;
        synchronized (HitchTrace.class) {
            events = frameEvents.toString();
        }
        if ((frame >= SLOW_FRAME_NS || !events.isEmpty()) && secondFrameLines++ < MAX_FRAME_LINES_PER_SECOND) {
            out.append(String.format(Locale.ROOT, "  +%.3fs frame %.1fms (render thread on cpu %.1f)%s:", (now - origin) / 1e9, ms(frame), ms(cpu),
                framePresented ? "" : " NOT PRESENTED"));
            for (int i = 0; i < KINDS; i++) {
                if (frameNs[i] >= 500_000L) {
                    out.append(String.format(Locale.ROOT, " %s %.1f%s |", NAMES[i], ms(frameNs[i]), frameCount[i] > 1 ? " (x" + frameCount[i] + ")" : ""));
                }
            }
            if (gc > 0L) {
                out.append(" gc ").append(gc).append("ms |");
            }
            if (frameUploadBytes + frameBufferBytes >= 1L << 20) {
                out.append(String.format(Locale.ROOT, " uploads %.1fMB, %d new buffers %.1fMB |", frameUploadBytes / 1048576.0, frameBuffers, frameBufferBytes / 1048576.0));
            }
            out.append(String.format(Locale.ROOT, " gpu max %.1f, commit-to-done max %.1f |", ms(gpu[2]), ms(gpu[3])));
            out.append(" at").append(frameMarks);
            if (!events.isEmpty()) {
                out.append(" | ").append(events);
            }
            out.append('\n');
        }

        if (now - secondStart >= 1_000_000_000L || now >= windowEnd) {
            summarize(now, gcNow);
        }
    }

    private static void summarize(final long now, final long gcNow) {
        double wall = ms(now - secondStart);
        long processCpu = processCpu();
        Runtime runtime = Runtime.getRuntime();
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.ROOT,
            "+%.1fs: %d frames in %.0fms (avg %.1f, max %.1f ms), %d reached the screen, %d not presented, %d dropped, longest gap on screen %.1fms |"
                + " render thread on cpu %.0f%%, process using %.1f cores |",
            (now - origin) / 1e9, secondFrames, wall, wall / Math.max(secondFrames, 1), ms(secondMaxFrame), secondGpu[4], secondNotPresented, secondGpu[5],
            ms(secondGpu[6]), 100.0 * ms(secondCpu) / wall, ms(processCpu - lastProcessCpu) / wall));
        lastProcessCpu = processCpu;
        for (int i = 0; i < KINDS; i++) {
            if (secondCount[i] > 0) {
                s.append(String.format(Locale.ROOT, " %s %.0fms (x%d) |", NAMES[i], ms(secondNs[i]), secondCount[i]));
            }
        }
        s.append(String.format(Locale.ROOT,
            " uploads %.1fMB, %d new buffers %.1fMB | gpu avg %.1f max %.1f ms per submit (x%d), commit-to-done max %.1fms | nextDrawable max %.1fms, gave up x%d |"
                + " heap %dMB",
            secondUploadBytes / 1048576.0, secondBuffers, secondBufferBytes / 1048576.0, ms(secondGpu[1]) / Math.max(secondGpu[0], 1L), ms(secondGpu[2]),
            secondGpu[0], ms(secondGpu[3]), ms(secondGpu[7]), secondGpu[8], (runtime.totalMemory() - runtime.freeMemory()) >> 20));
        MetalNative.traceInputTake(inputToScreen);
        s.append(String.format(Locale.ROOT, " | input: %d events, read max %.1fms after they happened, to screen max %.1fms (%d of %d frames over 60ms)",
            secondInputs, ms(secondInputAge), inputToScreen[0] * 1e3, (int)inputToScreen[1], (int)inputToScreen[2]));
        synchronized (HitchTrace.class) {
            s.append(String.format(Locale.ROOT, " | server: %d ticks, max %.1fms, %d over 50ms", secondServerTicks, ms(secondServerMax), secondServerSlow));
            secondServerTicks = 0;
            secondServerSlow = 0;
            secondServerMax = 0L;
        }
        secondInputs = 0;
        secondInputAge = 0L;
        if (secondGpu[16] > 0L) {
            s.append(" | frames on screen for");
            for (int i = 0; i < 6; i++) {
                s.append(String.format(Locale.ROOT, " %.1f%sms: %d", ms(secondGpu[15] + i * secondGpu[16]), i == 5 ? "+" : "", secondGpu[9 + i]));
            }
        }
        if (secondShadowOffered > 0L) {
            s.append(String.format(Locale.ROOT, " | shadow passes drew %d of %d section draws per frame", secondShadowDrawn / Math.max(secondFrames, 1),
                secondShadowOffered / Math.max(secondFrames, 1)));
        }
        s.append(String.format(Locale.ROOT, " | section list %d, %d of them only for shadows, %d of those not meshed yet, %d sections of open air in view",
            listSections, listCasterOnly, listNotMeshed, listAirInView));
        if (secondMainOffered > 0L) {
            s.append(String.format(Locale.ROOT, " | main pass drew %d of %d", secondMainDrawn / Math.max(secondFrames, 1),
                secondMainOffered / Math.max(secondFrames, 1)));
        }
        secondMainOffered = 0L;
        secondMainDrawn = 0L;
        secondShadowOffered = 0L;
        secondShadowDrawn = 0L;
        String passes = MetalNative.profileReport();
        if (!passes.isEmpty()) {
            s.append("\n  gpu ms per submit by pass: ").append(passes);
        }
        String peaks = MetalNative.profilePeaks();
        int split = peaks.indexOf('\n');
        if (split >= 0) {
            s.append("\n  peak gpu ms in one submit by pass: ").append(peaks, 0, split);
            s.append("\n  slowest submit: ").append(peaks, split + 1, peaks.length());
        }
        out.insert(0, s.append('\n'));
        boolean finished = now >= windowEnd;
        if (finished) {
            out.append("trace window over\n");
            active = false;
            MetalNative.profileTrace(false);
        }
        LOGGER.info("\n{}", out);
        out.setLength(0);
        secondStart = now;
        secondFrames = 0;
        secondNotPresented = 0;
        secondFrameLines = 0;
        secondMaxFrame = 0L;
        secondCpu = 0L;
        java.util.Arrays.fill(secondNs, 0L);
        java.util.Arrays.fill(secondCount, 0);
        java.util.Arrays.fill(secondGpu, 0L);
        secondUploadBytes = 0L;
        secondBufferBytes = 0L;
        secondBuffers = 0;
    }
}
