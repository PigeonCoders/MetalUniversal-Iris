package com.metallum.mixin.iris;

import com.metallum.client.metal.render.MetalActive;
import net.irisshaders.iris.Iris;
import net.minecraft.client.renderer.Projection;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Metal has no OpenGL equivalent of {@code glDepthRange}: the rasterizer clips
 * clip-space z outside [0,1] outright. While an Iris pack is in use the engine
 * switches its projections to OpenGL-style [-1,1] depth (Iris's
 * {@code UndoReverseZOne/Four} path, which the Metal pipeline opts into via
 * {@code isPackInUseQuick}), so every fragment with negative clip z is dropped
 * by Metal instead of being mapped into [0,1] the way GL does.
 *
 * <p>This is what cut the GUI item atlas models in half (their front half has
 * negative clip z) and made flat 2D item icons disappear entirely (they sit at
 * z &le; 0). Pre-multiplying the projection matrix with the standard
 * [-1,1] -&gt; [0,1] remap ({@code z' = (z + w) / 2}, i.e. the z row becomes
 * half the z row plus half the w row) reproduces GL's depth range on Metal.
 *
 * <p>With this remap in place the backend's depth compare flip and depth clear
 * complement (see {@code MetalCompiledRenderPipeline} and
 * {@code MetalCommandEncoder}) form the complete standard-z convention that
 * Iris's {@code UndoReverseZThree/Five} implement for GL.
 */
@Mixin(Projection.class)
public abstract class ProjectionDepthRemapMixin {
    @Inject(method = "getMatrix", at = @At("RETURN"))
    private void metallum$remapDepthToZeroOne(final CallbackInfoReturnable<Matrix4f> cir) {
        if (!MetalActive.isMetalActive() || !Iris.isPackInUseQuick()) {
            return;
        }

        Matrix4f matrix = cir.getReturnValue();
        float z0 = matrix.m20();
        float z1 = matrix.m21();
        float z2 = matrix.m22();
        float z3 = matrix.m23();
        float w0 = matrix.m30();
        float w1 = matrix.m31();
        float w2 = matrix.m32();
        float w3 = matrix.m33();
        matrix.m20(0.5F * z0 + 0.5F * w0);
        matrix.m21(0.5F * z1 + 0.5F * w1);
        matrix.m22(0.5F * z2 + 0.5F * w2);
        matrix.m23(0.5F * z3 + 0.5F * w3);
    }
}
