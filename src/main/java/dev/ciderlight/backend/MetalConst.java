package dev.ciderlight.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

/**
 * Stable ids shared with ciderlight.m. Formats, blend factors, blend ops and compare ops use the
 * renderpearl enum ordinals, which the native tables mirror; {@link #verify()} guards against a
 * future Minecraft version reordering them.
 */
public final class MetalConst {
    public static final int PRIM_POINTS = 0;
    public static final int PRIM_LINES = 1;
    public static final int PRIM_LINE_STRIP = 2;
    public static final int PRIM_TRIANGLES = 3;
    public static final int PRIM_TRIANGLE_STRIP = 4;

    public static final int STAGE_VERTEX = 1;
    public static final int STAGE_FRAGMENT = 2;

    private MetalConst() {
    }

    public static int format(final GpuFormat format) {
        return format.ordinal();
    }

    public static boolean isTextureFormatSupported(final GpuFormat format) {
        return format.componentCount() != 3 || format.componentType() == GpuFormat.ComponentType.OPAQUE_32;
    }

    public static int primitive(final PrimitiveTopology topology) {
        return switch (topology) {
            case POINTS -> PRIM_POINTS;
            case DEBUG_LINES -> PRIM_LINES;
            case DEBUG_LINE_STRIP -> PRIM_LINE_STRIP;
            case TRIANGLE_STRIP -> PRIM_TRIANGLE_STRIP;
            // LINES and QUADS are expanded into indexed triangles by the frontend; fans are
            // converted to triangle lists by MetalRenderPass.
            case LINES, TRIANGLES, TRIANGLE_FAN, QUADS -> PRIM_TRIANGLES;
        };
    }

    public static void verify() {
        check(GpuFormat.RGBA8_UNORM.ordinal() == 6 && GpuFormat.R16_FLOAT.ordinal() == 40 && GpuFormat.D32_FLOAT.ordinal() == 51
            && GpuFormat.S8_UINT.ordinal() == 55 && GpuFormat.values().length == 56, "GpuFormat");
        check(com.mojang.renderpearl.api.pipeline.BlendFactor.ZERO.ordinal() == 14
            && com.mojang.renderpearl.api.pipeline.BlendFactor.ONE_MINUS_SRC_ALPHA.ordinal() == 9, "BlendFactor");
        check(com.mojang.renderpearl.api.pipeline.BlendOp.MAX.ordinal() == 4, "BlendOp");
        check(com.mojang.renderpearl.api.pipeline.CompareOp.GREATER_THAN_OR_EQUAL.ordinal() == 5
            && com.mojang.renderpearl.api.pipeline.CompareOp.NEVER_PASS.ordinal() == 7, "CompareOp");
    }

    private static void check(final boolean ok, final String what) {
        if (!ok) {
            throw new IllegalStateException("Ciderlight: " + what + " enum layout changed; this Minecraft version is not supported");
        }
    }
}
