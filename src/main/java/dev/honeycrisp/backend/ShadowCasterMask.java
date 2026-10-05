package dev.honeycrisp.backend;

import java.util.Arrays;
import org.joml.Vector3fc;

/**
 * Which chunk sections can cast a shadow onto something the camera sees.
 *
 * <p>Minecraft only meshes and draws the sections it believes the camera can see, and the shadow maps are rendered by
 * replaying those draws, so a section that matters only as a shadow caster has to be added to its list. Adding every
 * section around the camera is ruinous where the view is closed in: in a mine tunnel the camera sees a few dozen
 * sections, and thousands from the hills above would be meshed, uploaded and drawn every frame for shadows nobody can
 * see. So the casters are worked out from the receivers: a section casts when it lies between the light and something
 * in view.
 *
 * <p>The receivers are entered first: the sections in view, and the open air in view (fog shows light shafts). Seen
 * from the light they cover part of a grid of cells laid across the light's direction; each cell remembers how far
 * from the light, and how low, its receivers reach. A section then casts when it covers a cell holding a receiver
 * that is further from the light and reaches below the section's top. The grid is fixed in the world rather than
 * centred on the camera, so whether a section casts does not flicker as the camera moves within a cell.
 *
 * <p>This says where a caster could be. Which of those sections the light actually falls on is for
 * {@link ShadowCasterSweep} to find.
 */
public final class ShadowCasterMask {
    /**
     * Width of a grid cell, in blocks. A section's shadow is 16 to 28 blocks wide, so cells of half a section follow
     * the receivers' outline closely enough (in a tunnel, a fifth fewer casters than with 16-block cells); finer ones
     * gain little more.
     */
    private static final double CELL = 8.0;
    /** Width of a chunk section, in blocks, and half of it. */
    private static final double SECTION = 16.0;
    private static final double HALF = SECTION / 2.0;
    private static final float NONE = Float.POSITIVE_INFINITY;
    private static final float EPSILON = 0.01F;
    /**
     * Slack around a receiver, in blocks: a shadow lookup is offset along the surface normal and towards the light,
     * and filtered over a few texels, so it reads the map a little outside the receiver itself.
     */
    private static final double SLACK = 1.0;
    /**
     * How far the light may have moved on since the casters were chosen, in radians: MetalShaders has them chosen again
     * for every tenth of a degree. A ray from a caster then arrives that much to the side for every block it travels.
     */
    static final double LIGHT_LAG = 0.002;

    private double camX, camY, camZ;
    /** Where the camera is in the light's frame: s and u run across the light. */
    private double camS, camU;
    private double sx, sy, sz, ux, uy, uz, lx, ly, lz;
    /** Half of a section's extent along s, along u and towards the light. */
    private double hs, hu, hd;
    /** Cells per side, and the index (in whole cells from the world's origin) of the first one on each axis. */
    private int size;
    private long baseS, baseU;
    /** Per cell, for the receivers in it: the least depth towards the light, and the lowest height, both from the camera. */
    private float[] depth = new float[0];
    private float[] floor = new float[0];
    /** The least depth of any receiver, or infinity. */
    private float deepest;
    /** The cells that hold a receiver lie in this range (empty while maxI is below minI). */
    private int minI, maxI, minJ, maxJ;
    /** Under a light from above, nothing can shadow what is higher than itself. */
    private boolean heightTest;

    /**
     * Starts a new set of receivers.
     *
     * @param light the direction towards the light
     * @param radius half-width of the area the shadow maps cover around the camera, in blocks
     */
    public void begin(final double camX, final double camY, final double camZ, final Vector3fc light, final double radius) {
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        double length = Math.sqrt(light.x() * light.x() + light.y() * light.y() + light.z() * light.z());
        this.lx = light.x() / length;
        this.ly = light.y() / length;
        this.lz = light.z() / length;
        // The light's frame as the shadow maps have it (MetalShaders.texelSnappedAnchor): s = forward x up, u = s x forward,
        // with forward = -light and up = +Z, so the area the maps cover is a square of cells.
        double fx = -this.lx, fy = -this.ly, fz = -this.lz;
        double sLength = Math.sqrt(fy * fy + fx * fx);
        if (sLength < 1e-6) {
            // The light runs along Z: any frame across it will do.
            this.sx = 1.0;
            this.sy = 0.0;
        } else {
            this.sx = fy / sLength;
            this.sy = -fx / sLength;
        }
        this.sz = 0.0;
        this.ux = this.sy * fz - this.sz * fy;
        this.uy = this.sz * fx - this.sx * fz;
        this.uz = this.sx * fy - this.sy * fx;
        this.hs = HALF * (Math.abs(this.sx) + Math.abs(this.sy) + Math.abs(this.sz));
        this.hu = HALF * (Math.abs(this.ux) + Math.abs(this.uy) + Math.abs(this.uz));
        this.hd = HALF * (Math.abs(this.lx) + Math.abs(this.ly) + Math.abs(this.lz));
        this.heightTest = this.ly > 0.05;
        this.camS = camX * this.sx + camY * this.sy + camZ * this.sz;
        this.camU = camX * this.ux + camY * this.uy + camZ * this.uz;
        int half = (int)Math.ceil((radius + 2.0 * SECTION) / CELL);
        this.size = 2 * half + 1;
        this.baseS = (long)Math.floor(this.camS / CELL) - half;
        this.baseU = (long)Math.floor(this.camU / CELL) - half;
        int cells = this.size * this.size;
        if (this.depth.length != cells) {
            this.depth = new float[cells];
            this.floor = new float[cells];
        }
        Arrays.fill(this.depth, NONE);
        Arrays.fill(this.floor, NONE);
        this.deepest = NONE;
        this.minI = this.size;
        this.maxI = -1;
        this.minJ = this.size;
        this.maxJ = -1;
    }

    private int cellS(final double s) {
        return (int)Math.clamp((long)Math.floor(s / CELL) - this.baseS, -1L, (long)this.size);
    }

    private int cellU(final double u) {
        return (int)Math.clamp((long)Math.floor(u / CELL) - this.baseU, -1L, (long)this.size);
    }

    /** Enters receivers that span the given rectangle across the light, no deeper and no lower than given. */
    private void mark(final double s0, final double s1, final double u0, final double u1, final float depth, final float floor) {
        int i0 = Math.max(this.cellS(s0), 0), i1 = Math.min(this.cellS(s1), this.size - 1);
        int j0 = Math.max(this.cellU(u0), 0), j1 = Math.min(this.cellU(u1), this.size - 1);
        if (i0 > i1 || j0 > j1) {
            return;
        }
        this.deepest = Math.min(this.deepest, depth);
        for (int j = j0; j <= j1; j++) {
            int row = j * this.size;
            for (int i = i0; i <= i1; i++) {
                if (depth < this.depth[row + i]) {
                    this.depth[row + i] = depth;
                }
                if (floor < this.floor[row + i]) {
                    this.floor[row + i] = floor;
                }
            }
        }
        this.minI = Math.min(this.minI, i0);
        this.maxI = Math.max(this.maxI, i1);
        this.minJ = Math.min(this.minJ, j0);
        this.maxJ = Math.max(this.maxJ, j1);
    }

    /** Enters the 16-block cube with this lowest corner (a chunk section, or as much open air) as something in view. */
    public void addReceiver(final double minX, final double minY, final double minZ) {
        double x = minX + HALF, y = minY + HALF, z = minZ + HALF;
        double s = x * this.sx + y * this.sy + z * this.sz;
        double u = x * this.ux + y * this.uy + z * this.uz;
        double d = (x - this.camX) * this.lx + (y - this.camY) * this.ly + (z - this.camZ) * this.lz;
        this.mark(
            s - this.hs - SLACK, s + this.hs + SLACK, u - this.hu - SLACK, u + this.hu + SLACK, (float)(d - this.hd - SLACK), (float)(minY - this.camY - SLACK)
        );
    }

    /**
     * Completes the receivers with the camera's own surroundings: the air around it is in view whatever else is
     * (the caller enters the rest of the open air in view like any other receiver).
     */
    public void finish() {
        this.mark(this.camS - SECTION, this.camS + SECTION, this.camU - SECTION, this.camU + SECTION, (float)(-SECTION - this.hd), (float)-SECTION);
    }

    /** Whether the section (a 16-block cube) with this lowest corner can shadow anything entered as a receiver. */
    public boolean casts(final double minX, final double minY, final double minZ) {
        double x = minX + HALF, y = minY + HALF, z = minZ + HALF;
        double s = x * this.sx + y * this.sy + z * this.sz;
        double u = x * this.ux + y * this.uy + z * this.uz;
        // How near the light, and how high, the section reaches.
        float near = (float)((x - this.camX) * this.lx + (y - this.camY) * this.ly + (z - this.camZ) * this.lz + this.hd) - EPSILON;
        float top = (float)(minY + SECTION - this.camY) - EPSILON;
        // Its shadow may fall this far to the side by the time it reaches the deepest receiver (LIGHT_LAG).
        double lag = Math.max(near - this.deepest, 0.0) * LIGHT_LAG;
        int i0 = Math.max(this.cellS(s - this.hs - lag), this.minI), i1 = Math.min(this.cellS(s + this.hs + lag), this.maxI);
        int j0 = Math.max(this.cellU(u - this.hu - lag), this.minJ), j1 = Math.min(this.cellU(u + this.hu + lag), this.maxJ);
        if (i0 > i1 || j0 > j1) {
            return false;
        }
        for (int j = j0; j <= j1; j++) {
            int row = j * this.size;
            for (int i = i0; i <= i1; i++) {
                if (this.depth[row + i] < near && (!this.heightTest || this.floor[row + i] < top)) {
                    return true;
                }
            }
        }
        return false;
    }
}
