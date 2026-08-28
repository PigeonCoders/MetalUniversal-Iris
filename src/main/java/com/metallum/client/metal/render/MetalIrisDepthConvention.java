package com.metallum.client.metal.render;

import com.mojang.blaze3d.platform.CompareOp;
import net.irisshaders.iris.Iris;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Depth-convention bridge between Blaze3D's reverse-Z Metal world and Iris's
 * conventional OpenGL world.
 *
 * <p>Iris 1.11.2+26.2 already undoes Blaze3D reverse-Z while a shaderpack is
 * active: its {@code UndoReverseZFour} mixin rewrites
 * {@code Projection.getMatrix()} to emit a forward OpenGL projection (near
 * {@code -w}, far {@code +w}), and its GL-path mixins clear depth to 1.0 and
 * invert depth compares. {@code CapturedRenderingState.getGbufferProjection()}
 * therefore already carries GL-convention matrices when our pipeline samples
 * it — applying another pack transform here double-converts the projection.
 *
 * <p>The Metal backend must mirror the remaining GL-path undo steps itself:
 * shaderpack vertex MSL remaps GL clip-z to Metal [0,w] (see
 * {@link MetalMslClipSpace}), depth clears become {@code 1.0 - clearDepth},
 * and reverse-Z compare ops are inverted.
 */
final class MetalIrisDepthConvention {
    private MetalIrisDepthConvention() {
    }

    /** True while Iris reports a live shaderpack and this backend owns it. */
    static boolean conventionalDepthActive() {
        try {
            return MetalActive.isMetalActive() && Iris.isPackInUseQuick();
        } catch (Throwable ignored) {
            // Iris may be absent in a vanilla-only deployment; reverse-Z remains.
            return false;
        }
    }

    /**
     * CapturedRenderingState already holds a GL-convention matrix while a pack
     * is active. Keep it unchanged rather than packing it a second time.
     */
    static Matrix4f projection(final Matrix4fc capturedGlProjection) {
        return new Matrix4f(capturedGlProjection);
    }

    static Matrix4f projectionInverse(final Matrix4fc capturedGlProjection) {
        return new Matrix4f(capturedGlProjection).invert();
    }

    /** Blaze3D reverse-Z depth clear to Iris's conventional depth clear. */
    static double conventionalClearDepth(final double metalClearDepth) {
        if (!conventionalDepthActive()) {
            return metalClearDepth;
        }
        return Math.clamp(1.0 - metalClearDepth, 0.0, 1.0);
    }

    /**
     * Iris GL maps reverse-Z compare ops to their conventional counterpart
     * (e.g. GREATER_THAN_OR_EQUAL becomes LESS_THAN_OR_EQUAL). Mirror that
     * mapping for shaderpack Metal pipelines whose vertex depth is now
     * conventional near=0, far=1.
     */
    static CompareOp invertForConventionalDepth(final CompareOp op) {
        return switch (op) {
            case GREATER_THAN_OR_EQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
            case GREATER_THAN -> CompareOp.LESS_THAN;
            case LESS_THAN_OR_EQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
            case LESS_THAN -> CompareOp.GREATER_THAN;
            case ALWAYS_PASS, EQUAL, NOT_EQUAL, NEVER_PASS -> op;
        };
    }
}
