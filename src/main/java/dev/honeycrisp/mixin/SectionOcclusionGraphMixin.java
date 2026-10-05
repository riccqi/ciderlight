package dev.honeycrisp.mixin;

import dev.honeycrisp.backend.HitchTrace;
import dev.honeycrisp.backend.MetalShaders;
import dev.honeycrisp.backend.ShadowCasterSelection;
import dev.honeycrisp.backend.ShadowReceiverFrustum;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds to the sections Minecraft draws those that cast a shadow onto something in view, so off-screen terrain still
 * casts shadows: the shadow maps are rendered by replaying Minecraft's own terrain draws.
 */
@Mixin(SectionOcclusionGraph.class)
public class SectionOcclusionGraphMixin {
    @Shadow private ViewArea viewArea;
    @Shadow @Final private LongOpenHashSet loadedChunks;
    @Shadow @Final private LongOpenHashSet emptySections;
    @Shadow @Final private AtomicBoolean needsFrustumUpdate;
    @Shadow @Final private AtomicReference<?> currentGraph;
    @Unique private final ShadowCasterSelection honeycrisp$selection = new ShadowCasterSelection();
    /** Set when a section's mesh has been built (from whichever thread reports it). */
    @Unique private volatile boolean honeycrisp$meshBuilt;
    /** The frustum Minecraft itself picks sections with, and what honeycrisp$widenView made of it. */
    @Unique private @Nullable Frustum honeycrisp$seen;
    @Unique private @Nullable Frustum honeycrisp$widened;
    @Unique private long honeycrisp$lastCameraBlock = Long.MIN_VALUE;
    @Unique private boolean honeycrisp$hadCasters;
    @Unique private int honeycrisp$lightStamp;
    @Unique private long honeycrisp$selectionStart;

    // The light is not followed through sections whose mesh is not built yet (ShadowCasterSelection.mesh): it goes on
    // from there once this reports the mesh.
    @Inject(method = "schedulePropagationFrom", at = @At("HEAD"))
    private void honeycrisp$noteMeshBuilt(final RenderSection section, final CallbackInfo ci) {
        this.honeycrisp$meshBuilt = true;
    }

    // Occlusion propagation may never visit an off-camera caster. Refresh the supplemental
    // list on movement/loading anyway, without forcing a rebuild of the visibility graph.
    @Inject(method = "update", at = @At("TAIL"))
    private void honeycrisp$refreshCasters(final CameraRenderState camera, final int fov,
                                          final ChunkLoadingRenderState chunks, final CallbackInfo ci) {
        boolean enabled = MetalShaders.needsShadowCasters();
        long block = camera.blockPos.asLong();
        // Which sections cast into view also depends on where the light is (ShadowCasterMask).
        int lightStamp = MetalShaders.casterLightStamp();
        boolean lightMoved = lightStamp != this.honeycrisp$lightStamp;
        this.honeycrisp$lightStamp = lightStamp;
        boolean terrainChanged = !chunks.addedLoadedChunks.isEmpty() || !chunks.removedLoadedChunks.isEmpty()
            || !chunks.addedEmptySections.isEmpty() || !chunks.removedEmptySections.isEmpty();
        if (!chunks.addedLoadedChunks.isEmpty()) {
            this.honeycrisp$selection.chunkArrived();
        }
        if (this.honeycrisp$meshBuilt) {
            this.honeycrisp$meshBuilt = false;
            terrainChanged = true;
        }
        if (terrainChanged) {
            this.honeycrisp$selection.invalidate();
        }
        // A stale selection is one that put off looking at the terrain again: keep asking until it has.
        if (enabled != this.honeycrisp$hadCasters
            || (enabled && (lightMoved || block != this.honeycrisp$lastCameraBlock || terrainChanged || this.honeycrisp$selection.stale()))) {
            this.needsFrustumUpdate.set(true);
        }
        this.honeycrisp$hadCasters = enabled;
        this.honeycrisp$lastCameraBlock = block;
    }

    @Inject(method = "addSectionsInFrustum", at = @At("HEAD"))
    private void honeycrisp$timeSelection(final Frustum camera, final List<RenderSection> visible,
                                          final List<RenderSection> nearby, final CallbackInfo ci) {
        this.honeycrisp$selectionStart = System.nanoTime();
    }

    @ModifyArg(
        method = "addSectionsInFrustum",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/Octree;visitNodes(Lnet/minecraft/client/renderer/Octree$OctreeVisitor;Lnet/minecraft/client/renderer/culling/Frustum;I)V"
        ),
        index = 1
    )
    private Frustum honeycrisp$widenView(final Frustum frustum) {
        this.honeycrisp$seen = frustum;
        this.honeycrisp$widened = MetalShaders.receiverFrustum(frustum);
        return this.honeycrisp$widened;
    }

    @Inject(method = "addSectionsInFrustum", at = @At("TAIL"))
    private void honeycrisp$addCasters(final Frustum camera, final List<RenderSection> visible,
                                       final List<RenderSection> nearby, final CallbackInfo ci) {
        this.honeycrisp$selectCasters(camera, visible);
        if (HitchTrace.ENABLED) {
            HitchTrace.add(HitchTrace.SECTIONS, System.nanoTime() - this.honeycrisp$selectionStart);
            LongOpenHashSet casterOnly = MetalShaders.casterOnlySections();
            HitchTrace.sectionList(
                visible.size(), casterOnly != null ? casterOnly.size() : 0, this.honeycrisp$selection.notMeshed(), this.honeycrisp$selection.airInView()
            );
        }
    }

    @Unique
    private void honeycrisp$selectCasters(final Frustum camera, final List<RenderSection> visible) {
        Frustum seen = this.honeycrisp$seen;
        Vector3fc light = MetalShaders.casterLight();
        if (this.viewArea == null || seen == null || light == null || !(this.honeycrisp$widened instanceof ShadowReceiverFrustum widened)) {
            MetalShaders.casterOnlySections(null);
            return;
        }
        MetalShaders.casterOnlySections(this.honeycrisp$selection.select(
            (SectionOcclusionGraph)(Object)this, this.currentGraph.get(), this.viewArea, this.loadedChunks, this.emptySections,
            camera, seen, widened, light, visible
        ));
    }
}
