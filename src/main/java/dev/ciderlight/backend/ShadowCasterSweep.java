package dev.ciderlight.backend;

import java.util.Arrays;

/**
 * Follows the light into the sections around the camera, to find the ones it actually falls on.
 *
 * <p>{@link ShadowCasterMask} says which sections lie between a receiver and the light. Most of those the light never
 * reaches: they are under a hill's surface, or in the rock above a tunnel. A shadow map holds, along each ray, the
 * surface nearest the light, and that surface is in a section the light gets to; whatever lies behind a section the
 * light cannot pass through adds nothing to the map, so it need not be meshed or drawn. Leaving it out leaves every
 * texel a receiver can look up exactly as it was.
 *
 * <p>Whether light can pass through a section, from one face to another, is what Minecraft works out for its own cave
 * culling (the visibility set of a compiled section mesh). The light is followed from section to section by those
 * faces, whichever way it slants within its general direction, so the result holds while the sun moves on.
 *
 * <p>That speaks for light that came through the air. Where nothing is drawn (beyond the loaded chunks, and in
 * sections that can never be meshed: the outermost ring of chunks), a ray gets inside the ground without meeting a
 * surface, and from there on it is stopped by whichever surface it meets first, anywhere behind: a leak. Every
 * section such rays go on through is kept.
 *
 * <p>The sections form a grid, x by z by y, with index {@code (x * nz + z) * ny + y}. The light crosses it from one
 * corner, so one pass over the grid in that order visits every section after all those the light can reach it from.
 */
public final class ShadowCasterSweep {
    /**
     * What a grid cell holds: nothing loaded, only air, or blocks; or nothing loaded yet, in a chunk that is on its
     * way (the light is not followed through that until it is there).
     */
    public static final byte UNKNOWN = 0, AIR = 1, BLOCKS = 2, AWAITED = 3;
    /** The faces of a section by Direction ordinal, as bits in the mask of faces the light enters by. */
    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    /**
     * How a section that holds blocks is drawn, and what it does to the light. DRAWN: from its mesh, which says how the
     * light passes. SOLID: no light gets through (rock with nothing to draw; or a mesh still to be built, which is
     * taken to stop the light until it is there). OPEN: nothing to draw and nothing to stop the light. UNDRAWN: never
     * drawn, it cannot be meshed.
     */
    public static final int DRAWN = 0, SOLID = 1, OPEN = 2, UNDRAWN = 3;
    /**
     * A part of the direction to the light smaller than this may change sign shortly (the sun passing overhead): the
     * light is then followed both ways along that axis, so that the sections it is about to fall on are meshed by
     * the time it does.
     */
    private static final double NEAR_TURN = 0.05;
    private static final int[] STILL = {0}, FORTH = {1}, BACK = {-1}, BOTH = {-1, 1};
    private static final int FACES = 0x3F;
    /** Further bits of that mask: light that leaked in upstream; and light arriving from where nothing is drawn. */
    private static final int LEAKED = 1 << 6, FROM_NOTHING = 1 << 7;
    /**
     * How far to the side of where the light points now a leaked ray may end up, in blocks: the light moves on a little
     * before the casters are chosen again (ShadowCasterMask.LIGHT_LAG), over as much as 600 blocks.
     */
    private static final float LEAK_SPREAD = (float)(600.0 * ShadowCasterMask.LIGHT_LAG);

    /** What the sweep asks about the sections that hold blocks, and what it reports. */
    public interface World {
        /** Whether section `index` lies between a receiver and the light at all (ShadowCasterMask.casts). */
        boolean inReach(int index);

        /** How section `index` is drawn: DRAWN, SOLID, OPEN or UNDRAWN. */
        int mesh(int index);

        /** Whether light entering the DRAWN section `index` by one face can leave it by the other. */
        boolean passes(int index, int entryFace, int exitFace);

        /** The light falls on section `index`, which holds blocks: it is a caster. */
        void reached(int index);
    }

    private ShadowCasterSweep() {
    }

    /**
     * @param lightX the direction towards the light (with lightY, lightZ), of length 1
     * @param kind what each section holds (UNKNOWN, AIR, BLOCKS or AWAITED)
     * @param enters scratch, one per section: how light enters it
     * @param leaks scratch, four per section: which rays leaked into it
     */
    public static void run(final int nx, final int ny, final int nz, final double lightX, final double lightY, final double lightZ,
                           final byte[] kind, final byte[] enters, final float[] leaks, final World world) {
        // Two directions across the light. A ray keeps its place along them all the way, so they tell the rays apart:
        // a section is crossed by the rays within a rectangle there, and a leak is the rectangle of rays that leaked.
        double ax, ay, az;
        if (Math.abs(lightZ) < 0.9) {
            double length = Math.sqrt(lightX * lightX + lightY * lightY);
            ax = lightY / length;
            ay = -lightX / length;
            az = 0.0;
        } else {
            double length = Math.sqrt(lightY * lightY + lightZ * lightZ);
            ax = 0.0;
            ay = lightZ / length;
            az = -lightY / length;
        }
        double bx = ay * lightZ - az * lightY, by = az * lightX - ax * lightZ, bz = ax * lightY - ay * lightX;
        float[] across = {(float)(16.0 * ax), (float)(16.0 * ay), (float)(16.0 * az), (float)(16.0 * bx), (float)(16.0 * by), (float)(16.0 * bz)};
        for (int stepX : steps(lightX)) {
            for (int stepY : steps(lightY)) {
                for (int stepZ : steps(lightZ)) {
                    Arrays.fill(enters, (byte)0);
                    sweep(nx, ny, nz, stepX, stepY, stepZ, across, kind, enters, leaks, world);
                }
            }
        }
    }

    /**
     * The ways the light travels along an axis, given that part of the direction towards it: one way; not at all (the
     * sun and moon cross the sky from east to west, their light has no north-south part at all); or possibly either.
     */
    private static int[] steps(final double towardsLight) {
        if (towardsLight == 0.0) {
            return STILL;
        }
        return Math.abs(towardsLight) < NEAR_TURN ? BOTH : towardsLight > 0.0 ? BACK : FORTH;
    }

    /**
     * @param stepX the way the light travels along x (with stepY, stepZ): -1, 0 or 1
     * @param across how far one section along x, y and z is along the first direction across the light, then the second
     */
    private static void sweep(final int nx, final int ny, final int nz, final int stepX, final int stepY, final int stepZ, final float[] across,
                              final byte[] kind, final byte[] enters, final float[] leaks, final World world) {
        // Half of a section's extent along the two directions across the light.
        float halfS = 0.5F * (Math.abs(across[0]) + Math.abs(across[1]) + Math.abs(across[2]));
        float halfU = 0.5F * (Math.abs(across[3]) + Math.abs(across[4]) + Math.abs(across[5]));
        // The face the light leaves a section by along each axis (it enters the next by the opposite one).
        int exitX = stepX > 0 ? EAST : WEST, exitY = stepY > 0 ? UP : DOWN, exitZ = stepZ > 0 ? SOUTH : NORTH;
        int onward = (stepX != 0 ? 1 << exitX : 0) | (stepY != 0 ? 1 << exitY : 0) | (stepZ != 0 ? 1 << exitZ : 0);
        for (int a = 0; a < nx; a++) {
            int x = stepX >= 0 ? a : nx - 1 - a;
            for (int b = 0; b < nz; b++) {
                int z = stepZ >= 0 ? b : nz - 1 - b;
                for (int c = 0; c < ny; c++) {
                    int y = stepY >= 0 ? c : ny - 1 - c;
                    int i = (x * nz + z) * ny + y;
                    int in = enters[i] & 0xFF;
                    // At the top of the grid the light comes out of the open sky. By the other sides that face the light
                    // it comes in from outside the grid, where nothing is drawn.
                    int sky = stepY < 0 && c == 0 ? 1 << UP : 0;
                    int outside = (stepX != 0 && a == 0 ? 1 << (exitX ^ 1) : 0) | (stepZ != 0 && b == 0 ? 1 << (exitZ ^ 1) : 0)
                        | (stepY > 0 && c == 0 ? 1 << DOWN : 0);
                    if ((sky | outside) != 0 && world.inReach(i)) {
                        in |= sky | outside | (outside != 0 ? FROM_NOTHING : 0);
                    }
                    if (in == 0) {
                        continue;
                    }
                    // What goes on from here: light through the air, by which faces; leaked light; and whether what goes
                    // on has still met nothing that is drawn.
                    boolean lit = (in & FACES) != 0;
                    int out = lit && kind[i] != AWAITED ? onward : 0;
                    boolean leaked = (in & LEAKED) != 0;
                    boolean fromNothing = kind[i] == UNKNOWN;
                    if (kind[i] == BLOCKS) {
                        world.reached(i);
                        int mesh = world.mesh(i);
                        if (mesh == UNDRAWN || (in & FROM_NOTHING) != 0) {
                            // Nothing drawn here, ever, or nothing drawn before here: any ray through this section may
                            // be inside the ground now.
                            float s = x * across[0] + y * across[1] + z * across[2], u = x * across[3] + y * across[4] + z * across[5];
                            leak(leaks, i, leaked, s - halfS - LEAK_SPREAD, s + halfS + LEAK_SPREAD, u - halfU - LEAK_SPREAD, u + halfU + LEAK_SPREAD);
                            leaked = true;
                        }
                        if (mesh == DRAWN) {
                            out = 0;
                            for (int exit = 0; lit && exit < 6; exit++) {
                                if ((onward & 1 << exit) == 0) {
                                    continue;
                                }
                                for (int entry = 0; entry < 6; entry++) {
                                    if ((in & 1 << entry) != 0 && world.passes(i, entry, exit)) {
                                        out |= 1 << exit;
                                        break;
                                    }
                                }
                            }
                        } else if (mesh != OPEN) {
                            // SOLID stops the light. UNDRAWN lets all of it through, as a leak.
                            out = 0;
                        }
                    }
                    // Opposite faces differ in their lowest bit: the next section is entered by the exit face's opposite.
                    for (int axis = 0; axis < 3; axis++) {
                        int step = axis == 0 ? stepX : axis == 1 ? stepY : stepZ;
                        if (step == 0 || (axis == 0 ? a + 1 >= nx : axis == 1 ? c + 1 >= ny : b + 1 >= nz)) {
                            continue;
                        }
                        int exit = axis == 0 ? exitX : axis == 1 ? exitY : exitZ;
                        int next = i + step * (axis == 0 ? nz * ny : axis == 1 ? 1 : ny);
                        int face = (out & 1 << exit) != 0 ? 1 << (exit ^ 1) : 0;
                        if ((face == 0 && !leaked) || !world.inReach(next)) {
                            continue;
                        }
                        int bits = face | (face != 0 && fromNothing ? FROM_NOTHING : 0);
                        if (leaked) {
                            // The leaked rays go on into the next section as far as they cross it.
                            float s = x * across[0] + y * across[1] + z * across[2] + step * across[axis];
                            float u = x * across[3] + y * across[4] + z * across[5] + step * across[3 + axis];
                            float s0 = Math.max(leaks[4 * i], s - halfS), s1 = Math.min(leaks[4 * i + 1], s + halfS);
                            float u0 = Math.max(leaks[4 * i + 2], u - halfU), u1 = Math.min(leaks[4 * i + 3], u + halfU);
                            if (s0 <= s1 && u0 <= u1) {
                                leak(leaks, next, (enters[next] & LEAKED) != 0, s0, s1, u0, u1);
                                bits |= LEAKED;
                            }
                        }
                        enters[next] |= (byte)bits;
                    }
                }
            }
        }
    }

    /** Adds a rectangle of rays to those that leaked into section `index` (the first, unless it `has` some already). */
    private static void leak(final float[] leaks, final int index, final boolean has, final float s0, final float s1, final float u0, final float u1) {
        leaks[4 * index] = has ? Math.min(leaks[4 * index], s0) : s0;
        leaks[4 * index + 1] = has ? Math.max(leaks[4 * index + 1], s1) : s1;
        leaks[4 * index + 2] = has ? Math.min(leaks[4 * index + 2], u0) : u0;
        leaks[4 * index + 3] = has ? Math.max(leaks[4 * index + 3], u1) : u1;
    }
}
