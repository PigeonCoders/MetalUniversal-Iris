package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/** Metal-owned, stage-scoped implementation of Iris shader-pack custom textures. */
@Environment(EnvType.CLIENT)
final class IrisMetalCustomTextures implements AutoCloseable {
    private static final int USAGE = GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_COPY_SRC;

    private final MetalDevice device;
    private final EnumMap<TextureStage, Map<String, CustomTextureData>> definitions;
    private final Map<String, CustomTextureData> irisDefinitions;
    private final Map<Key, OwnedTexture> loaded = new HashMap<>();
    private final Map<String, OwnedTexture> irisLoaded = new HashMap<>();
    @Nullable
    private final ResourceTextureResolver resourceTextures;
    private boolean closed;

    IrisMetalCustomTextures(final MetalDevice device, final ShaderPack pack) {
        this(
                device,
                Objects.requireNonNull(pack, "pack").getCustomTextureDataMap(),
                pack.getIrisCustomTextureDataMap(),
                null
        );
    }

    /** Package-private map seam keeps focused tests independent of a complete shader-pack parse. */
    IrisMetalCustomTextures(
            final MetalDevice device,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> definitions
    ) {
        this(device, definitions, Map.of(), null);
    }

    /** Package-private global-custom-texture seam for {@code customtexN} sampler bindings. */
    IrisMetalCustomTextures(
            final MetalDevice device,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> definitions,
            final Map<String, ? extends CustomTextureData> irisDefinitions
    ) {
        this(device, definitions, irisDefinitions, null);
    }

    /** Package-private resolver seam lets tests observe borrowed {@link ResourceData} bindings. */
    IrisMetalCustomTextures(
            final MetalDevice device,
            final Map<TextureStage, ? extends Map<String, CustomTextureData>> definitions,
            final Map<String, ? extends CustomTextureData> irisDefinitions,
            final @Nullable ResourceTextureResolver resourceTextures
    ) {
        this.device = Objects.requireNonNull(device, "device");
        this.definitions = copyDefinitions(Objects.requireNonNull(definitions, "definitions"));
        this.irisDefinitions = copyIrisDefinitions(Objects.requireNonNull(irisDefinitions, "irisDefinitions"));
        this.resourceTextures = resourceTextures;
    }

    /**
     * Resolves the first stage-local sampler alias exactly as Iris's custom-texture interceptor does,
     * then falls back to the pack-global {@code customtexN} map that Iris's patcher rewrites raw
     * directives into. Callers must ask this layer before standard samplers so a matching directive
     * takes precedence.
     */
    synchronized MetalRenderPass.@Nullable TextureViewAndSampler resolve(
            final TextureStage stage,
            final String... samplerNames
    ) {
        ensureOpen();
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(samplerNames, "samplerNames");
        Map<String, CustomTextureData> stageDefinitions = this.definitions.get(stage);
        if (stageDefinitions != null) {
            for (String samplerName : samplerNames) {
                Objects.requireNonNull(samplerName, "samplerName");
                CustomTextureData data = stageDefinitions.get(samplerName);
                if (data != null) {
                    return resolveStage(stage, samplerName, data);
                }
            }
        }
        for (String samplerName : samplerNames) {
            Objects.requireNonNull(samplerName, "samplerName");
            CustomTextureData data = this.irisDefinitions.get(samplerName);
            if (data != null) {
                return resolveIris(stage, samplerName, data);
            }
        }
        return null;
    }

    private MetalRenderPass.TextureViewAndSampler resolveStage(
            final TextureStage stage,
            final String samplerName,
            final CustomTextureData data
    ) {
        if (data instanceof CustomTextureData.ResourceData resourceData) {
            // Borrowed from TextureManager: re-query every time so resource
            // reloads can never leave us holding a deleted vanilla texture.
            return createResourceBinding(stage, samplerName, resourceData);
        }
        Key key = new Key(stage, samplerName);
        OwnedTexture texture = this.loaded.get(key);
        if (texture == null) {
            texture = create(stage, samplerName, data);
            this.loaded.put(key, texture);
        }
        return texture.binding();
    }

    private MetalRenderPass.TextureViewAndSampler resolveIris(
            final TextureStage stage,
            final String samplerName,
            final CustomTextureData data
    ) {
        if (data instanceof CustomTextureData.ResourceData resourceData) {
            return createResourceBinding(null, samplerName, resourceData);
        }
        OwnedTexture texture = this.irisLoaded.get(samplerName);
        if (texture == null) {
            texture = create(null, samplerName, data);
            this.irisLoaded.put(samplerName, texture);
        }
        return texture.binding();
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
     * Materializes every owned (PNG / raw) declaration before any render encoder is live.
     * Resource declarations are only validated here; their bindings are borrowed lazily.
     */
    synchronized void prewarmAll() {
        ensureOpen();
        for (Map.Entry<TextureStage, Map<String, CustomTextureData>> stage : this.definitions.entrySet()) {
            for (Map.Entry<String, CustomTextureData> entry : stage.getValue().entrySet()) {
                prewarm(stage.getKey(), entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<String, CustomTextureData> entry : this.irisDefinitions.entrySet()) {
            prewarm(null, entry.getKey(), entry.getValue());
        }
    }

    private void prewarm(
            final @Nullable TextureStage stage,
            final String samplerName,
            final CustomTextureData data
    ) {
        if (data instanceof CustomTextureData.ResourceData resourceData) {
            validateResource(stage, samplerName, resourceData);
        } else if (stage == null) {
            resolveIris(null, samplerName, data);
        } else {
            resolveStage(stage, samplerName, data);
        }
    }

    private OwnedTexture create(
            final @Nullable TextureStage stage,
            final String samplerName,
            final @Nullable CustomTextureData data
    ) {
        if (data instanceof CustomTextureData.PngData png) {
            return createPng(stage, samplerName, png);
        }
        if (data instanceof CustomTextureData.RawData3D rawData3D) {
            return createRaw(
                    stage, samplerName, rawData3D,
                    rawData3D.getSizeX(), rawData3D.getSizeY(), rawData3D.getSizeZ(), true
            );
        }
        if (data instanceof CustomTextureData.RawData2D rawData2D
                && !(data instanceof CustomTextureData.RawDataRect)) {
            return createRaw(
                    stage, samplerName, rawData2D,
                    rawData2D.getSizeX(), rawData2D.getSizeY(), 1, false
            );
        }
        String type = data == null ? "null" : data.getClass().getSimpleName();
        throw new UnsupportedOperationException(
                "Unsupported Iris custom texture on Metal: stage=" + stageDescription(stage)
                        + ", sampler=" + samplerName + ", type=" + type
        );
    }

    private OwnedTexture createPng(
            final @Nullable TextureStage stage,
            final String samplerName,
            final CustomTextureData.PngData png
    ) {
        NativeImage image;
        try {
            image = NativeImage.read(png.getContent());
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Failed to decode Iris custom texture PNG: stage=" + stageDescription(stage)
                            + ", sampler=" + samplerName + ", type=PngData",
                    exception
            );
        }

        MetalGpuTexture texture = null;
        MetalGpuTextureView view = null;
        MetalGpuSampler sampler = null;
        try (image) {
            texture = (MetalGpuTexture) this.device.createTexture(
                    customTextureLabel(stage, samplerName),
                    USAGE,
                    GpuFormat.RGBA8_UNORM,
                    image.getWidth(),
                    image.getHeight(),
                    1,
                    1
            );
            view = (MetalGpuTextureView) this.device.createTextureView(texture);
            sampler = customSampler(png.getFilteringData());

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
            return new OwnedTexture(texture, view, sampler);
        } catch (RuntimeException | Error failure) {
            closePartial(texture, view, sampler);
            throw failure;
        }
    }

    private OwnedTexture createRaw(
            final @Nullable TextureStage stage,
            final String samplerName,
            final CustomTextureData.RawData rawData,
            final int width,
            final int height,
            final int depth,
            final boolean texture3D
    ) {
        GpuFormat format = rawGpuFormat(stage, samplerName, rawData);
        MetalGpuTexture texture = null;
        MetalGpuTextureView view = null;
        MetalGpuSampler sampler = null;
        try {
            texture = new MetalGpuTexture(
                    this.device,
                    USAGE,
                    customTextureLabel(stage, samplerName),
                    format,
                    width,
                    height,
                    depth,
                    1,
                    texture3D
            );
            view = (MetalGpuTextureView) this.device.createTextureView(texture);
            sampler = customSampler(rawData.getFilteringData());

            ByteBuffer pixels = ByteBuffer
                    .allocateDirect(rawData.getContent().length)
                    .order(ByteOrder.nativeOrder());
            pixels.put(rawData.getContent());
            pixels.flip();
            this.device.createCommandEncoder().writeToTexture(
                    texture,
                    pixels,
                    0,
                    0,
                    0,
                    0,
                    width,
                    height
            );
            return new OwnedTexture(texture, view, sampler);
        } catch (RuntimeException | Error failure) {
            closePartial(texture, view, sampler);
            throw failure;
        }
    }

    private MetalRenderPass.TextureViewAndSampler createResourceBinding(
            final @Nullable TextureStage stage,
            final String samplerName,
            final CustomTextureData.ResourceData resourceData
    ) {
        validateResource(stage, samplerName, resourceData);
        Identifier location = resourceIdentifier(resourceData);

        AbstractTexture texture;
        try {
            texture = this.resourceTextures == null
                    ? vanillaTexture(location)
                    : this.resourceTextures.resolve(location);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Failed to resolve Iris resource custom texture on Metal: stage=" + stageDescription(stage)
                            + ", sampler=" + samplerName + ", location=" + location,
                    failure
            );
        }
        try {
            return new MetalRenderPass.TextureViewAndSampler(texture.getTextureView(), texture.getSampler());
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "Iris resource custom texture is not initialized on Metal: stage=" + stageDescription(stage)
                            + ", sampler=" + samplerName + ", location=" + location,
                    failure
            );
        }
    }

    private static void validateResource(
            final @Nullable TextureStage stage,
            final String samplerName,
            final CustomTextureData.ResourceData resourceData
    ) {
        String location = resourceData.getLocation();
        String withoutExtension = withoutExtension(location);
        if (withoutExtension.endsWith("_n") || withoutExtension.endsWith("_s")) {
            throw new UnsupportedOperationException(
                    "Unsupported Iris PBR custom texture on Metal: stage=" + stageDescription(stage)
                            + ", sampler=" + samplerName + ", type=ResourceData(PBR), location="
                            + resourceData.getNamespace() + ":" + location
            );
        }
        resourceIdentifier(resourceData);
    }

    private static Identifier resourceIdentifier(final CustomTextureData.ResourceData resourceData) {
        try {
            return Identifier.fromNamespaceAndPath(resourceData.getNamespace(), resourceData.getLocation());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    "Invalid Iris resource custom texture identifier: "
                            + resourceData.getNamespace() + ":" + resourceData.getLocation(),
                    failure
            );
        }
    }

    private static AbstractTexture vanillaTexture(final Identifier location) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            throw new IllegalStateException(
                    "Cannot resolve an Iris resource custom texture before Minecraft is initialized: " + location
            );
        }
        return minecraft.getTextureManager().getTexture(location);
    }

    private MetalGpuSampler customSampler(final TextureFilteringData filteringData) {
        boolean clamp = filteringData.shouldClamp();
        boolean blur = filteringData.shouldBlur();
        AddressMode addressMode = clamp ? AddressMode.CLAMP_TO_EDGE : AddressMode.REPEAT;
        FilterMode filterMode = blur ? FilterMode.LINEAR : FilterMode.NEAREST;
        return new MetalGpuSampler(
                this.device,
                addressMode,
                addressMode,
                filterMode,
                filterMode,
                1,
                OptionalDouble.of(0.0)
        );
    }

    private static String customTextureLabel(final @Nullable TextureStage stage, final String samplerName) {
        String stageName = stage == null ? "global" : stage.name().toLowerCase(Locale.ROOT);
        return "metallum:iris_custom/" + stageName + "/" + samplerName;
    }

    private static String stageDescription(final @Nullable TextureStage stage) {
        return stage == null ? "global" : stage.name();
    }

    private static GpuFormat rawGpuFormat(
            final @Nullable TextureStage stage,
            final String samplerName,
            final CustomTextureData.RawData rawData
    ) {
        String type = rawData.getClass().getSimpleName();
        GpuFormat format = toGpuFormat(rawData.getInternalFormat());
        if (format == null) {
            throw new UnsupportedOperationException(
                    "Unsupported Iris raw custom texture on Metal: stage=" + stageDescription(stage)
                            + ", sampler=" + samplerName + ", type=" + type
                            + ", internalFormat=" + rawData.getInternalFormat().name()
                            + ", pixelFormat=" + rawData.getPixelFormat().name()
                            + ", pixelType=" + rawData.getPixelType().name()
            );
        }
        long expectedBytesPerTexel = (long) rawData.getPixelFormat().getComponentCount()
                * rawData.getPixelType().getByteSize();
        if (expectedBytesPerTexel != format.blockSize()) {
            throw new IllegalArgumentException(
                    "Iris raw custom texture texel layout does not match its Metal format: stage="
                            + stageDescription(stage)
                            + ", sampler=" + samplerName + ", type=" + type
                            + ", internalFormat=" + rawData.getInternalFormat().name()
                            + ", pixelFormat=" + rawData.getPixelFormat().name()
                            + ", pixelType=" + rawData.getPixelType().name()
                            + ", expectedBytesPerTexel=" + expectedBytesPerTexel
                            + ", metalBlockSize=" + format.blockSize()
            );
        }
        if (!hasCompatibleChannelOrder(rawData.getPixelFormat())) {
            throw new UnsupportedOperationException(
                    "Unsupported Iris raw custom texture channel order on Metal: stage=" + stageDescription(stage)
                            + ", sampler=" + samplerName + ", type=" + type
                            + ", pixelFormat=" + rawData.getPixelFormat().name()
            );
        }
        return format;
    }

    private static boolean hasCompatibleChannelOrder(final PixelFormat pixelFormat) {
        return switch (pixelFormat) {
            case RED, RED_INTEGER, RG, RG_INTEGER, RGBA, RGBA_INTEGER -> true;
            // BGR / BGRA / RGB and integer variants order channels differently
            // than Metal's R/RG/RGBA formats; uploading them unchanged would
            // silently swap channels, so fail loudly instead.
            default -> false;
        };
    }

    /**
     * Maps Iris's GL internal formats to Blaze3D {@link GpuFormat}s backed by
     * Apple-GPU-supported Metal pixel formats. Returns {@code null} for GL
     * formats without a Metal equivalent (3-component and packed legacy
     * formats).
     */
    private static @Nullable GpuFormat toGpuFormat(final InternalTextureFormat internalFormat) {
        return switch (internalFormat) {
            case RGBA, RGBA8 -> GpuFormat.RGBA8_UNORM;
            case R8 -> GpuFormat.R8_UNORM;
            case RG8 -> GpuFormat.RG8_UNORM;
            case R8_SNORM -> GpuFormat.R8_SNORM;
            case RG8_SNORM -> GpuFormat.RG8_SNORM;
            case RGBA8_SNORM -> GpuFormat.RGBA8_SNORM;
            case R16 -> GpuFormat.R16_UNORM;
            case RG16 -> GpuFormat.RG16_UNORM;
            case RGBA16 -> GpuFormat.RGBA16_UNORM;
            case R16_SNORM -> GpuFormat.R16_SNORM;
            case RG16_SNORM -> GpuFormat.RG16_SNORM;
            case RGBA16_SNORM -> GpuFormat.RGBA16_SNORM;
            case R16F -> GpuFormat.R16_FLOAT;
            case RG16F -> GpuFormat.RG16_FLOAT;
            case RGBA16F -> GpuFormat.RGBA16_FLOAT;
            case R32F -> GpuFormat.R32_FLOAT;
            case RG32F -> GpuFormat.RG32_FLOAT;
            case RGBA32F -> GpuFormat.RGBA32_FLOAT;
            case R8I -> GpuFormat.R8_SINT;
            case RG8I -> GpuFormat.RG8_SINT;
            case RGBA8I -> GpuFormat.RGBA8_SINT;
            case R8UI -> GpuFormat.R8_UINT;
            case RG8UI -> GpuFormat.RG8_UINT;
            case RGBA8UI -> GpuFormat.RGBA8_UINT;
            case R16I -> GpuFormat.R16_SINT;
            case RG16I -> GpuFormat.RG16_SINT;
            case RGBA16I -> GpuFormat.RGBA16_SINT;
            case R16UI -> GpuFormat.R16_UINT;
            case RG16UI -> GpuFormat.RG16_UINT;
            case RGBA16UI -> GpuFormat.RGBA16_UINT;
            case R32I -> GpuFormat.R32_SINT;
            case RG32I -> GpuFormat.RG32_SINT;
            case RGBA32I -> GpuFormat.RGBA32_SINT;
            case R32UI -> GpuFormat.R32_UINT;
            case RG32UI -> GpuFormat.RG32_UINT;
            case RGBA32UI -> GpuFormat.RGBA32_UINT;
            default -> null;
        };
    }

    private static String withoutExtension(final String location) {
        int slash = Math.max(location.lastIndexOf('/'), location.lastIndexOf('\\'));
        int dot = location.lastIndexOf('.');
        return dot > slash ? location.substring(0, dot) : location;
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

    private static Map<String, CustomTextureData> copyIrisDefinitions(
            final Map<String, ? extends CustomTextureData> source
    ) {
        LinkedHashMap<String, CustomTextureData> copy = new LinkedHashMap<>();
        source.forEach((name, data) -> copy.put(
                Objects.requireNonNull(name, "iris custom texture sampler"),
                data
        ));
        return Collections.unmodifiableMap(copy);
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
        this.loaded.values().forEach(OwnedTexture::close);
        this.loaded.clear();
        this.irisLoaded.values().forEach(OwnedTexture::close);
        this.irisLoaded.clear();
    }

    private record Key(TextureStage stage, String samplerName) {
    }

    @FunctionalInterface
    interface ResourceTextureResolver {
        AbstractTexture resolve(Identifier location);
    }

    private static final class OwnedTexture implements AutoCloseable {
        private final MetalGpuTexture texture;
        private final MetalGpuTextureView view;
        private final MetalGpuSampler sampler;

        private OwnedTexture(
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
