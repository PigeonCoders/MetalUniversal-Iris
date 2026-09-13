package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Routes the vanilla world passes that Iris owns on the GL path &mdash; sky,
 * celestial bodies and the held item &mdash; onto their shaderpack programs.
 *
 * <p>On GL, {@code IrisPipelines} maps {@code RenderPipelines.SKY} to
 * {@link ShaderKey#SKY_BASIC}, {@code CELESTIAL} to
 * {@link ShaderKey#SKY_TEXTURED} and the item pipelines to the hand keys, and
 * {@code MixinCompiledShaderProgram}/{@code MixinGlCommandEncoder} then draw
 * those vanilla passes into the pack framebuffer with the pack program. The
 * {@link MetalWorldRenderingPipeline} has no {@code IrisRenderingPipeline}
 * shader map, so those passes used to fall through to vanilla shaders writing
 * straight into the main target. This bridge supplies the Metal equivalent:
 *
 * <ul>
 *   <li>{@link #redirectDescriptor} rewrites the vanilla render-pass
 *       descriptor, whose creation precedes
 *       {@code MetalRenderPass.setPipeline}, from the main target to the
 *       pack's colortex attachments for the current
 *       {@link WorldRenderingPhase};</li>
 *   <li>{@link #installPipeline} swaps the vanilla {@link RenderPipeline} for
 *       the generation-compiled shaderpack program at
 *       {@code setPipeline} time, matching the GL override list.</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class IrisMetalVanillaBridge {
    private static final Set<String> LOGGED = new HashSet<>();

    private IrisMetalVanillaBridge() {
    }

    /** The shaderpack key a vanilla pipeline maps to, or {@code null} to leave it vanilla. */
    static @Nullable ShaderKey keyFor(final RenderPipeline source) {
        if (source == RenderPipelines.SKY) {
            return ShaderKey.SKY_BASIC;
        }
        if (source == RenderPipelines.STARS) {
            return ShaderKey.SKY_BASIC;
        }
        if (source == RenderPipelines.SUNRISE_SUNSET) {
            return ShaderKey.SKY_BASIC_COLOR;
        }
        if (source == RenderPipelines.CELESTIAL) {
            return ShaderKey.SKY_TEXTURED;
        }
        // Mirrors IrisPipelines.getCutout/getSolid/getTranslucent for the hand
        // and entity pipelines that can run inside HandRenderer's phase window.
        // The descriptor is already redirected for HAND_SOLID/HAND_TRANSLUCENT,
        // so every draw reaching setPipeline in those phases must resolve to a
        // pack key or the vanilla pipeline would fail the colortex format check.
        if (source == RenderPipelines.ITEM_CUTOUT
                || source == RenderPipelines.ENTITY_CUTOUT
                || source == RenderPipelines.ENTITY_CUTOUT_CULL
                || source == RenderPipelines.ENTITY_CUTOUT_DISSOLVE
                || source == RenderPipelines.ENTITY_CUTOUT_Z_OFFSET
                || source == RenderPipelines.ARMOR_CUTOUT_NO_CULL
                || source == RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL) {
            return cutoutKey();
        }
        if (source == RenderPipelines.ITEM_TRANSLUCENT
                || source == RenderPipelines.ENTITY_TRANSLUCENT
                || source == RenderPipelines.ENTITY_TRANSLUCENT_CULL
                || source == RenderPipelines.ENTITY_SHADOW
                || source == RenderPipelines.ARMOR_TRANSLUCENT
                || source == RenderPipelines.BREEZE_WIND) {
            return translucentKey();
        }
        if (source == RenderPipelines.ENTITY_SOLID
                || source == RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD) {
            return solidKey();
        }
        if (source == RenderPipelines.TEXT
                || source == RenderPipelines.TEXT_POLYGON_OFFSET
                || source == RenderPipelines.TEXT_SEE_THROUGH) {
            return HandRenderer.INSTANCE.isActive()
                    ? (HandRenderer.INSTANCE.isRenderingSolid()
                    ? ShaderKey.HAND_TEXT
                    : ShaderKey.HAND_TEXT_TRANSLUCENT)
                    : ShaderKey.TEXT;
        }
        return null;
    }

    private static ShaderKey solidKey() {
        if (HandRenderer.INSTANCE.isActive()) {
            return HandRenderer.INSTANCE.isRenderingSolid()
                    ? ShaderKey.HAND_CUTOUT
                    : ShaderKey.HAND_TRANSLUCENT;
        }
        return ShaderKey.ENTITIES_SOLID;
    }

    private static ShaderKey cutoutKey() {
        if (HandRenderer.INSTANCE.isActive()) {
            return HandRenderer.INSTANCE.isRenderingSolid()
                    ? ShaderKey.HAND_CUTOUT_DIFFUSE
                    : ShaderKey.HAND_WATER_DIFFUSE;
        }
        return ShaderKey.ENTITIES_CUTOUT_DIFFUSE;
    }

    private static ShaderKey translucentKey() {
        if (HandRenderer.INSTANCE.isActive()) {
            return HandRenderer.INSTANCE.isRenderingSolid()
                    ? ShaderKey.HAND_CUTOUT_DIFFUSE
                    : ShaderKey.HAND_WATER_DIFFUSE;
        }
        return ShaderKey.ENTITIES_TRANSLUCENT;
    }

    /** The key the current phase expects at render-pass creation time. */
    private static @Nullable ShaderKey keyForPhase(final WorldRenderingPhase phase) {
        return switch (phase) {
            case SKY, STARS, VOID -> ShaderKey.SKY_BASIC;
            case SUNSET -> ShaderKey.SKY_BASIC_COLOR;
            case SUN, MOON -> ShaderKey.SKY_TEXTURED;
            case HAND_SOLID -> HandRenderer.INSTANCE.isRenderingSolid()
                    ? ShaderKey.HAND_CUTOUT_DIFFUSE
                    : ShaderKey.HAND_WATER_DIFFUSE;
            case HAND_TRANSLUCENT -> ShaderKey.HAND_WATER_DIFFUSE;
            default -> null;
        };
    }

    private static boolean phaseAccepts(final WorldRenderingPhase phase, final ShaderKey key) {
        return switch (key) {
            case SKY_BASIC -> phase == WorldRenderingPhase.SKY
                    || phase == WorldRenderingPhase.STARS
                    || phase == WorldRenderingPhase.VOID;
            case SKY_BASIC_COLOR -> phase == WorldRenderingPhase.SUNSET;
            case SKY_TEXTURED -> phase == WorldRenderingPhase.SUN
                    || phase == WorldRenderingPhase.MOON;
            case HAND_CUTOUT, HAND_CUTOUT_BRIGHT, HAND_CUTOUT_DIFFUSE,
                 HAND_TEXT, HAND_TEXT_TRANSLUCENT, HAND_TEXT_INTENSITY,
                 HAND_TRANSLUCENT, HAND_WATER_BRIGHT, HAND_WATER_DIFFUSE ->
                    phase == WorldRenderingPhase.HAND_SOLID
                            || phase == WorldRenderingPhase.HAND_TRANSLUCENT;
            default -> false;
        };
    }

    static ShaderAttributeInputs attributeInputs(final ShaderKey key) {
        return new ShaderAttributeInputs(
                key.getVertexFormat(),
                key == ShaderKey.SKY_BASIC,
                false,
                key == ShaderKey.GLINT,
                key.isText(),
                false
        );
    }

    static IrisMetalGlslLinker.LinkedRasterProgram linkedProgram(
            final MetalWorldRenderingPipeline pipeline,
            final ShaderKey key
    ) {
        return pipeline.programs()
                .vanilla(
                        key.getProgram(),
                        key.getAlphaTest(),
                        false,
                        false,
                        attributeInputs(key)
                )
                .orElse(null);
    }

    static @Nullable MetalCompiledRenderPipeline compiledPipeline(
            final MetalWorldRenderingPipeline pipeline,
            final RenderPipeline source,
            final ShaderKey key
    ) {
        MetalDevice device = MetalDeviceRegistry.getActiveDevice();
        if (device == null) {
            return null;
        }
        if (!pipeline.compiledPrograms().isOwnedBy(device)) {
            throw new IllegalStateException(
                    "Iris Metal vanilla draw crossed Metal device ownership"
            );
        }
        // The pack vertex shader is patched for ShaderKey's vertex format
        // (e.g. IrisVertexFormats.ENTITY for hand keys), not necessarily the
        // vanilla pipeline's binding (DefaultVertexFormat.ENTITY). GL
        // ShaderCreator passes key.getVertexFormat() to the shader pipeline,
        // so the Metal vertex layout must do the same or the extended entity
        // attributes land on the wrong offsets.
        IrisMetalCompiledPrograms.RasterState state =
                IrisMetalCompiledPrograms.RasterState.from(source, key.getVertexFormat());
        return pipeline.compiledPrograms()
                .vanilla(key, attributeInputs(key), state)
                .orElse(null);
    }

    /** Rewrites a vanilla world pass descriptor onto the pack colortex targets. */
    public static @Nullable RenderPassDescriptor redirectDescriptor(
            final MetalWorldRenderingPipeline pipeline,
            final RenderPassDescriptor descriptor
    ) {
        WorldRenderingPhase phase = pipeline.phase();
        ShaderKey key = keyForPhase(phase);
        if (key == null) {
            return null;
        }
        IrisMetalGlslLinker.LinkedRasterProgram linked = linkedProgram(pipeline, key);
        if (linked == null) {
            return null;
        }
        int[] drawBuffers = linked.program().drawBuffers();
        if (drawBuffers.length == 0) {
            drawBuffers = new int[]{0};
        }
        if (descriptor.colorAttachments().isEmpty()) {
            return null;
        }
        RenderPassDescriptor.Attachment<Optional<org.joml.Vector4fc>> color =
                descriptor.colorAttachments().getFirst();
        GpuTextureView sceneColor = color.textureView();
        if (sceneColor.getWidth(0) != pipeline.resources().renderTargets().width()
                || sceneColor.getHeight(0) != pipeline.resources().renderTargets().height()) {
            // Non-scene passes (atlas animation uploads, GUI icons, ...) can
            // run while a world phase is still active, especially during
            // error unwinding. Never rewrite an off-size surface.
            return null;
        }
        org.joml.Vector4fc clearColor = color.clearValue().orElse(null);
        OptionalDouble clearDepth = OptionalDouble.empty();
        RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
        if (depth != null && depth.clearValue().isPresent()) {
            clearDepth = OptionalDouble.of(depth.clearValue().getAsDouble());
        }
        GpuTextureView depthView = pipeline.resources().renderTargets().mainDepthView();
        return pipeline.resources().renderTargets().createTerrainWriteDescriptor(
                descriptor.label().get(),
                drawBuffers,
                sceneColor,
                clearColor,
                depthView,
                clearDepth.isPresent() ? clearDepth.getAsDouble() : null
        );
    }

    /**
     * Installs the pack program for a vanilla world pipeline. Called from the
     * {@code MetalRenderPass.setPipeline} mixin; {@code pass} is passed as an
     * {@link Object} so the mixin never references the package-private pass
     * type.
     */
    public static boolean installPipeline(
            final Object pass,
            final RenderPipeline source,
            final MetalWorldRenderingPipeline pipeline
    ) {
        if (!(pass instanceof MetalRenderPass metalPass)) {
            return false;
        }
        ShaderKey key = keyFor(source);
        if (key == null) {
            return false;
        }
        WorldRenderingPhase phase = pipeline.phase();
        if (!phaseAccepts(phase, key)) {
            if (LOGGED.add("phase-mismatch:" + key + ":" + phase)) {
                MetallumDebugLog.log(
                        "[metallum-iris] vanilla key phase mismatch " + key + " in " + phase
                );
            }
            return false;
        }
        MetalCompiledRenderPipeline compiled = compiledPipeline(pipeline, source, key);
        if (compiled == null) {
            if (LOGGED.add("missing:" + key)) {
                MetallumDebugLog.log("[metallum-iris] vanilla key has no compiled pipeline " + key);
            }
            return false;
        }
        metalPass.setCompiledPipeline(compiled);
        metalPass.aliasExistingIrisVanillaUniforms();
        // TEMPORARY BISECTION: alternate "vanilla sky draws on/off" in 450-frame
        // windows so one device run shows whether the black sky slivers belong to
        // the vanilla sky passes (sky disc / sunrise / sun / moon / stars).
        boolean skyKey = key == ShaderKey.SKY_BASIC
                || key == ShaderKey.SKY_BASIC_COLOR
                || key == ShaderKey.SKY_TEXTURED;
        boolean hideWindow = (IrisMetalFrameDiagnostics.frame() / 450) % 2 == 1;
        boolean experiment = MetalExperimentGate.enabled("metallum.experiment.hideVanillaSky");
        if (skyKey && experiment && hideWindow) {
            metalPass.setSkipDraws(true);
        }
        if (skyKey && experiment) {
            IrisMetalFrameDiagnostics.logOnce(
                    "vanilla-sky-window",
                    "vanilla sky draws=" + (hideWindow ? "SKIPPED" : "DRAWN") + " key=" + key
            );
        }
        if (compiled.resource(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME) != null) {
            int dynamicSize = pipeline.uniformValues().coreDrawBlockSize(key);
            GpuBufferSlice slice;
            if (dynamicSize > 0) {
                try (GpuBufferSlice.MappedView mapped = metalPass.allocateTransient(
                        dynamicSize, 256, GpuBuffer.USAGE_UNIFORM
                )) {
                    pipeline.uniformValues().materializeCoreDraw(
                            key,
                            mapped.data(),
                            RenderSystem.getModelViewMatrixCopy(),
                            CapturedRenderingState.INSTANCE.getGbufferProjection()
                    );
                    slice = mapped.slice();
                }
            } else {
                slice = pipeline.uniformSlice(key);
            }
            if (slice != null) {
                metalPass.setUniform(IrisMetalGlslLinker.UNIFORM_BLOCK_NAME, slice);
            }
        }
        if (LOGGED.add("install:" + key)) {
            MetallumDebugLog.log("[metallum-iris] vanilla key installed " + key);
        }
        return true;
    }

    /** Sampler fallback for non-terrain pack draws; terrain keeps its own richer context. */
    public static MetalRenderPass.TextureViewAndSampler fallbackSampler(
            final MetalWorldRenderingPipeline pipeline,
            final String name,
            final java.util.Map<String, MetalRenderPass.TextureViewAndSampler> bound
    ) {
        MetalRenderPass.TextureViewAndSampler alias = switch (name) {
            case "gtexture", "texture", "tex" -> bound.get("Sampler0");
            case "lightmap" -> {
                MetalRenderPass.TextureViewAndSampler found = bound.get("Sampler2");
                yield found != null ? found : bound.get("Sampler1");
            }
            default -> null;
        };
        if (alias != null) {
            return alias;
        }
        if ("noisetex".equals(name)) {
            return pipeline.resources().noiseTexture().binding();
        }
        if ("iris_overlay".equals(name)) {
            return pipeline.resources().whitePixel();
        }
        IrisMetalRenderTargets targets = pipeline.resources().renderTargets();
        GpuTextureView depthView = switch (name) {
            case "depthtex0" -> targets.mainDepthView();
            case "depthtex1" -> targets.noTranslucentsDepthView();
            case "depthtex2" -> targets.noHandDepthView();
            default -> null;
        };
        if (depthView != null) {
            return new MetalRenderPass.TextureViewAndSampler(
                    depthView, targets.depthSampler()
            );
        }
        int colorIndex = IrisMetalTerrainBridge.renderTargetIndex(name);
        if (colorIndex >= 0) {
            if (colorIndex >= targets.colorTargets().targetCount()) {
                return null;
            }
            return new MetalRenderPass.TextureViewAndSampler(
                    targets.colorTargets().readView(colorIndex),
                    targets.colorSampler(colorIndex)
            );
        }
        return null;
    }
}
