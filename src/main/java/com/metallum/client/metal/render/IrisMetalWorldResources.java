package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** GPU resources owned and retired atomically by one Iris world generation. */
final class IrisMetalWorldResources implements AutoCloseable {
    private final MetalDevice device;
    private final int generation;
    private final IrisMetalRenderTargets renderTargets;
    @Nullable
    private final IrisMetalShadowTargets shadowTargets;
    private final IrisMetalCustomTextures customTextures;
    private final IrisMetalNoiseTexture noiseTexture;
    @Nullable
    private final IrisMetalComputeResources computeResources;
    private boolean closed;

    IrisMetalWorldResources(
            final MetalDevice device,
            final int generation,
            final ProgramSet programSet,
            final int width,
            final int height
    ) {
        this(
                device,
                generation,
                IrisMetalRenderTargetFormats.from(programSet.getPackDirectives()),
                width,
                height,
                programSet.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings(),
                mipmappedTargets(programSet),
                programSet.getPack().getCustomTextureDataMap(),
                programSet.getPack().getIrisCustomTextureDataMap(),
                programSet.getPackDirectives().getNoiseTextureResolution(),
                programSet.getPack().getCustomNoiseTexture(),
                createShadowTargets(device, programSet),
                programSet.getPack()
        );
    }

    IrisMetalWorldResources(
            final MetalDevice device,
            final int generation,
            final GpuFormat[] formats,
            final int width,
            final int height,
            final Map<Integer, RenderTargetSettings> targetSettings,
            final Set<Integer> mipmappedTargets,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> customDefinitions,
            final int noiseResolution,
            final @Nullable CustomTextureData customNoise
    ) {
        this(
                device,
                generation,
                formats,
                width,
                height,
                targetSettings,
                mipmappedTargets,
                customDefinitions,
                Map.of(),
                noiseResolution,
                customNoise,
                null,
                null
        );
    }

    IrisMetalWorldResources(
            final MetalDevice device,
            final int generation,
            final GpuFormat[] formats,
            final int width,
            final int height,
            final Map<Integer, RenderTargetSettings> targetSettings,
            final Set<Integer> mipmappedTargets,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> customDefinitions,
            final Map<String, ? extends CustomTextureData> irisDefinitions,
            final int noiseResolution,
            final @Nullable CustomTextureData customNoise
    ) {
        this(
                device,
                generation,
                formats,
                width,
                height,
                targetSettings,
                mipmappedTargets,
                customDefinitions,
                irisDefinitions,
                noiseResolution,
                customNoise,
                null,
                null
        );
    }

    private IrisMetalWorldResources(
            final MetalDevice device,
            final int generation,
            final GpuFormat[] formats,
            final int width,
            final int height,
            final Map<Integer, RenderTargetSettings> targetSettings,
            final Set<Integer> mipmappedTargets,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> customDefinitions,
            final Map<String, ? extends CustomTextureData> irisDefinitions,
            final int noiseResolution,
            final @Nullable CustomTextureData customNoise,
            final @Nullable IrisMetalShadowTargets shadowTargets,
            final @Nullable ShaderPack computePack
    ) {
        this.device = Objects.requireNonNull(device, "device");
        if (generation <= 0) {
            throw new IllegalArgumentException("Iris generation must be positive: " + generation);
        }
        this.generation = generation;

        IrisMetalRenderTargets newTargets = null;
        IrisMetalShadowTargets newShadowTargets = shadowTargets;
        IrisMetalCustomTextures newCustomTextures = null;
        IrisMetalNoiseTexture newNoiseTexture = null;
        IrisMetalComputeResources newComputeResources = null;
        try {
            newTargets = new IrisMetalRenderTargets(
                    device, formats, width, height, targetSettings, mipmappedTargets
            );
            newCustomTextures = new IrisMetalCustomTextures(device, customDefinitions, irisDefinitions);
            newCustomTextures.prewarmAll();
            newNoiseTexture = new IrisMetalNoiseTexture(device, noiseResolution, customNoise);
            if (computePack != null) {
                newComputeResources = new IrisMetalComputeResources(device, computePack, width, height);
            }
        } catch (RuntimeException | Error failure) {
            closePartial(newTargets, newShadowTargets, newCustomTextures, newNoiseTexture, newComputeResources);
            throw failure;
        }
        this.renderTargets = newTargets;
        this.shadowTargets = newShadowTargets;
        this.customTextures = newCustomTextures;
        this.noiseTexture = newNoiseTexture;
        this.computeResources = newComputeResources;
    }

    int generation() {
        return this.generation;
    }

    boolean isOwnedBy(final MetalDevice expected) {
        return this.device == expected;
    }

    IrisMetalRenderTargets renderTargets() {
        ensureOpen();
        return this.renderTargets;
    }

    IrisMetalCustomTextures customTextures() {
        ensureOpen();
        return this.customTextures;
    }

    IrisMetalNoiseTexture noiseTexture() {
        ensureOpen();
        return this.noiseTexture;
    }

    @Nullable
    IrisMetalShadowTargets shadowTargets() {
        ensureOpen();
        return this.shadowTargets;
    }

    @Nullable
    IrisMetalComputeResources computeResources() {
        ensureOpen();
        return this.computeResources;
    }

    void resize(final int width, final int height) {
        ensureOpen();
        this.renderTargets.resize(width, height);
        if (this.computeResources != null) {
            this.computeResources.resize(width, height);
        }
    }

    private static void closePartial(
            final @Nullable IrisMetalRenderTargets targets,
            final @Nullable IrisMetalShadowTargets shadowTargets,
            final @Nullable IrisMetalCustomTextures customTextures,
            final @Nullable IrisMetalNoiseTexture noiseTexture,
            final @Nullable IrisMetalComputeResources computeResources
    ) {
        if (noiseTexture != null) {
            noiseTexture.close();
        }
        if (customTextures != null) {
            customTextures.close();
        }
        if (shadowTargets != null) {
            shadowTargets.close();
        }
        if (targets != null) {
            targets.close();
        }
        if (computeResources != null) {
            computeResources.close();
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris Metal generation " + this.generation + " is closed");
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        closePartial(
                this.renderTargets,
                this.shadowTargets,
                this.customTextures,
                this.noiseTexture,
                this.computeResources
        );
    }

    @Nullable
    private static IrisMetalShadowTargets createShadowTargets(
            final MetalDevice device,
            final ProgramSet programSet
    ) {
        PackDirectives directives = programSet.getPackDirectives();
        PackShadowDirectives shadow = directives.getShadowDirectives();
        if (!shadow.isShadowEnabled().orElse(true)
                || new ProgramFallbackResolver(programSet).resolveNullable(ProgramId.ShadowSolid) == null) {
            return null;
        }

        int targetCount = programSet.getPack().hasFeature(FeatureFlags.HIGHER_SHADOWCOLOR)
                ? PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS
                : PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_OF;
        boolean[] nearestColor = new boolean[targetCount];
        boolean[] mipmappedColor = new boolean[targetCount];
        GpuFormat[] colorFormats = new GpuFormat[targetCount];
        for (int index = 0; index < targetCount; index++) {
            PackShadowDirectives.SamplingSettings settings = shadow.getColorSamplingSettings().get(index);
            if (settings == null) {
                settings = new PackShadowDirectives.SamplingSettings();
            }
            nearestColor[index] = settings.getNearest();
            mipmappedColor[index] = settings.getMipmap();
            colorFormats[index] = IrisMetalRenderTargetFormats.fromInternalName(settings.getFormat().name());
        }

        boolean[] nearestDepth = new boolean[2];
        boolean[] mipmappedDepth = new boolean[2];
        for (int index = 0; index < 2; index++) {
            PackShadowDirectives.DepthSamplingSettings settings = shadow.getDepthSamplingSettings().get(index);
            nearestDepth[index] = settings.getNearest();
            mipmappedDepth[index] = settings.getMipmap();
        }
        return new IrisMetalShadowTargets(
                device,
                colorFormats,
                shadow.getResolution(),
                nearestColor,
                mipmappedColor,
                nearestDepth,
                mipmappedDepth
        );
    }

    private static Set<Integer> mipmappedTargets(final ProgramSet programSet) {
        java.util.HashSet<Integer> result = new java.util.HashSet<>();
        for (ProgramArrayId arrayId : new ProgramArrayId[]{
                ProgramArrayId.Setup, ProgramArrayId.Begin, ProgramArrayId.Prepare,
                ProgramArrayId.Deferred, ProgramArrayId.Composite, ProgramArrayId.ShadowComposite
        }) {
            for (ProgramSource source : programSet.getComposite(arrayId)) {
                if (source != null && source.isValid()) {
                    result.addAll(source.getDirectives().getMipmappedBuffers());
                }
            }
        }
        programSet.get(ProgramId.Final).ifPresent(source -> {
            if (source.isValid()) {
                result.addAll(source.getDirectives().getMipmappedBuffers());
            }
        });
        int targetCount = IrisMetalRenderTargetFormats.from(programSet.getPackDirectives()).length;
        for (Integer target : result) {
            if (target == null || target < 0 || target >= targetCount) {
                throw new IllegalArgumentException(
                        "Iris mipmap target out of range: " + target + " (count=" + targetCount + ")"
                );
            }
        }
        return Set.copyOf(result);
    }
}
