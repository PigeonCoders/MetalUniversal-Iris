package com.metallum.client.metal.fx;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Read-only bridge between the MetalFX feature and the pre-existing
 * {@code -Dmetallum.iris.renderScale} path, so the two never stack.
 *
 * <p>There are two independent ways to render below the drawable size:
 *
 * <ul>
 *   <li><b>MetalFX</b> — {@code WindowMixin} shrinks the framebuffer extent
 *       reported by {@link com.mojang.blaze3d.platform.Window} by
 *       {@link MetalFxConfig.SpatialMode#renderScale}; vanilla, Iris and the
 *       GUI all allocate at that size and {@code MetalSurface} upscales on
 *       present.</li>
 *   <li><b>render scale</b> — {@code -Dmetallum.iris.renderScale} resizes the
 *       vanilla main render target in
 *       {@code MetalWorldRenderingPipeline.prepareResources}, with
 *       {@code MetalRenderScaleMixin} keeping {@code GameRenderer} from
 *       undoing it.</li>
 * </ul>
 *
 * <p>Multiplying them is never intended (a spatial preset plus a 0.75 render
 * scale would give 0.58, i.e. a visibly mushy image) and there is no single
 * "internal resolution" number left to reason about. This query makes MetalFX
 * the single owner whenever it is active; every render-scale entry point asks
 * it first and steps aside.
 *
 * <p>The result mirrors the exact condition {@code WindowMixin} uses to shrink
 * the extent (Metal backend + spatial upscaling active), so the answer is
 * always "does something other than the render-scale path already own this
 * frame's internal size". It is cheap enough to call per frame: one backend
 * name comparison plus volatile reads, and every call is wrapped so a failing
 * backend cannot take the frame down.
 */
@Environment(EnvType.CLIENT)
public final class MetalFxCompat {
    private MetalFxCompat() {
    }

    /**
     * @return {@code true} when MetalFX is currently responsible for the
     *         internal render resolution, i.e. the render-scale path must not
     *         resize the main target. Never throws.
     */
    public static boolean isInternalResolutionOwnedByMetalFx() {
        try {
            if (!isMetalBackendActive()) {
                return false;
            }
            return MetalFxConfig.get().isSpatialUpscalingActive();
        } catch (Throwable ignored) {
            // Capability queries must never break rendering; when the answer
            // is unknown, the render-scale path (opt-in, default 1.0) keeps
            // its previous behaviour.
            return false;
        }
    }

    /**
     * @return the shrink factor MetalFX applies to the reported framebuffer
     *         extent, or {@code 1.0} when MetalFX is not active. Diagnostic
     *         only (the startup warning / probe receipts).
     */
    public static float internalRenderScale() {
        if (!isInternalResolutionOwnedByMetalFx()) {
            return 1.0f;
        }
        return MetalFxConfig.get().spatialMode().renderScale;
    }

    private static boolean isMetalBackendActive() {
        var device = RenderSystem.getDevice();
        if (device == null) {
            return false;
        }
        return "Metal".equals(device.getDeviceInfo().backendName());
    }
}
