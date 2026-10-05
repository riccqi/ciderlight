package dev.honeycrisp.backend;

import java.util.Arrays;
import java.util.Random;

/** Standalone regression checks; no GPU or test framework required. */
public final class MetalBindingCacheChecks {
    public static void main(String[] args) {
        MetalBindingCache cache = new MetalBindingCache(15);
        cache.begin();
        cache.set(0, 1, 3, 100, 0, 256);
        cache.set(1, 2, 2, 200, 300, 0);
        check(cache.changed(), "initial bindings must be submitted");
        cache.begin();
        cache.set(0, 1, 3, 100, 0, 256);
        cache.set(1, 2, 2, 200, 300, 0);
        check(!cache.changed(), "identical bindings must be skipped");
        check(cache.kinds[0] == 0 && cache.kinds[1] == 0, "a clean slot must not be resubmitted");
        cache.begin();
        cache.set(0, 1, 3, 100, 0, 512);
        check(cache.kinds[0] == 1 && cache.kinds[1] == 0, "changing a buffer offset must only bind that slot");
        check(cache.objects[1] == 200, "skipping a slot must preserve its captured resource handle");
        cache.begin();
        cache.set(1, 2, 2, 200, 301, 0);
        check(cache.kinds[1] == 2, "changing only the sampler must be submitted");
        cache.begin();
        cache.set(1, 2, 3, 200, 301, 0);
        check(cache.kinds[1] == 2, "adding a shader stage must bind the resource in that stage");
        cache.invalidate();
        cache.begin();
        cache.set(0, 1, 3, 100, 0, 512);
        cache.set(1, 2, 3, 200, 301, 0);
        check(cache.kinds[0] == 1 && cache.kinds[1] == 2, "encoder replacement or external state changes must force rebinding");
        compareAgainstUncachedEncoder();
        System.out.println("Metal binding cache checks passed (10,000 randomized batches).");
    }

    private static void compareAgainstUncachedEncoder() {
        Random random = new Random(0x4d4554414cL);
        MetalBindingCache cache = new MetalBindingCache(15);
        // Two shader stages, each with independent buffer, offset, texture and sampler namespaces.
        long[][] reference = new long[8][15];
        long[][] actual = new long[8][15];
        for (int batch = 0; batch < 10_000; batch++) {
            if (batch % 37 == 0) {
                cache.invalidate();
                for (long[] slots : reference) Arrays.fill(slots, 0);
                for (long[] slots : actual) Arrays.fill(slots, 0);
            }
            cache.begin();
            for (int index = 0; index < 15; index++) {
                if (random.nextBoolean()) continue;
                int kind = 1 + random.nextInt(3);
                int stages = 1 + random.nextInt(3);
                long object = 1 + random.nextInt(3);
                long sampler = kind == 2 ? 1 + random.nextInt(3) : 0;
                long offset = kind == 1 ? 256L * random.nextInt(3) : 0;
                bind(reference, index, kind, stages, object, sampler, offset);
                cache.set(index, kind, stages, object, sampler, offset);
            }
            for (int index = 0; index < 15; index++) {
                bind(actual, index, cache.kinds[index], cache.stages[index], cache.objects[index], cache.samplers[index], cache.offsets[index]);
            }
            check(Arrays.deepEquals(reference, actual), "cached encoder state differs from uncached state in batch " + batch);
        }
    }

    private static void bind(long[][] state, int index, int kind, int stages, long object, long sampler, long offset) {
        for (int stage = 0; stage < 2; stage++) {
            if ((stages & (1 << stage)) == 0) continue;
            int base = stage * 4;
            if (kind == 1) {
                state[base][index] = object;
                state[base + 1][index] = offset;
            } else if (kind == 2 || kind == 3) {
                state[base + 2][index] = object;
                if (kind == 2) state[base + 3][index] = sampler;
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
