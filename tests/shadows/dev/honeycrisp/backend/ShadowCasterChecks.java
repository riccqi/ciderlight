package dev.honeycrisp.backend;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Geometry regression: a section is a caster when its shadow can fall onto something in view, wherever it is itself
 * and however the camera moves, and never merely because it is near the camera.
 */
public final class ShadowCasterChecks {
    private static final Vector3f[] LIGHTS = {
        new Vector3f(0, 1, 0), new Vector3f(0.5F, 0.7F, 0.3F).normalize(), new Vector3f(0, 0.35F, 1).normalize(),
        new Vector3f(-0.94F, 0.34F, 0).normalize(), new Vector3f(0.77F, 0.64F, 0).normalize()
    };

    public static void main(String[] args) {
        int cases = 0;

        // The widened view: what the camera sees, what a small turn would show, and what is next to the camera.
        Frustum camera = camera(0, 64, 0, 0); // looks down -Z
        ShadowReceiverFrustum view = new ShadowReceiverFrustum(camera, viewProj(0));
        AABB ahead = section(0, 64, -64);
        AABB aside = section(96 * Math.sin(Math.toRadians(42)), 64, -96 * Math.cos(Math.toRadians(42)));
        AABB behind = section(0, 64, 96);
        check(camera.isVisible(ahead) && view.isVisible(ahead), "a section in view must stay listed");
        check(!camera.isVisible(aside), "fixture must be outside the camera frustum");
        check(view.isVisible(aside), "a section a small turn away must be listed ahead of time");
        check(!view.isVisible(behind), "a section far behind the camera must not be listed");
        check(view.isVisible(section(0, 64, 20)), "a section next to the camera must be listed whatever it looks at");
        int branch = view.cubeInFrustum(new BoundingBox((int)aside.minX, (int)aside.minY, (int)aside.minZ, (int)aside.maxX - 1, (int)aside.maxY - 1, (int)aside.maxZ - 1));
        check(branch == -1 || branch == -2, "octree branch containing a listed section was rejected");
        ShadowReceiverFrustum plain = new ShadowReceiverFrustum(camera, null);
        check(plain.isVisible(ahead) && !plain.isVisible(aside), "without a view the frustum must stay as it is");
        for (int angle = 0; angle < 360; angle += 15) {
            ShadowReceiverFrustum turned = new ShadowReceiverFrustum(camera(0, 64, 0, angle), viewProj(angle));
            Vector3f forward = new Matrix4f().rotateY((float)Math.toRadians(angle)).invert().transformDirection(new Vector3f(0, 0, -1));
            for (int distance = 16; distance <= 160; distance += 16) {
                check(turned.isVisible(section(forward.x * distance, 64, forward.z * distance)), "a section straight ahead is not listed");
                cases++;
            }
        }

        // The casters. Whatever lies between a receiver and the light casts; walking does not change that.
        ShadowCasterMask mask = new ShadowCasterMask();
        for (Vector3f light : LIGHTS) {
            for (double[] base : new double[][]{{0, 64, 0}, {-5000.3, 64, -5000.7}, {1234567.5, -40, -7654321.25}}) {
                for (int step = 0; step <= 64; step++) {
                    double camX = base[0] + step * 0.25 - 8, camY = base[1], camZ = base[2] + step * 0.125;
                    double[] receiver = {base[0] + 40, base[1] - 20, base[2] - 72};
                    mask.begin(camX, camY, camZ, light, 128);
                    mask.addReceiver(corner(receiver[0]), corner(receiver[1]), corner(receiver[2]));
                    mask.finish();
                    for (int lift = 16; lift <= 96; lift += 16) {
                        check(casts(mask, receiver[0] + light.x * lift, receiver[1] + light.y * lift, receiver[2] + light.z * lift),
                            "a section between a receiver and the light is not a caster");
                        cases++;
                    }
                    // The air around the camera is lit through whatever stands above it.
                    check(casts(mask, camX + light.x * 40, camY + light.y * 40, camZ + light.z * 40), "a section between the camera and the light is not a caster");
                    // Beyond the receiver, seen from the light, nothing casts onto it; nor does what is far to the side.
                    check(!casts(mask, receiver[0] - light.x * 64, receiver[1] - light.y * 64, receiver[2] - light.z * 64),
                        "a section behind the receiver, away from the light, must not be a caster");
                    Vector3f side = new Vector3f(light).cross(Math.abs(light.y) < 0.9F ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0)).normalize();
                    // To the side away from the camera: the air around the camera has casters of its own.
                    if (side.x * (camX - receiver[0]) + side.y * (camY - receiver[1]) + side.z * (camZ - receiver[2]) > 0) {
                        side.negate();
                    }
                    check(!casts(mask, receiver[0] + side.x * 96 + light.x * 32, receiver[1] + side.y * 96 + light.y * 32, receiver[2] + side.z * 96 + light.z * 32),
                        "a section whose shadow passes the receiver by must not be a caster");
                    cases += 3;
                }
            }
        }

        // The light moves on between one choice of casters and the next (by up to a tenth of a degree), and a shadow is
        // looked up a little beside the receiver itself (filtering, the offset along the normal): whatever then lies
        // between the light and such a point must have been chosen already. The rays that stray furthest are those
        // from just outside the receiver's corners.
        for (Vector3f light : LIGHTS) {
            Vector3f axis = Math.abs(light.z) < 0.9F ? new Vector3f(0, 0, 1) : new Vector3f(1, 0, 0);
            Vector3f[] later = {
                new Matrix4f().rotate((float)Math.toRadians(0.1), axis).transformDirection(new Vector3f(light)),
                new Matrix4f().rotate((float)Math.toRadians(-0.1), axis).transformDirection(new Vector3f(light))
            };
            for (int rx = -160; rx <= 160; rx += 16) {
                for (int ry = -48; ry <= 48; ry += 32) {
                    for (int rz = -64; rz <= 32; rz += 32) {
                        mask.begin(3.5, 20.25, -7.5, light, 304);
                        mask.addReceiver(rx, ry, rz);
                        mask.finish();
                        for (Vector3f moved : later) {
                            for (int corner = 0; corner < 8; corner++) {
                                double x = rx + ((corner & 1) != 0 ? 16.5 : -0.5), y = ry + ((corner & 2) != 0 ? 16.5 : -0.5), z = rz + ((corner & 4) != 0 ? 16.5 : -0.5);
                                for (double reach = 1; reach < 480; reach += 0.5) {
                                    double px = x + moved.x * reach, py = y + moved.y * reach, pz = z + moved.z * reach;
                                    if (Math.abs(px - 3.5) < 288 && Math.abs(pz + 7.5) < 288) {
                                        check(casts(mask, px, py, pz), "a section between a receiver and the light, once the light has moved on, is not a caster");
                                    }
                                }
                                cases++;
                            }
                        }
                    }
                }
            }
        }

        // One such case, found by search: with the light a tenth of a degree further on, this section's shadow just
        // touches the receiver's surroundings, though for the light as it was it lies clear of them by 0.06 blocks.
        Vector3f slanted = new Vector3f((float)Math.sin(Math.toRadians(21.04078)), (float)Math.cos(Math.toRadians(21.04078)), 0);
        mask.begin(0.5, 8, 0.5, slanted, 304);
        mask.addReceiver(0, 0, 0);
        mask.finish();
        check(mask.casts(128, 272, 0), "a section whose shadow reaches a receiver once the light has moved on is not a caster");
        cases++;

        // A closed-in view: the camera in a tunnel sees a few sections around it, and only what is above those casts.
        Vector3f noon = new Vector3f(0, 1, 0);
        mask.begin(0.5, -7, 0.5, noon, 304);
        for (int x = -32; x <= 16; x += 16) {
            mask.addReceiver(x, -16, 0);
        }
        mask.finish();
        check(casts(mask, 0, 80, 0), "terrain above the tunnel must cast");
        check(!casts(mask, 0, -40, 0), "terrain under the tunnel must not cast");
        check(!casts(mask, 120, 80, 0) && !casts(mask, 0, 80, 120) && !casts(mask, -200, 100, -200), "terrain away from the tunnel must not cast");
        int casters = 0;
        for (int x = -304; x < 304; x += 16) {
            for (int z = -304; z < 304; z += 16) {
                for (int y = -64; y < 320; y += 16) {
                    if (mask.casts(x, y, z)) {
                        casters++;
                    }
                }
            }
        }
        check(casters < 1000, "a closed-in view must leave most of the area out (" + casters + " of 34656 sections)");
        cases += 4;

        // Nothing in view but the air around the camera: only what stands over the camera casts.
        mask.begin(0, 64, 0, noon, 128);
        mask.finish();
        check(casts(mask, 0, 100, 0), "a section over the camera must cast");
        check(!casts(mask, 80, 100, 0) && !casts(mask, 0, 20, 0), "without receivers, sections away from the camera must not cast");
        sweepChecks();
        System.out.println("Shadow caster coverage checks passed (" + cases + " walking/turning cases).");
    }

    /** A small world for ShadowCasterSweep: a grid of sections, some holding blocks light cannot pass. */
    private static final class Grid implements ShadowCasterSweep.World {
        static final int NX = 5, NY = 6, NZ = 5;
        final byte[] kind = new byte[NX * NY * NZ];
        final java.util.Set<Integer> reached = new java.util.HashSet<>();
        final java.util.Set<Integer> outOfReach = new java.util.HashSet<>();
        /** Where light passes: whole sections, or index * 36 + entry face * 6 + exit face for a pair of faces. */
        final java.util.Set<Integer> clear = new java.util.HashSet<>();
        final java.util.Set<Integer> open = new java.util.HashSet<>();
        /** Sections that are not drawn from a mesh (SOLID, OPEN or UNDRAWN). */
        final java.util.Map<Integer, Integer> mesh = new java.util.HashMap<>();

        Grid() {
            java.util.Arrays.fill(this.kind, ShadowCasterSweep.AIR);
        }

        static int at(int x, int y, int z) {
            return (x * NZ + z) * NY + y;
        }

        Grid blocks(int x, int y, int z) {
            this.kind[at(x, y, z)] = ShadowCasterSweep.BLOCKS;
            return this;
        }

        Grid blocks(int x, int y, int z, int mesh) {
            this.mesh.put(at(x, y, z), mesh);
            return this.blocks(x, y, z);
        }

        Grid run(double lx, double ly, double lz) {
            this.reached.clear();
            double length = Math.sqrt(lx * lx + ly * ly + lz * lz);
            ShadowCasterSweep.run(NX, NY, NZ, lx / length, ly / length, lz / length, this.kind, new byte[NX * NY * NZ], new float[NX * NY * NZ * 4], this);
            return this;
        }

        boolean reaches(int x, int y, int z) {
            return this.reached.contains(at(x, y, z));
        }

        @Override
        public int mesh(int index) {
            return this.mesh.getOrDefault(index, ShadowCasterSweep.DRAWN);
        }

        @Override
        public boolean passes(int index, int entryFace, int exitFace) {
            check(this.mesh(index) == ShadowCasterSweep.DRAWN, "only a mesh can say which way light passes");
            return this.clear.contains(index) || this.open.contains(index * 36 + entryFace * 6 + exitFace);
        }

        @Override
        public boolean inReach(int index) {
            return !this.outOfReach.contains(index);
        }

        @Override
        public void reached(int index) {
            check(this.kind[index] == ShadowCasterSweep.BLOCKS, "only sections that hold blocks cast");
            this.reached.add(index);
        }
    }

    private static void sweepChecks() {
        // Light from straight above: it falls on the first section in its way, and on nothing under that.
        Grid grid = new Grid().blocks(2, 4, 2).blocks(2, 2, 2);
        grid.run(0, 1, 0);
        check(grid.reaches(2, 4, 2), "the section the light falls on must cast");
        check(!grid.reaches(2, 2, 2), "a section behind one that stops the light must not cast");
        grid.clear.add(Grid.at(2, 4, 2));
        check(grid.run(0, 1, 0).reaches(2, 2, 2), "a section behind one the light passes through must cast");
        // Passing depends on the faces: in by the top is not in by the east face.
        grid.clear.clear();
        grid.open.add(Grid.at(2, 4, 2) * 36 + ShadowCasterSweep.EAST * 6 + ShadowCasterSweep.DOWN);
        check(!grid.run(0, 1, 0).reaches(2, 2, 2), "light must not pass between faces that do not see each other");
        grid.open.add(Grid.at(2, 4, 2) * 36 + ShadowCasterSweep.UP * 6 + ShadowCasterSweep.DOWN);
        check(grid.run(0, 1, 0).reaches(2, 2, 2), "light must pass between faces that see each other");

        // A slanting light gets round a section that stops it, through the air beside; shut in on the light's side, it
        // does not get there.
        grid = new Grid().blocks(3, 4, 2).blocks(2, 3, 2);
        check(grid.run(1, 1, 0).reaches(3, 4, 2) && grid.reaches(2, 3, 2), "light must reach a section through the air around another");
        grid.blocks(3, 3, 2).blocks(2, 4, 2);
        grid.run(1, 1, 0);
        check(grid.reaches(3, 3, 2) && grid.reaches(2, 4, 2), "the sections on the light's side must cast");
        check(!grid.reaches(2, 3, 2), "a section shut in behind others must not cast");
        grid.clear.add(Grid.at(2, 4, 2));
        check(grid.run(1, 1, 0).reaches(2, 3, 2), "light passing a section must go on by its far faces");
        // The light never turns back: under and beyond the shut-in section nothing is lit through it.
        grid.blocks(1, 3, 2).blocks(1, 2, 2).blocks(2, 2, 2).blocks(1, 4, 2).blocks(3, 2, 2);
        grid.clear.clear();
        grid.run(1, 1, 0);
        check(grid.reaches(1, 4, 2) && grid.reaches(3, 2, 2), "the outside of a closed mass must cast");
        check(!grid.reaches(1, 3, 2) && !grid.reaches(2, 2, 2) && !grid.reaches(1, 2, 2), "sections inside a closed mass must not cast");

        // What the mask rules out is neither a caster nor on the light's way; where nothing is loaded the light passes.
        grid = new Grid().blocks(2, 3, 2);
        grid.outOfReach.add(Grid.at(2, 5, 2));
        check(grid.run(0, 1, 0).reached.isEmpty(), "light must not be followed where no receiver lies behind");
        grid.outOfReach.clear();
        grid.outOfReach.add(Grid.at(2, 3, 2));
        check(grid.run(0, 1, 0).reached.isEmpty(), "a section out of the receivers' reach must not cast");
        grid.outOfReach.clear();
        grid.kind[Grid.at(2, 4, 2)] = ShadowCasterSweep.UNKNOWN;
        check(grid.run(0, 1, 0).reaches(2, 3, 2), "light must pass where nothing is loaded");

        // Where a section cannot be meshed nothing is drawn, so nothing stops the light there: all that lies behind it
        // may be the first surface a ray meets, and is kept; what lies beside that is not. The same where the light
        // comes out of a part that is not loaded, or in by the side of the grid, straight into blocks.
        grid = new Grid().blocks(2, 4, 2, ShadowCasterSweep.UNDRAWN).blocks(2, 3, 2).blocks(2, 1, 2).blocks(3, 4, 2).blocks(3, 3, 2).blocks(3, 1, 2);
        grid.run(0, 1, 0);
        check(grid.reaches(2, 4, 2) && grid.reaches(2, 3, 2) && grid.reaches(2, 1, 2), "everything behind a section that cannot be meshed must cast");
        check(grid.reaches(3, 4, 2) && !grid.reaches(3, 3, 2) && !grid.reaches(3, 1, 2), "a leak must not spread beside its own shadow");
        grid = new Grid().blocks(2, 3, 2).blocks(2, 2, 2).blocks(2, 0, 2);
        grid.kind[Grid.at(2, 4, 2)] = ShadowCasterSweep.UNKNOWN;
        grid.kind[Grid.at(2, 5, 2)] = ShadowCasterSweep.UNKNOWN;
        check(grid.run(0, 1, 0).reaches(2, 2, 2) && grid.reaches(2, 0, 2), "everything behind blocks next to what is not loaded must cast");
        grid.kind[Grid.at(2, 4, 2)] = ShadowCasterSweep.AIR;
        check(grid.run(0, 1, 0).reaches(2, 3, 2) && !grid.reaches(2, 2, 2), "light that crossed air after what is not loaded is ordinary light");
        // Above the grid is the open sky, beside it is not: blocks at its top are lit like any others, blocks at its side
        // may hide a ray that is already inside the ground.
        grid = new Grid().blocks(2, 5, 2).blocks(2, 3, 2);
        check(grid.run(0, 1, 0).reaches(2, 5, 2) && !grid.reaches(2, 3, 2), "blocks at the top of the grid are lit from the sky");
        // (Both sections under test are shut in by others, so ordinary light does not get to them.)
        grid = new Grid().blocks(4, 4, 2).blocks(2, 3, 2).blocks(3, 2, 2).blocks(2, 2, 2).blocks(0, 4, 2).blocks(1, 3, 2).blocks(0, 3, 2);
        grid.run(1, 1, 0);
        check(grid.reaches(4, 4, 2) && grid.reaches(2, 2, 2), "everything behind blocks at the side of the grid must cast");
        check(!grid.reaches(0, 3, 2), "a slanting leak must follow the light, not spread beside it");
        grid.kind[Grid.at(4, 4, 2)] = ShadowCasterSweep.AIR;
        check(!grid.run(1, 1, 0).reaches(2, 2, 2), "without the blocks at the side of the grid there is no leak");

        // A chunk that is still on its way is waited for: the light is not followed through it, nor does it leak there.
        grid = new Grid().blocks(2, 2, 2).blocks(2, 0, 2);
        grid.kind[Grid.at(2, 3, 2)] = ShadowCasterSweep.AWAITED;
        check(grid.run(0, 1, 0).reached.isEmpty(), "light must not be followed through a chunk that is still to arrive");
        grid.kind[Grid.at(2, 3, 2)] = ShadowCasterSweep.UNKNOWN;
        check(grid.run(0, 1, 0).reaches(2, 2, 2) && grid.reaches(2, 0, 2), "a chunk that stays away lets the light leak through");

        // A section with nothing to draw has no mesh to say how the light passes: it is rock all through, which stops
        // the light (so does a section whose mesh is still to come), or it is open. Neither is a leak.
        grid = new Grid().blocks(2, 4, 2, ShadowCasterSweep.SOLID).blocks(2, 2, 2);
        check(grid.run(0, 1, 0).reaches(2, 4, 2) && !grid.reaches(2, 2, 2), "a solid section must stop the light");
        grid = new Grid().blocks(2, 4, 2, ShadowCasterSweep.OPEN).blocks(2, 3, 2).blocks(2, 1, 2);
        grid.run(0, 1, 0);
        check(grid.reaches(2, 4, 2) && grid.reaches(2, 3, 2), "light must be followed through an open section");
        check(!grid.reaches(2, 1, 2), "an open section must not count as a leak");

        // Any direction: here the light comes from -X, -Z and above.
        grid = new Grid().blocks(2, 3, 2).blocks(1, 3, 2).blocks(2, 4, 2).blocks(2, 3, 1);
        grid.run(-0.5, 0.6, -0.4);
        check(grid.reaches(1, 3, 2) && grid.reaches(2, 4, 2) && grid.reaches(2, 3, 1), "the light must be followed whichever way it comes from");
        check(!grid.reaches(2, 3, 2), "a section shut in behind others must not cast (reversed order)");

        // Around noon the light is about to lean the other way: both ways are followed then. A light with no part
        // along an axis at all never crosses that way. Here the section under the roof can only be lit from the east.
        grid = new Grid().blocks(2, 4, 2).blocks(1, 3, 2).blocks(2, 3, 2);
        check(!grid.run(0, 1, 0).reaches(2, 3, 2), "a light from straight above must not get in from the side");
        check(!grid.run(-0.5, 1, 0).reaches(2, 3, 2), "a light from the west must not get in from the east");
        check(grid.run(0.5, 1, 0).reaches(2, 3, 2), "a light from the east must get in from the east");
        check(grid.run(-0.01, 1, 0).reaches(2, 3, 2), "a light about to come from the other side must be followed both ways");
        check(grid.run(0.01, 1, 0).reaches(2, 3, 2), "a light that has just come round must still be followed");
        check(!grid.run(-0.07, 1, 0).reaches(2, 3, 2), "a light well to one side must be followed that way only");
    }

    /** Whether the section containing this point is a caster. */
    private static boolean casts(ShadowCasterMask mask, double x, double y, double z) {
        return mask.casts(corner(x), corner(y), corner(z));
    }

    private static double corner(double v) {
        return Math.floor(v / 16) * 16;
    }

    /** The section containing this point. */
    private static AABB section(double x, double y, double z) {
        return new AABB(corner(x), corner(y), corner(z), corner(x) + 16, corner(y) + 16, corner(z) + 16);
    }

    private static Matrix4f viewProj(int angle) {
        return new Matrix4f().perspective((float)Math.toRadians(70), 1.0F, 0.1F, 512.0F).mul(new Matrix4f().rotateY((float)Math.toRadians(angle)));
    }

    private static Frustum camera(double x, double y, double z, int angle) {
        Frustum result = new Frustum(new Matrix4f().rotateY((float)Math.toRadians(angle)),
            new Matrix4f().perspective((float)Math.toRadians(70), 1.0F, 0.1F, 512.0F));
        result.prepare(x, y, z);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
