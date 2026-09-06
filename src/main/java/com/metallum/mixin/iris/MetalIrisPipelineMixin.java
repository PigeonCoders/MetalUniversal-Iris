package com.metallum.mixin.iris;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalActive;
import com.metallum.client.metal.render.MetalIrisProgram;
import com.metallum.client.metal.render.MetalIrisProgramRegistry;
import com.metallum.client.metal.render.MetalIrisProgramsToClear;
import com.metallum.client.metal.render.MetalWorldRenderingPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * Metal-side Iris render-dispatch mixin: detect when a vanilla/sodium
 * {@code RenderPipeline} maps to a shaderpack program and install that
 * program's pre-compiled Metal pipeline onto the active {@code MetalRenderPass}.
 *
 * <p>This is the Metal counterpart of iris-ref's {@code MixinGlCommandEncoder}
 * {@code trySetup RETURN} injector. The GL path recovers the Iris program from
 * {@code glRenderPass.pipeline.program()} &mdash; the {@code GlProgram} carried
 * by the GL render pipeline. Metal's {@code MetalRenderPass} does not carry a
 * program handle, so instead this mixin intercepts
 * {@code MetalRenderPass.setPipeline(RenderPipeline)} (the vanilla/sodium entry
 * point that installs a {@code RenderPipeline}) and maps
 * {@code RenderPipeline} &rarr; {@link ShaderKey} &rarr; program name &rarr;
 * {@link MetalIrisProgram} via the same {@link IrisPipelines#getPipeline}
 * lookup the GL path uses in {@code MixinShaderManager_Overrides}.
 *
 * <p>Once a {@link MetalIrisProgram} is resolved, {@code iris$setupState} is
 * invoked to swap in the shaderpack's pre-compiled
 * {@code MetalCompiledRenderPipeline} (via
 * {@code MetalRenderPass.setCompiledPipeline}) and mark the pass's pipeline +
 * descriptor state dirty. The actual Metal encoder calls
 * ({@code setRenderPipelineState} / {@code setTextureAndSampler}) are deferred
 * to {@code MetalRenderPass.bindDrawState} on the next draw, which re-pushes
 * every descriptor (including samplers) from the pass's own {@code samplers}
 * map against the shaderpack pipeline's reflected resource table.
 *
 * <p><b>Samplers and albedoTex are passed as {@code null}.</b>
 * {@code MetalIrisProgram.iris$setupState} accepts a
 * {@code HashMap<String, GlRenderPass.TextureViewAndSampler>}, and that record's
 * constructor requires {@code GlTextureView}/{@code GlSampler} &mdash; blaze3d
 * GL-specific subclasses of {@code GpuTextureView}/{@code GpuSampler}. Metal's
 * {@code MetalGpuTextureView}/{@code MetalGpuSampler} extend
 * {@code GpuTextureView}/{@code GpuSampler} but are <i>not</i>
 * {@code GlTextureView}/{@code GlSampler} (they are sibling subclasses, not
 * related by inheritance), so a {@code GlRenderPass.TextureViewAndSampler}
 * cannot be constructed from Metal views: the constructor rejects them at
 * compile time, and reflective {@code Constructor.newInstance} rejects them at
 * runtime via method-invocation conversion. Passing {@code null} is explicitly
 * supported by {@code MetalIrisProgram.iris$setupState} ("May be {@code null};
 * treated as empty") and is functionally equivalent here: the shaderpack
 * pipeline's {@code SAMPLED_IMAGE} resources are re-bound by
 * {@code MetalRenderPass.bindDrawState} from the pass's existing
 * {@code samplers} map (populated by prior {@code bindTexture} calls), not from
 * the map handed to {@code iris$setupState}. {@code albedoTex} is likewise
 * {@code null}; it is documented as ignored on Metal.
 *
 * <p><b>Non-Metal no-op.</b> Gated by {@link MetalActive#isMetalActive()} so
 * the GL path is untouched when Metal is not the active backend. The
 * {@code iris$clearState} counterpart lives in {@link MetalIrisClearMixin},
 * which clears the pending list registered via
 * {@link MetalIrisProgramsToClear}.
 *
 * <p><b>Target visibility.</b> {@code MetalRenderPass} is package-private, so
 * this mixin targets it by fully-qualified name string
 * ({@code targets = "..."}) rather than class literal, avoiding a compile-time
 * reference to the package-private type.
 */
@Environment(EnvType.CLIENT)
@Mixin(targets = "com.metallum.client.metal.render.MetalRenderPass")
public class MetalIrisPipelineMixin {
    private static final Set<String> LOGGED_VANILLA_PASSES = new HashSet<>();

    @Inject(method = "setPipeline", at = @At("RETURN"))
    private void metallum$irisSetupState(final RenderPipeline pipeline, final CallbackInfo ci) {
        if (!MetalActive.isMetalActive()) {
            return;
        }

        final WorldRenderingPipeline worldPipeline = Iris.getPipelineManager().getPipelineNullable();
        if (worldPipeline instanceof MetalWorldRenderingPipeline) {
            String location = pipeline.getLocation().toString();
            if (!location.contains("sodium") && LOGGED_VANILLA_PASSES.add(location)) {
                Metallum.LOGGER.info(
                        "[metallum-iris] vanilla pipeline reached MetalRenderPass without iris override: {}",
                        location
                );
            }
            return;
        }
        if (!(worldPipeline instanceof IrisRenderingPipeline irisPipeline)
                || !irisPipeline.shouldOverrideShaders()) {
            return;
        }

        final ShaderKey shaderKey = IrisPipelines.getPipeline(irisPipeline, pipeline);
        if (shaderKey == null) {
            return;
        }
        if (LOGGED_VANILLA_PASSES.add("key:" + shaderKey.getName())) {
            Metallum.LOGGER.info(
                    "[metallum-iris] iris override candidate key={} pipeline={}",
                    shaderKey.getName(), pipeline.getLocation()
            );
        }

        // The registry key is the ShaderKey's lowercased enum name (e.g.
        // "terrain_solid", "entities_solid") — the same `name` Iris passes to
        // ShaderCreator.link and that ShaderCreatorMixin registers the
        // MetalIrisProgram under. This is NOT
        // shaderKey.getProgram().getSourceName() (the ProgramId source name
        // such as "gbuffers_terrain_solid"), which is a different string.
        final MetalIrisProgram program = MetalIrisProgramRegistry.get(shaderKey.getName());
        if (program == null || program.iris$isSetUp()) {
            return;
        }

        // See class javadoc: samplers and albedoTex are passed as null because
        // GlRenderPass.TextureViewAndSampler's constructor requires GL-specific
        // types incompatible with Metal views. setCompiledPipeline (invoked
        // inside iris$setupState) plus MetalRenderPass.bindDrawState re-bind the
        // pass's existing samplers against the shaderpack pipeline.
        program.iris$setupState(null, null);

        MetalIrisProgramsToClear.PROGRAMS.add(program);
    }
}
