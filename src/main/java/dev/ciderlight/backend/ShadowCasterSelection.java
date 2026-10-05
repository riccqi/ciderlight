package dev.ciderlight.backend;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Adds to the sections Minecraft is about to draw the ones that cast a shadow onto something in view
 * (SectionOcclusionGraphMixin calls this whenever Minecraft picks its sections).
 *
 * <p>The receivers are what the camera's occlusion graph reaches inside the widened view: the sections Minecraft
 * listed, and the empty ones (open air, where fog shows light shafts and mobs fly). {@link ShadowCasterMask} gives
 * the sections between those and the light, and {@link ShadowCasterSweep} keeps the ones the light actually falls on.
 */
public final class ShadowCasterSelection implements ShadowCasterSweep.World {
    private static final Direction[] FACES = Direction.values();
    /**
     * How long the picture of loaded, empty and seen sections may lag while chunks stream in and meshes arrive. It is
     * rebuilt at once when the camera changes section or the occlusion graph is replaced.
     */
    private static final long REBUILD_INTERVAL_NS = 100_000_000L;
    /**
     * How long after a chunk arrives the world still counts as loading. While it does, a chunk that is expected but
     * not there yet (or there, but not meshable until its neighbours are) is waited for. Once it does not, such a
     * chunk is taken to stay away, like the ring of chunks around the view distance, which is never meshed.
     */
    private static final long LOADING_QUIET_NS = 3_000_000_000L;
    /** Refractive index of water, as in water.metal. */
    private static final double WATER_INDEX = 1.333;
    /** The blocks Minecraft's cave culling takes light (and sight) to pass. */
    private static final Predicate<BlockState> PASSES_LIGHT = state -> !state.isSolidRender();

    private final ShadowCasterMask mask = new ShadowCasterMask();
    private final BitSet included = new BitSet();
    private final BlockPos.MutableBlockPos origin = new BlockPos.MutableBlockPos();

    // The sections around the camera as ShadowCasterSweep's grid, from (minX, minY, minZ) in section coordinates.
    private int minX, minY, minZ, nx, ny, nz;
    private byte[] kind = new byte[0];
    private byte[] enters = new byte[0];
    private float[] leaks = new float[0];
    /** ShadowCasterMask.casts by grid index: 0 not asked yet, 1 yes, 2 no. */
    private byte[] reach = new byte[0];
    /**
     * Whether Minecraft will mesh the sections of a chunk, by grid column (index / ny): 0 not asked yet, MESHABLE now,
     * AWAITED (once the chunks around it have arrived), or NEVER.
     */
    private byte[] meshable = new byte[0];
    private static final byte MESHABLE = 1, AWAITED = 2, NEVER = 3;
    /** The empty sections the camera's occlusion graph reaches: the open air it can see into. */
    private long[] seenAir = new long[256];
    private int seenAirCount;
    private boolean stale = true;
    private long builtAt;
    private long chunkArrivedAt = System.nanoTime();
    /** Whether the world counted as loading at the last call. */
    private boolean loading = true;
    /** The chunk the view distance is measured from, and that distance in chunks. */
    private int centerX, centerZ, distance;
    private WeakReference<Object> builtFor = new WeakReference<>(null);

    // The call in progress.
    private @Nullable ClientLevel level;
    private @Nullable ViewArea viewArea;
    private @Nullable List<RenderSection> visible;
    private @Nullable LongOpenHashSet casterOnly;
    private int notMeshed;
    private int airInView;
    private int lookedUp = -1;
    private @Nullable RenderSection lookedUpSection;

    /** Chunks were loaded or unloaded, or a section mesh arrived: what is loaded, empty and seen has to be looked at again. */
    public void invalidate() {
        this.stale = true;
    }

    /** A chunk has just been loaded: the world is loading (LOADING_QUIET_NS). */
    public void chunkArrived() {
        this.chunkArrivedAt = System.nanoTime();
    }

    /**
     * Whether the last call still worked from an outdated picture (it is rebuilt at most every REBUILD_INTERVAL_NS,
     * and the world has stopped loading since): the selection has to be run again soon, even if nothing else changes.
     */
    public boolean stale() {
        return this.stale || (this.loading && System.nanoTime() - this.chunkArrivedAt >= LOADING_QUIET_NS);
    }

    /** How many empty sections the camera sees into lay in view at the last call (for the trace). */
    public int airInView() {
        return this.airInView;
    }

    /** Casters listed by the last call whose meshes were still to be built (not counting those that cannot be meshed). */
    public int notMeshed() {
        return this.notMeshed;
    }

    /**
     * @param graphState identifies the occlusion graph's current result; a different object means a new one
     * @param camera Minecraft's frustum, at the camera
     * @param seen the frustum Minecraft picks sections with itself
     * @param widened the frustum it was given instead
     * @param visible the sections picked, to which the casters are added
     * @return the sections in the list that are there for the shadow passes only (MetalShaders.sectionKey)
     */
    public LongOpenHashSet select(
        final SectionOcclusionGraph graph, final Object graphState, final ViewArea viewArea, final LongOpenHashSet loadedChunks,
        final LongOpenHashSet emptySections, final Frustum camera, final Frustum seen, final ShadowReceiverFrustum widened,
        final Vector3fc light, final List<RenderSection> visible
    ) {
        double camX = camera.getCamX(), camY = camera.getCamY(), camZ = camera.getCamZ();
        double radius = MetalShaders.casterRadius();
        this.mask.begin(camX, camY, camZ, light, radius);
        LongOpenHashSet casterOnly = new LongOpenHashSet();
        this.level = Minecraft.getInstance().level;
        this.viewArea = viewArea;
        this.visible = visible;
        this.casterOnly = casterOnly;
        this.notMeshed = 0;
        this.lookedUp = -1;

        SectionPos center = viewArea.getCameraSectionPos();
        int distance = viewArea.getViewDistance();
        int x0 = Math.max(center.x() - distance, SectionPos.blockToSectionCoord(camX - radius));
        int x1 = Math.min(center.x() + distance, SectionPos.blockToSectionCoord(camX + radius));
        int z0 = Math.max(center.z() - distance, SectionPos.blockToSectionCoord(camZ - radius));
        int z1 = Math.min(center.z() + distance, SectionPos.blockToSectionCoord(camZ + radius));
        int y0 = viewArea.minSectionY(), y1 = viewArea.maxSectionY();
        int nx = Math.max(x1 - x0 + 1, 0), ny = Math.max(y1 - y0 + 1, 0), nz = Math.max(z1 - z0 + 1, 0);
        long now = System.nanoTime();
        boolean moved = x0 != this.minX || y0 != this.minY || z0 != this.minZ || nx != this.nx || ny != this.ny || nz != this.nz;
        boolean loading = now - this.chunkArrivedAt < LOADING_QUIET_NS;
        if (moved || graphState != this.builtFor.get() || loading != this.loading || center.x() != this.centerX || center.z() != this.centerZ
            || distance != this.distance || (this.stale && now - this.builtAt >= REBUILD_INTERVAL_NS)) {
            this.loading = loading;
            this.centerX = center.x();
            this.centerZ = center.z();
            this.distance = distance;
            this.minX = x0;
            this.minY = y0;
            this.minZ = z0;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.rebuild(graph, viewArea, loadedChunks, emptySections);
            this.builtFor = new WeakReference<>(graphState);
            this.builtAt = now;
            this.stale = false;
        }

        // The receivers Minecraft listed. Those its own frustum would not have listed are there for the shadow passes
        // only, like the casters added below, and the main pass leaves them out again (MetalRenderPass.drawSeenSections).
        double waterSurface = MetalShaders.casterWaterSurface();
        // Under water the shader also looks up where each ray of light entered the water (composite.metal, water_visibility).
        double length = Math.sqrt(light.x() * light.x() + light.y() * light.y() + light.z() * light.z());
        double bentX = light.x() / length / WATER_INDEX, bentZ = light.z() / length / WATER_INDEX;
        double bentY = Math.sqrt(Math.max(1.0 - bentX * bentX - bentZ * bentZ, 1e-4));
        double entryX = bentX / bentY, entryZ = bentZ / bentY; // how far the entry point lies from a point, per block of depth
        double depth = Math.max(waterSurface - camY, 0.0);
        double entryMinX = camX + entryX * depth, entryMaxX = entryMinX, entryMinZ = camZ + entryZ * depth, entryMaxZ = entryMinZ;
        this.included.clear();
        for (RenderSection section : visible) {
            this.included.set(section.index);
            AABB box = section.getBoundingBox();
            if (!seen.isVisible(box)) {
                casterOnly.add(MetalShaders.sectionKey(box));
            }
            this.mask.addReceiver(box.minX, box.minY, box.minZ);
            if (box.minY < waterSurface) {
                double shallow = Math.max(waterSurface - box.maxY, 0.0), deep = waterSurface - box.minY;
                entryMinX = Math.min(entryMinX, box.minX + Math.min(entryX * shallow, entryX * deep));
                entryMaxX = Math.max(entryMaxX, box.maxX + Math.max(entryX * shallow, entryX * deep));
                entryMinZ = Math.min(entryMinZ, box.minZ + Math.min(entryZ * shallow, entryZ * deep));
                entryMaxZ = Math.max(entryMaxZ, box.maxZ + Math.max(entryZ * shallow, entryZ * deep));
            }
        }
        // The open air in view.
        this.airInView = 0;
        for (int k = 0; k < this.seenAirCount; k++) {
            long node = this.seenAir[k];
            double minX = SectionPos.x(node) << 4, minY = SectionPos.y(node) << 4, minZ = SectionPos.z(node) << 4;
            if (widened.inWidenedView(minX, minY, minZ, minX + 16.0, minY + 16.0, minZ + 16.0)) {
                this.mask.addReceiver(minX, minY, minZ);
                this.airInView++;
            }
        }
        // Under water: the patch of surface that the light for everything in view came through.
        if (!Double.isNaN(waterSurface)) {
            int toX = Math.min(SectionPos.blockToSectionCoord(entryMaxX), x1), toZ = Math.min(SectionPos.blockToSectionCoord(entryMaxZ), z1);
            for (int x = Math.max(SectionPos.blockToSectionCoord(entryMinX), x0); x <= toX; x++) {
                for (int z = Math.max(SectionPos.blockToSectionCoord(entryMinZ), z0); z <= toZ; z++) {
                    this.mask.addReceiver(x << 4, waterSurface - 8.0, z << 4);
                }
            }
        }
        this.mask.finish();

        // The casters. Sections not meshed yet are listed too, so that the normal extraction/compilation path builds
        // them; the nearby list is left alone so off-screen sections don't gain sorting priority.
        Arrays.fill(this.reach, (byte)0);
        Arrays.fill(this.meshable, (byte)0);
        if (MetalShaders.CASTER_MASK && MetalShaders.CASTER_SWEEP) {
            ShadowCasterSweep.run(nx, ny, nz, light.x() / length, light.y() / length, light.z() / length, this.kind, this.enters, this.leaks, this);
        } else {
            // For comparison: every section between a receiver and the light, or every loaded section around the camera.
            for (int index = 0; index < this.kind.length; index++) {
                if (this.kind[index] == ShadowCasterSweep.BLOCKS && (!MetalShaders.CASTER_MASK || this.inReach(index))) {
                    this.reached(index);
                }
            }
        }
        this.level = null;
        this.viewArea = null;
        this.visible = null;
        this.casterOnly = null;
        this.lookedUpSection = null;
        return casterOnly;
    }

    /** Looks at which sections around the camera are loaded, which are empty, and which of those the camera sees into. */
    private void rebuild(final SectionOcclusionGraph graph, final ViewArea viewArea, final LongOpenHashSet loadedChunks, final LongOpenHashSet emptySections) {
        int cells = this.nx * this.ny * this.nz;
        if (this.kind.length != cells) {
            this.kind = new byte[cells];
            this.enters = new byte[cells];
            this.leaks = new float[cells * 4];
            this.reach = new byte[cells];
            this.meshable = new byte[this.nx * this.nz];
        }
        Arrays.fill(this.kind, ShadowCasterSweep.UNKNOWN);
        for (int x = 0; x < this.nx; x++) {
            for (int z = 0; z < this.nz; z++) {
                // ViewArea is a reusable ring of slots, not a guarantee that a chunk is loaded.
                int column = (x * this.nz + z) * this.ny;
                if (loadedChunks.contains(ChunkPos.pack(this.minX + x, this.minZ + z))) {
                    Arrays.fill(this.kind, column, column + this.ny, ShadowCasterSweep.BLOCKS);
                } else if (this.expected(this.minX + x, this.minZ + z)) {
                    Arrays.fill(this.kind, column, column + this.ny, ShadowCasterSweep.AWAITED);
                }
            }
        }
        this.seenAirCount = 0;
        for (LongIterator empty = emptySections.iterator(); empty.hasNext();) {
            long node = empty.nextLong();
            int x = SectionPos.x(node), y = SectionPos.y(node), z = SectionPos.z(node);
            int index = this.index(x, y, z);
            if (index < 0 || this.kind[index] != ShadowCasterSweep.BLOCKS) {
                continue;
            }
            this.kind[index] = ShadowCasterSweep.AIR;
            RenderSection section = viewArea.getRenderSectionAt(this.origin.set(x << 4, y << 4, z << 4));
            if (section != null && graph.getNode(section) != null) {
                if (this.seenAirCount == this.seenAir.length) {
                    this.seenAir = Arrays.copyOf(this.seenAir, this.seenAir.length * 2);
                }
                this.seenAir[this.seenAirCount++] = node;
            }
        }
    }

    /** Grid index of a section, or -1 when it lies outside the grid. */
    private int index(final int x, final int y, final int z) {
        int gx = x - this.minX, gy = y - this.minY, gz = z - this.minZ;
        return gx < 0 || gx >= this.nx || gy < 0 || gy >= this.ny || gz < 0 || gz >= this.nz ? -1 : (gx * this.nz + gz) * this.ny + gy;
    }

    private @Nullable RenderSection section(final int index) {
        if (index != this.lookedUp) {
            int y = index % this.ny, z = index / this.ny % this.nz, x = index / this.ny / this.nz;
            this.lookedUp = index;
            this.lookedUpSection = this.viewArea.getRenderSectionAt(this.origin.set(this.minX + x << 4, this.minY + y << 4, this.minZ + z << 4));
        }
        return this.lookedUpSection;
    }

    @Override
    public int mesh(final int index) {
        RenderSection section = this.section(index);
        SectionMesh mesh = section == null ? CompiledSectionMesh.UNCOMPILED : section.getSectionMesh();
        if (mesh == CompiledSectionMesh.UNCOMPILED) {
            // Listed as a caster, it is meshed next, if it can be. Until its mesh says how the light passes, the light
            // is not followed beyond it: what it may fall on there is listed once the mesh is in.
            return section != null && this.meshing(index / this.ny) != NEVER ? ShadowCasterSweep.SOLID : ShadowCasterSweep.UNDRAWN;
        }
        if (mesh != CompiledSectionMesh.EMPTY) {
            return ShadowCasterSweep.DRAWN;
        }
        // Meshed, and nothing in it to draw: rock with rock all around, or water under water. Minecraft takes every
        // such section to be open (SectionMesh.facesCanSeeEachother), so its blocks have to say which it is.
        LevelChunk chunk = this.level == null ? null
            : this.level.getChunkSource().getChunk(this.minX + index / this.ny / this.nz, this.minZ + index / this.ny % this.nz, ChunkStatus.FULL, false);
        int slot = chunk == null ? -1 : chunk.getSectionIndexFromSectionY(this.minY + index % this.ny);
        return slot < 0 || slot >= chunk.getSections().length || chunk.getSection(slot).maybeHas(PASSES_LIGHT) ? ShadowCasterSweep.OPEN : ShadowCasterSweep.SOLID;
    }

    /** Whether the server is about to send this chunk, if it has not yet: it lies within the view distance and the world is still loading. */
    private boolean expected(final int x, final int z) {
        return this.loading && ChunkTrackingView.isInViewDistance(this.centerX, this.centerZ, this.distance, x, z);
    }

    /**
     * Whether Minecraft meshes the sections of the chunk in this grid column once they are listed. It does when the
     * eight chunks around are loaded and lit (SectionUpdateTracker.hasAllNeighbors); the ring of chunks around the
     * view distance never is.
     */
    private byte meshing(final int column) {
        if (this.meshable[column] == 0) {
            int x = this.minX + column / this.nz, z = this.minZ + column % this.nz;
            boolean all = this.level != null;
            for (int dx = -1; all && dx <= 1; dx++) {
                for (int dz = -1; all && dz <= 1; dz++) {
                    all = (dx == 0 && dz == 0) || (this.level.getChunkSource().getChunk(x + dx, z + dz, ChunkStatus.FULL, false) != null
                        && this.level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(x + dx, z + dz)));
                }
            }
            this.meshable[column] = all ? MESHABLE : this.expected(x, z) ? AWAITED : NEVER;
        }
        return this.meshable[column];
    }

    @Override
    public boolean passes(final int index, final int entryFace, final int exitFace) {
        return this.section(index).getSectionMesh().facesCanSeeEachother(FACES[entryFace], FACES[exitFace]);
    }

    @Override
    public boolean inReach(final int index) {
        if (this.reach[index] == 0) {
            int y = index % this.ny, z = index / this.ny % this.nz, x = index / this.ny / this.nz;
            this.reach[index] = (byte)(this.mask.casts(this.minX + x << 4, this.minY + y << 4, this.minZ + z << 4) ? 1 : 2);
        }
        return this.reach[index] == 1;
    }

    @Override
    public void reached(final int index) {
        RenderSection section = this.section(index);
        if (section == null || this.included.get(section.index)) {
            return;
        }
        this.included.set(section.index);
        this.visible.add(section);
        this.casterOnly.add(MetalShaders.sectionKey(section.getBoundingBox()));
        if (section.getSectionMesh() == CompiledSectionMesh.UNCOMPILED && this.meshing(index / this.ny) != NEVER) {
            this.notMeshed++;
        }
    }
}
