package com.metallum.client.metal.render;

import com.metallum.client.metal.render.mtl.MTLSamplerMipFilter;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives.RenderTargetSettings;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/** Generation-owned Iris colortex ping-pong and depthtex0/1/2 resources. */
@Environment(EnvType.CLIENT)
final class IrisMetalRenderTargets implements AutoCloseable {
    private static final int DEPTH_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST;

    private final MetalDevice device;
    private final IrisMetalPingPongTargets colorTargets;
    private final Map<Integer, RenderTargetSettings> targetSettings;
    private MetalGpuTexture mainDepth;
    private MetalGpuTexture noTranslucentsDepth;
    private MetalGpuTexture noHandDepth;
    private MetalGpuTextureView mainDepthView;
    private MetalGpuTextureView noTranslucentsDepthView;
    private MetalGpuTextureView noHandDepthView;
    private final MetalGpuSampler colorSampler;
    private final MetalGpuSampler nearestSampler;
    private final MetalGpuSampler colorMipSampler;
    private final MetalGpuSampler nearestMipSampler;
    private int width;
    private int height;
    private boolean fullClearRequired = true;
    private boolean closed;

    IrisMetalRenderTargets(
            final MetalDevice device,
            final GpuFormat[] colorFormats,
            final int width,
            final int height
    ) {
        this(device, colorFormats, width, height, Map.of(), Set.of());
    }

    IrisMetalRenderTargets(
            final MetalDevice device,
            final GpuFormat[] colorFormats,
            final int width,
            final int height,
            final Map<Integer, RenderTargetSettings> targetSettings
    ) {
        this(device, colorFormats, width, height, targetSettings, Set.of());
    }

    IrisMetalRenderTargets(
            final MetalDevice device,
            final GpuFormat[] colorFormats,
            final int width,
            final int height,
            final Map<Integer, RenderTargetSettings> targetSettings,
            final Set<Integer> mipmappedTargets
    ) {
        this.device = device;
        this.colorTargets = new IrisMetalPingPongTargets(
                device, "iris-colortex", colorFormats, width, height, mipmappedTargets
        );
        this.targetSettings = Map.copyOf(targetSettings);
        this.colorSampler = sampler(FilterMode.LINEAR, MTLSamplerMipFilter.NotMipmapped);
        this.nearestSampler = sampler(FilterMode.NEAREST, MTLSamplerMipFilter.NotMipmapped);
        this.colorMipSampler = sampler(FilterMode.LINEAR, MTLSamplerMipFilter.Linear);
        this.nearestMipSampler = sampler(FilterMode.NEAREST, MTLSamplerMipFilter.Linear);
        createDepthTextures(width, height);
    }

    private MetalGpuSampler sampler(final FilterMode filter, final MTLSamplerMipFilter mipFilter) {
        return new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                filter,
                filter,
                1,
                OptionalDouble.empty(),
                null,
                mipFilter
        );
    }

    boolean clearForFrame(final MetalCommandEncoder encoder, final Vector4fc fogColor) {
        ensureOpen();
        Vector4f fog = new Vector4f(fogColor.x(), fogColor.y(), fogColor.z(), 1.0F);
        boolean fullClear = this.fullClearRequired;
        for (int index = 0; index < colorTargets.targetCount(); index++) {
            RenderTargetSettings settings = targetSettings.get(index);
            if (!fullClear && (settings == null || !settings.shouldClear())) {
                continue;
            }
            Vector4fc clear = settings == null || settings.getClearColor().isEmpty()
                    ? defaultClearColor(index, fog)
                    : settings.getClearColor().get();
            encoder.clearColorTexture(colorTargets.mainTexture(index), clear);
            encoder.clearColorTexture(colorTargets.altTexture(index), clear);
        }
        this.fullClearRequired = false;
        return fullClear;
    }

    private static Vector4f defaultClearColor(final int index, final Vector4fc fogColor) {
        if (index == 0) {
            return new Vector4f(fogColor);
        }
        if (index == 1) {
            return new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
    }

    private void createDepthTextures(final int newWidth, final int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            throw new IllegalArgumentException("Target extent must be positive: " + newWidth + "x" + newHeight);
        }
        this.width = newWidth;
        this.height = newHeight;
        this.mainDepth = texture("iris-depthtex0", newWidth, newHeight);
        this.noTranslucentsDepth = texture("iris-depthtex1", newWidth, newHeight);
        this.noHandDepth = texture("iris-depthtex2", newWidth, newHeight);
        this.mainDepthView = new MetalGpuTextureView(this.mainDepth, 0, 1);
        this.noTranslucentsDepthView = new MetalGpuTextureView(this.noTranslucentsDepth, 0, 1);
        this.noHandDepthView = new MetalGpuTextureView(this.noHandDepth, 0, 1);
    }

    private MetalGpuTexture texture(final String label, final int textureWidth, final int textureHeight) {
        return (MetalGpuTexture) device.createTexture(
                label, DEPTH_USAGE, GpuFormat.D32_FLOAT, textureWidth, textureHeight, 1, 1
        );
    }

    IrisMetalPingPongTargets colorTargets() {
        ensureOpen();
        return colorTargets;
    }

    MetalGpuTexture mainDepthTexture() {
        ensureOpen();
        return mainDepth;
    }

    MetalGpuTexture noTranslucentsDepthTexture() {
        ensureOpen();
        return noTranslucentsDepth;
    }

    MetalGpuTexture noHandDepthTexture() {
        ensureOpen();
        return noHandDepth;
    }

    MetalGpuTextureView mainDepthView() {
        ensureOpen();
        return mainDepthView;
    }

    MetalGpuTextureView noTranslucentsDepthView() {
        ensureOpen();
        return noTranslucentsDepthView;
    }

    MetalGpuTextureView noHandDepthView() {
        ensureOpen();
        return noHandDepthView;
    }

    GpuSampler colorSampler() {
        ensureOpen();
        return colorSampler;
    }

    GpuSampler colorSampler(final int logicalTarget) {
        ensureOpen();
        String componentType = colorTargets.format(logicalTarget).componentType().name();
        boolean nearest = componentType.startsWith("UINT") || componentType.startsWith("SINT");
        boolean mipmapped = colorTargets.readMipmapsEnabled(logicalTarget);
        if (nearest) {
            return mipmapped ? nearestMipSampler : nearestSampler;
        }
        return mipmapped ? colorMipSampler : colorSampler;
    }

    void enableReadMipmaps(final int logicalTarget) {
        ensureOpen();
        colorTargets.enableReadMipmaps(logicalTarget);
    }

    void resetMipmaps() {
        ensureOpen();
        colorTargets.resetMipmaps();
    }

    GpuSampler depthSampler() {
        ensureOpen();
        return nearestSampler;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    void captureNoTranslucentsDepth(final MetalCommandEncoder encoder) {
        ensureOpen();
        encoder.copyTextureToTexture(mainDepth, noTranslucentsDepth, 0, 0, 0, 0, 0, width, height);
    }

    void captureNoTranslucentsDepth(final MetalCommandEncoder encoder, final GpuTexture sourceDepth) {
        ensureOpen();
        checkDepthExtent(sourceDepth);
        encoder.copyTextureToTexture(sourceDepth, noTranslucentsDepth, 0, 0, 0, 0, 0, width, height);
    }

    void captureMainDepth(final MetalCommandEncoder encoder, final GpuTexture sourceDepth) {
        ensureOpen();
        checkDepthExtent(sourceDepth);
        encoder.copyTextureToTexture(sourceDepth, mainDepth, 0, 0, 0, 0, 0, width, height);
    }

    void captureNoHandDepth(final MetalCommandEncoder encoder) {
        ensureOpen();
        encoder.copyTextureToTexture(mainDepth, noHandDepth, 0, 0, 0, 0, 0, width, height);
    }

    void captureNoHandDepth(final MetalCommandEncoder encoder, final GpuTexture sourceDepth) {
        ensureOpen();
        checkDepthExtent(sourceDepth);
        encoder.copyTextureToTexture(sourceDepth, noHandDepth, 0, 0, 0, 0, 0, width, height);
    }

    private void checkDepthExtent(final GpuTexture sourceDepth) {
        if (sourceDepth.getWidth(0) != width || sourceDepth.getHeight(0) != height) {
            throw new IllegalArgumentException(
                    "Scene depth extent " + sourceDepth.getWidth(0) + "x" + sourceDepth.getHeight(0)
                            + " does not match Iris targets " + width + "x" + height
            );
        }
    }

    RenderPassDescriptorWithViews createWriteDescriptor(
            final String label,
            final int[] drawBuffers,
            @Nullable final Vector4fc[] clearColors,
            final boolean withDepth,
            @Nullable final Double clearDepth,
            final int @Nullable [] readTargets
    ) {
        ensureOpen();
        if (drawBuffers.length == 0) {
            throw new IllegalArgumentException("A pass must write at least one draw buffer");
        }
        if (clearColors != null && clearColors.length != drawBuffers.length) {
            throw new IllegalArgumentException("Clear color array must match draw buffer count");
        }
        if (readTargets != null) {
            colorTargets.checkNoFeedbackLoop(drawBuffers, readTargets);
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        MetalGpuTextureView[] views = new MetalGpuTextureView[drawBuffers.length + (withDepth ? 1 : 0)];
        for (int slot = 0; slot < drawBuffers.length; slot++) {
            MetalGpuTextureView view = new MetalGpuTextureView(
                    colorTargets.writeTexture(drawBuffers[slot]), 0, 1
            );
            views[slot] = view;
            descriptor.withColorAttachment(
                    view,
                    clearColors == null || clearColors[slot] == null
                            ? Optional.empty()
                            : Optional.of(clearColors[slot])
            );
        }
        if (withDepth) {
            MetalGpuTextureView depthView = new MetalGpuTextureView(mainDepth, 0, 1);
            views[drawBuffers.length] = depthView;
            descriptor.withDepthAttachment(
                    depthView,
                    clearDepth == null ? OptionalDouble.empty() : OptionalDouble.of(clearDepth)
            );
        }
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        return new RenderPassDescriptorWithViews(descriptor, views);
    }

    RenderPassDescriptor createTerrainWriteDescriptor(
            final String label,
            final int[] drawBuffers,
            final GpuTextureView mainColor,
            @Nullable final Vector4fc mainClearColor,
            @Nullable final GpuTextureView sceneDepth,
            @Nullable final Double clearDepth
    ) {
        ensureOpen();
        if (drawBuffers.length == 0) {
            throw new IllegalArgumentException("A gbuffer pass must write at least one draw buffer");
        }
        if (mainColor.getWidth(0) != width || mainColor.getHeight(0) != height) {
            throw new IllegalArgumentException(
                    "Scene color extent " + mainColor.getWidth(0) + "x" + mainColor.getHeight(0)
                            + " does not match Iris targets " + width + "x" + height
            );
        }
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> label);
        boolean[] written = new boolean[colorTargets.targetCount()];
        for (int logicalTarget : drawBuffers) {
            if (logicalTarget < 0 || logicalTarget >= colorTargets.targetCount()) {
                throw new IllegalArgumentException("Terrain DRAWBUFFERS target out of range: " + logicalTarget);
            }
            if (written[logicalTarget]) {
                throw new IllegalArgumentException("Terrain DRAWBUFFERS repeats logical target " + logicalTarget);
            }
            written[logicalTarget] = true;
            Optional<Vector4fc> clear = logicalTarget == 0 && mainClearColor != null
                    ? Optional.of(mainClearColor)
                    : Optional.empty();
            descriptor.withColorAttachment(colorTargets.readView(logicalTarget), clear);
        }
        if (sceneDepth != null) {
            descriptor.withDepthAttachment(
                    sceneDepth,
                    clearDepth == null ? OptionalDouble.empty() : OptionalDouble.of(clearDepth)
            );
        }
        descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        return descriptor;
    }

    void resize(final int newWidth, final int newHeight) {
        ensureOpen();
        if (newWidth == width && newHeight == height) {
            return;
        }
        colorTargets.resize(newWidth, newHeight);
        releaseDepthTextures();
        createDepthTextures(newWidth, newHeight);
        this.fullClearRequired = true;
    }

    private void releaseDepthTextures() {
        if (mainDepthView != null) {
            mainDepthView.close();
            mainDepthView = null;
        }
        if (noTranslucentsDepthView != null) {
            noTranslucentsDepthView.close();
            noTranslucentsDepthView = null;
        }
        if (noHandDepthView != null) {
            noHandDepthView.close();
            noHandDepthView = null;
        }
        if (mainDepth != null) {
            mainDepth.close();
            mainDepth = null;
        }
        if (noTranslucentsDepth != null) {
            noTranslucentsDepth.close();
            noTranslucentsDepth = null;
        }
        if (noHandDepth != null) {
            noHandDepth.close();
            noHandDepth = null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Iris render targets are closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        colorTargets.close();
        releaseDepthTextures();
        colorSampler.close();
        nearestSampler.close();
        colorMipSampler.close();
        nearestMipSampler.close();
    }

    record RenderPassDescriptorWithViews(
            RenderPassDescriptor descriptor,
            MetalGpuTextureView[] views
    ) implements AutoCloseable {
        @Override
        public void close() {
            for (MetalGpuTextureView view : views) {
                if (view != null) {
                    view.close();
                }
            }
        }
    }
}
