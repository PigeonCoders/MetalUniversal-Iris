package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/** Metal-owned, stage-scoped implementation of Iris shader-pack custom textures. */
@Environment(EnvType.CLIENT)
final class IrisMetalCustomTextures implements AutoCloseable {
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_COPY_SRC;

    private final MetalDevice device;
    private final EnumMap<TextureStage, Map<String, CustomTextureData>> definitions;
    private final Map<Key, OwnedPng> loaded = new HashMap<>();
    private boolean closed;

    IrisMetalCustomTextures(final MetalDevice device, final ShaderPack pack) {
        this(device, Objects.requireNonNull(pack, "pack").getCustomTextureDataMap());
    }

    /** Package-private map seam keeps focused tests independent of a complete shader-pack parse. */
    IrisMetalCustomTextures(
            final MetalDevice device,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> definitions
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.definitions = copyDefinitions(Objects.requireNonNull(definitions, "definitions"));
    }

    /**
     * Resolves the first stage-local sampler alias exactly as Iris's custom-texture interceptor does.
     * Callers must ask this layer before standard samplers so a matching directive takes precedence.
     */
    synchronized MetalRenderPass.@Nullable TextureViewAndSampler resolve(
            final TextureStage stage,
            final String... samplerNames
    ) {
        ensureOpen();
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(samplerNames, "samplerNames");
        Map<String, CustomTextureData> stageDefinitions = this.definitions.get(stage);
        if (stageDefinitions == null) {
            return null;
        }
        for (String samplerName : samplerNames) {
            Objects.requireNonNull(samplerName, "samplerName");
            if (!stageDefinitions.containsKey(samplerName)) {
                continue;
            }
            Key key = new Key(stage, samplerName);
            OwnedPng texture = this.loaded.get(key);
            if (texture == null) {
                texture = create(stage, samplerName, stageDefinitions.get(samplerName));
                this.loaded.put(key, texture);
            }
            return texture.binding();
        }
        return null;
    }

    /** Returns the stage override when present, otherwise the caller's standard binding. */
    synchronized MetalRenderPass.@Nullable TextureViewAndSampler overrideOrDefault(
            final TextureStage stage,
            final MetalRenderPass.@Nullable TextureViewAndSampler standard,
            final String... samplerNames
    ) {
        MetalRenderPass.TextureViewAndSampler override = resolve(stage, samplerNames);
        return override == null ? standard : override;
    }

    synchronized boolean hasOverride(final TextureStage stage, final String samplerName) {
        ensureOpen();
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(samplerName, "samplerName");
        Map<String, CustomTextureData> stageDefinitions = this.definitions.get(stage);
        return stageDefinitions != null && stageDefinitions.containsKey(samplerName);
    }

    /**
     * Applies the custom-texture precedence to a name-based sampler fallback:
     * a custom stage texture always beats the standard/legacy binding, and a
     * miss defers to the fallback untouched. Upstream implements the same
     * precedence in {@code ProgramSamplers.CustomTextureSamplerInterceptor},
     * which substitutes the override for every {@code addDynamicSampler} call
     * whose name matches, before the standard binding is recorded.
     */
    static MetalRenderPass.@Nullable TextureViewAndSampler overrideFirst(
            final MetalRenderPass.@Nullable TextureViewAndSampler custom,
            final Supplier<MetalRenderPass.@Nullable TextureViewAndSampler> fallback
    ) {
        Objects.requireNonNull(fallback, "fallback");
        return custom != null ? custom : fallback.get();
    }

    /** Materializes every declared PNG before any render encoder is live. */
    synchronized void prewarmAll() {
        ensureOpen();
        for (Map.Entry<TextureStage, Map<String, CustomTextureData>> stage : this.definitions.entrySet()) {
            for (String samplerName : stage.getValue().keySet()) {
                resolve(stage.getKey(), samplerName);
            }
        }
    }

    private OwnedPng create(
            final TextureStage stage,
            final String samplerName,
            final @Nullable CustomTextureData data
    ) {
        if (!(data instanceof CustomTextureData.PngData png)) {
            String type = data == null ? "null" : data.getClass().getSimpleName();
            throw new UnsupportedOperationException(
                    "Unsupported Iris custom texture on Metal: stage=" + stage
                            + ", sampler=" + samplerName + ", type=" + type
            );
        }

        NativeImage image;
        try {
            image = NativeImage.read(png.getContent());
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Failed to decode Iris custom texture PNG: stage=" + stage
                            + ", sampler=" + samplerName + ", type=PngData",
                    exception
            );
        }

        MetalGpuTexture texture = null;
        MetalGpuTextureView view = null;
        MetalGpuSampler sampler = null;
        try (image) {
            texture = (MetalGpuTexture) this.device.createTexture(
                    "metallum:iris_custom/" + stage.name().toLowerCase(Locale.ROOT) + "/" + samplerName,
                    USAGE,
                    GpuFormat.RGBA8_UNORM,
                    image.getWidth(),
                    image.getHeight(),
                    1,
                    1
            );
            view = (MetalGpuTextureView) this.device.createTextureView(texture);
            boolean clamp = png.getFilteringData().shouldClamp();
            boolean blur = png.getFilteringData().shouldBlur();
            AddressMode addressMode = clamp ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
            FilterMode filterMode = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
            sampler = new MetalGpuSampler(
                    this.device,
                    addressMode,
                    addressMode,
                    filterMode,
                    filterMode,
                    1,
                    OptionalDouble.of(0.0)
            );

            ByteBuffer pixels = image.getPixelBytes().duplicate();
            pixels.position(0);
            this.device.createCommandEncoder().writeToTexture(
                    texture,
                    pixels,
                    0,
                    0,
                    0,
                    0,
                    image.getWidth(),
                    image.getHeight()
            );
            return new OwnedPng(texture, view, sampler);
        } catch (RuntimeException | Error failure) {
            closePartial(texture, view, sampler);
            throw failure;
        }
    }

    private static EnumMap<TextureStage, Map<String, CustomTextureData>> copyDefinitions(
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> source
    ) {
        EnumMap<TextureStage, Map<String, CustomTextureData>> copy = new EnumMap<>(TextureStage.class);
        source.forEach((stage, entries) -> {
            Objects.requireNonNull(stage, "custom texture stage");
            Objects.requireNonNull(entries, "custom textures for stage " + stage);
            LinkedHashMap<String, CustomTextureData> stageCopy = new LinkedHashMap<>();
            entries.forEach((name, data) -> stageCopy.put(
                    Objects.requireNonNull(name, "custom texture sampler for stage " + stage),
                    data
            ));
            copy.put(stage, Collections.unmodifiableMap(stageCopy));
        });
        return copy;
    }

    private static void closePartial(
            final @Nullable MetalGpuTexture texture,
            final @Nullable MetalGpuTextureView view,
            final @Nullable MetalGpuSampler sampler
    ) {
        if (view != null) {
            view.close();
        }
        if (texture != null) {
            texture.close();
        }
        if (sampler != null) {
            sampler.close();
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Iris Metal custom textures are closed");
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.loaded.values().forEach(OwnedPng::close);
        this.loaded.clear();
    }

    private record Key(TextureStage stage, String samplerName) {
    }

    private static final class OwnedPng implements AutoCloseable {
        private final MetalGpuTexture texture;
        private final MetalGpuTextureView view;
        private final MetalGpuSampler sampler;

        private OwnedPng(
                final MetalGpuTexture texture,
                final MetalGpuTextureView view,
                final MetalGpuSampler sampler
        ) {
            this.texture = texture;
            this.view = view;
            this.sampler = sampler;
        }

        private MetalRenderPass.TextureViewAndSampler binding() {
            return new MetalRenderPass.TextureViewAndSampler(this.view, this.sampler);
        }

        @Override
        public void close() {
            this.view.close();
            this.texture.close();
            this.sampler.close();
        }
    }
}
