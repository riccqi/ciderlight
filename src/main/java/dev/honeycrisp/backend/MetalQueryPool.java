package dev.honeycrisp.backend;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.Arrays;
import java.util.OptionalLong;

/** Timestamp queries are not implemented yet; every query reports "no value". */
public class MetalQueryPool implements GpuQueryPool {
    private final int size;

    MetalQueryPool(final int size) {
        this.size = size;
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public OptionalLong getValue(final int index) {
        return OptionalLong.empty();
    }

    @Override
    public OptionalLong[] getValues(final int index, final int count) {
        OptionalLong[] values = new OptionalLong[count];
        Arrays.fill(values, OptionalLong.empty());
        return values;
    }

    @Override
    public void close() {
    }
}
