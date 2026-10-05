package dev.ciderlight.backend;

import java.util.Arrays;

/** Resource bindings for one render encoder. Zero kinds skip unchanged slots. */
final class MetalBindingCache {
    final int[] kinds;
    final int[] stages;
    final long[] objects;
    final long[] samplers;
    final long[] offsets;
    private final int[] boundKinds;
    private int valid;
    private boolean changed;

    MetalBindingCache(final int count) {
        this.kinds = new int[count];
        this.stages = new int[count];
        this.objects = new long[count];
        this.samplers = new long[count];
        this.offsets = new long[count];
        this.boundKinds = new int[count];
    }

    void invalidate() {
        this.valid = 0;
    }

    void begin() {
        Arrays.fill(this.kinds, 0);
        this.changed = false;
    }

    void set(final int index, final int kind, final int stage, final long object, final long sampler, final long offset) {
        int bit = 1 << index;
        if ((this.valid & bit) != 0 && this.boundKinds[index] == kind && this.stages[index] == stage
            && this.objects[index] == object && this.samplers[index] == sampler && this.offsets[index] == offset) {
            return;
        }
        this.kinds[index] = kind;
        this.boundKinds[index] = kind;
        this.stages[index] = stage;
        this.objects[index] = object;
        this.samplers[index] = sampler;
        this.offsets[index] = offset;
        this.valid |= bit;
        this.changed = true;
    }

    boolean changed() {
        return this.changed;
    }
}
