package dev.honeycrisp.backend;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * The camera frustum widened to the chunk sections a small turn or step would bring into view. Minecraft picks the
 * sections it draws with this instead of its own frustum while shadows are on: what it then lists (it only lists
 * sections its occlusion graph reaches) is what the camera sees or is about to see, the shadow receivers, and
 * {@link ShadowCasterMask} works out the casters from those. Listing the receivers a little ahead of the view means
 * their casters are meshed, and in the shadow map, by the time the camera has turned to them.
 *
 * <p>The sections this adds to Minecraft's own choice are left out of the main pass again (MetalRenderPass).
 */
public final class ShadowReceiverFrustum extends Frustum {
    /** Slack around the widened view, in blocks: sections next to the camera are kept whatever it looks at. */
    private static final float MARGIN = 32.0F;
    /** The view is widened by this factor in clip space (about 10 degrees on either side). */
    private static final float PAD = 1.5F;

    /** Inward normals (xyz triples) of the four sides of the widened view, which meet at the camera; null: not widened. */
    private final float @Nullable [] planes;

    /** @param viewProj the camera-relative view-projection matrix, or null to leave the frustum as it is */
    public ShadowReceiverFrustum(final Frustum camera, @Nullable final Matrix4fc viewProj) {
        super(camera);
        this.planes = viewProj != null ? widenedViewPlanes(viewProj) : null;
    }

    /** The four sides of the pyramid, with its apex at the camera, through the corners of the padded view. */
    private static float @Nullable [] widenedViewPlanes(final Matrix4fc viewProj) {
        Matrix4f inverse = viewProj.invert(new Matrix4f());
        if (!inverse.isFinite()) {
            return null;
        }
        // The perspective eye is the clip-space direction (0, 0, 1, 0); valid for any depth convention.
        Vector4f eye = inverse.transform(new Vector4f(0.0F, 0.0F, 1.0F, 0.0F));
        if (Math.abs(eye.w) < 1e-6F) {
            return null;
        }
        eye.div(eye.w);
        Vector3f[] rays = new Vector3f[4];
        for (int i = 0; i < 4; i++) {
            float x = (i == 1 || i == 2) ? PAD : -PAD;
            float y = i >= 2 ? PAD : -PAD;
            Vector4f point = inverse.transform(new Vector4f(x, y, 0.5F, 1.0F));
            if (Math.abs(point.w) < 1e-9F) {
                return null;
            }
            rays[i] = new Vector3f(point.x / point.w - eye.x, point.y / point.w - eye.y, point.z / point.w - eye.z).normalize();
            if (!rays[i].isFinite()) {
                return null;
            }
        }
        float[] planes = new float[4 * 3];
        Vector3f normal = new Vector3f();
        for (int i = 0; i < 4; i++) {
            // The side through two neighbouring corners, facing the corner opposite.
            rays[i].cross(rays[(i + 1) % 4], normal);
            if (normal.lengthSquared() < 1e-8F) {
                return null;
            }
            normal.normalize();
            float sign = normal.dot(rays[(i + 2) % 4]) < 0.0F ? -1.0F : 1.0F;
            planes[i * 3] = normal.x * sign;
            planes[i * 3 + 1] = normal.y * sign;
            planes[i * 3 + 2] = normal.z * sign;
        }
        return planes;
    }

    /** Whether the box reaches into the widened view, or lies within the margin around the camera. */
    public boolean inWidenedView(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        float[] planes = this.planes;
        if (planes == null) {
            return false;
        }
        double x0 = minX - this.getCamX(), x1 = maxX - this.getCamX();
        double y0 = minY - this.getCamY(), y1 = maxY - this.getCamY();
        double z0 = minZ - this.getCamZ(), z1 = maxZ - this.getCamZ();
        for (int i = 0; i < planes.length; i += 3) {
            // The box corner furthest along the inward normal.
            double reach = planes[i] * (planes[i] > 0.0F ? x1 : x0) + planes[i + 1] * (planes[i + 1] > 0.0F ? y1 : y0)
                + planes[i + 2] * (planes[i + 2] > 0.0F ? z1 : z0);
            if (reach < -MARGIN) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isVisible(final AABB box) {
        return super.isVisible(box) || this.inWidenedView(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    @Override
    public int cubeInFrustum(final BoundingBox box) {
        int result = super.cubeInFrustum(box);
        if (result != -2 && result != -1 && this.inWidenedView(box.minX(), box.minY(), box.minZ(), box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0)) {
            return -1; // "intersects": the octree keeps descending and tests each section with isVisible
        }
        return result;
    }
}
