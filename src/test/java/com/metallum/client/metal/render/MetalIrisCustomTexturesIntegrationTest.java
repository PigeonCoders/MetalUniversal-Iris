package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** GPU and lifecycle coverage for stage-scoped Iris custom texture overrides. */
@EnabledOnOs(OS.MAC)
final class MetalIrisCustomTexturesIntegrationTest {
    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice));
        device = new MetalDevice(
                (identifier, type) -> null,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "Iris custom textures integration device",
                MemorySegment.NULL
        );
        encoder = device.createCommandEncoder();
    }

    @AfterEach
    void closeDevice() {
        if (device != null) {
            device.close();
        }
    }

    @Test
    void pngOverridePreservesPixelsAndFiltering() throws IOException {
        EnumMap<TextureStage, Object2ObjectOpenHashMap<String, CustomTextureData>> definitions = definitions(
                TextureStage.COMPOSITE_AND_FINAL,
                "colortex7",
                png(false, true, 0xFFFF0000, 0x400080FF)
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(device, definitions)) {
            MetalRenderPass.TextureViewAndSampler binding =
                    textures.resolve(TextureStage.COMPOSITE_AND_FINAL, "colortex7");
            assertNotNull(binding);
            ByteBuffer pixels = readback((MetalGpuTexture) binding.textureView().texture());
            assertPixel(pixels, 0, 255, 0, 0, 255);
            assertPixel(pixels, 1, 0, 128, 255, 64);
            assertEquals(AddressMode.CLAMP_TO_EDGE, binding.sampler().getAddressModeU());
            assertEquals(AddressMode.CLAMP_TO_EDGE, binding.sampler().getAddressModeV());
            assertEquals(FilterMode.NEAREST, binding.sampler().getMinFilter());
            assertEquals(FilterMode.NEAREST, binding.sampler().getMagFilter());
        }
    }

    @Test
    void stageIsolationAndAliasOrderPreserveOverridePrecedence() throws IOException {
        EnumMap<TextureStage, Object2ObjectOpenHashMap<String, CustomTextureData>> definitions = definitions(
                TextureStage.COMPOSITE_AND_FINAL,
                "colortex7",
                png(true, true, 0xFF00FF00)
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(device, definitions);
             IrisMetalCustomTextures standards = new IrisMetalCustomTextures(
                     device,
                     definitions(TextureStage.BEGIN, "standardSampler", png(false, false, 0xFFFFFFFF))
             )) {
            MetalRenderPass.TextureViewAndSampler standardBinding =
                    standards.resolve(TextureStage.BEGIN, "standardSampler");
            assertNotNull(standardBinding);

            assertSame(
                    standardBinding,
                    textures.overrideOrDefault(TextureStage.DEFERRED, standardBinding, "colortex7"),
                    "an override from another stage must not leak"
            );
            assertSame(
                    standardBinding,
                    textures.overrideOrDefault(
                            TextureStage.COMPOSITE_AND_FINAL,
                            standardBinding,
                            "missingAlias",
                            "alsoMissing"
                    )
            );

            MetalRenderPass.TextureViewAndSampler override = textures.overrideOrDefault(
                    TextureStage.COMPOSITE_AND_FINAL,
                    standardBinding,
                    "missingAlias",
                    "colortex7"
            );
            assertNotNull(override);
            assertNotSame(standardBinding, override, "same-stage custom sampler must override the standard binding");
            assertEquals(FilterMode.LINEAR, override.sampler().getMinFilter());
            assertTrue(textures.hasOverride(TextureStage.COMPOSITE_AND_FINAL, "colortex7"));
            assertFalse(textures.hasOverride(TextureStage.DEFERRED, "colortex7"));
        }
    }

    @Test
    void closeReleasesEveryMaterializedResourceAndIsIdempotent() throws IOException {
        IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device,
                definitions(TextureStage.BEGIN, "customSampler", png(false, false, 0xFFFFFFFF))
        );
        MetalRenderPass.TextureViewAndSampler binding = textures.resolve(TextureStage.BEGIN, "customSampler");
        assertNotNull(binding);
        MetalGpuTexture texture = (MetalGpuTexture) binding.textureView().texture();
        MetalGpuSampler sampler = (MetalGpuSampler) binding.sampler();

        textures.close();
        textures.close();

        assertTrue(binding.textureView().isClosed());
        assertTrue(texture.isClosed());
        assertTrue(sampler.isClosed());
        assertThrows(
                IllegalStateException.class,
                () -> textures.resolve(TextureStage.BEGIN, "customSampler")
        );
    }

    @Test
    void resourceDataBorrowsTextureManagerBindingWithoutOwningIt() {
        GpuTexture texture = device.createTexture(
                () -> "borrowed iris resource texture",
                GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                1,
                1,
                1,
                1
        );
        GpuTextureView view = device.createTextureView(texture);
        GpuSampler sampler = device.createSampler(
                AddressMode.REPEAT, AddressMode.REPEAT,
                FilterMode.LINEAR, FilterMode.LINEAR,
                1, OptionalDouble.of(0.0)
        );
        AbstractTexture vanillaTexture = new BorrowedTestTexture(texture, view, sampler);
        CustomTextureData.ResourceData data = new CustomTextureData.ResourceData(
                "minecraft", "textures/environment/clouds.png"
        );

        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device,
                definitions(TextureStage.DEFERRED, "colortex3", data),
                Map.of(),
                location -> {
                    assertEquals(
                            Identifier.fromNamespaceAndPath("minecraft", "textures/environment/clouds.png"),
                            location
                    );
                    return vanillaTexture;
                }
        )) {
            MetalRenderPass.TextureViewAndSampler binding =
                    textures.resolve(TextureStage.DEFERRED, "colortex3");
            assertNotNull(binding);
            assertSame(view, binding.textureView());
            assertSame(sampler, binding.sampler());
        }

        assertFalse(((MetalGpuTexture) texture).isClosed(), "borrowed vanilla texture must not be closed");
        assertFalse(((MetalGpuTextureView) view).isClosed(), "borrowed vanilla texture view must not be closed");
        assertFalse(((MetalGpuSampler) sampler).isClosed(), "borrowed vanilla sampler must not be closed");
    }

    @Test
    void resourceDataReQueriesTextureManagerForEveryBind() {
        AtomicInteger calls = new AtomicInteger();
        AbstractTexture first = borrowedTexture();
        AbstractTexture second = borrowedTexture();
        CustomTextureData.ResourceData data = new CustomTextureData.ResourceData(
                "minecraft", "textures/environment/clouds.png"
        );

        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device,
                definitions(TextureStage.DEFERRED, "colortex3", data),
                Map.of(),
                location -> calls.incrementAndGet() == 1 ? first : second
        )) {
            MetalRenderPass.TextureViewAndSampler firstBinding =
                    textures.resolve(TextureStage.DEFERRED, "colortex3");
            MetalRenderPass.TextureViewAndSampler secondBinding =
                    textures.resolve(TextureStage.DEFERRED, "colortex3");
            assertEquals(2, calls.get());
            assertNotSame(
                    firstBinding.textureView(), secondBinding.textureView(),
                    "resource custom textures must be re-queried so reloads are visible"
            );
        }
    }

    @Test
    void pbrResourceDataFailsWithItsLocationWithoutTouchingTextureManager() {
        CustomTextureData.ResourceData pbr = new CustomTextureData.ResourceData(
                "minecraft", "textures/block/dirt_n.png"
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device,
                definitions(TextureStage.DEFERRED, "colortex3", pbr)
        )) {
            UnsupportedOperationException failure = assertThrows(
                    UnsupportedOperationException.class,
                    () -> textures.resolve(TextureStage.DEFERRED, "colortex3")
            );
            assertTrue(failure.getMessage().contains("type=ResourceData(PBR)"));
            assertTrue(failure.getMessage().contains("location=minecraft:textures/block/dirt_n.png"));
        }
    }

    @Test
    void raw3DTexturesPreservePixelsAndBindAs3D() {
        byte[] content = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        CustomTextureData.RawData3D data = new CustomTextureData.RawData3D(
                content, filtering(), InternalTextureFormat.R8,
                PixelFormat.RED, PixelType.UNSIGNED_BYTE, 4, 2, 2
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device,
                definitions(TextureStage.DEFERRED, "colortex6", data)
        )) {
            MetalRenderPass.TextureViewAndSampler binding =
                    textures.resolve(TextureStage.DEFERRED, "colortex6");
            assertNotNull(binding);
            MetalGpuTexture texture = (MetalGpuTexture) binding.textureView().texture();
            assertTrue(texture.isTexture3D());
            assertEquals(2, texture.getDepthOrLayers());
            assertEquals(GpuFormat.R8_UNORM, texture.getFormat());
            assertEquals(AddressMode.REPEAT, binding.sampler().getAddressModeU());
            assertEquals(FilterMode.NEAREST, binding.sampler().getMinFilter());

            ByteBuffer pixels = readback(texture);
            for (int index = 0; index < content.length; index++) {
                assertEquals(
                        Byte.toUnsignedInt(content[index]),
                        Byte.toUnsignedInt(pixels.get(index)),
                        "3D texel at " + index
                );
            }
        }
    }

    @Test
    void raw2DTexturesPreservePixelsAndBindAs2D() {
        byte[] content = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        CustomTextureData.RawData2D data = new CustomTextureData.RawData2D(
                content, filtering(), InternalTextureFormat.RGBA8,
                PixelFormat.RGBA, PixelType.UNSIGNED_BYTE, 4, 1
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device,
                definitions(TextureStage.DEFERRED, "colortex7", data)
        )) {
            MetalRenderPass.TextureViewAndSampler binding =
                    textures.resolve(TextureStage.DEFERRED, "colortex7");
            assertNotNull(binding);
            MetalGpuTexture texture = (MetalGpuTexture) binding.textureView().texture();
            assertFalse(texture.isTexture3D());
            assertEquals(GpuFormat.RGBA8_UNORM, texture.getFormat());

            ByteBuffer pixels = readback(texture);
            assertPixel(pixels, 0, 1, 2, 3, 4);
            assertPixel(pixels, 1, 5, 6, 7, 8);
            assertPixel(pixels, 2, 9, 10, 11, 12);
            assertPixel(pixels, 3, 13, 14, 15, 16);
        }
    }

    @Test
    void irisGlobalRaw3DTextureBindsUnderPatchedSamplerName() {
        byte[] content = {7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22};
        CustomTextureData.RawData3D data = new CustomTextureData.RawData3D(
                content, filtering(), InternalTextureFormat.R8,
                PixelFormat.RED, PixelType.UNSIGNED_BYTE, 4, 2, 2
        );
        try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                device,
                Map.of(),
                Map.of("customtex0", data)
        )) {
            MetalRenderPass.TextureViewAndSampler binding =
                    textures.resolve(TextureStage.DEFERRED, "customtex0");
            assertNotNull(binding);
            MetalGpuTexture texture = (MetalGpuTexture) binding.textureView().texture();
            assertTrue(texture.isTexture3D());
            assertEquals(GpuFormat.R8_UNORM, texture.getFormat());

            ByteBuffer pixels = readback(texture);
            for (int index = 0; index < content.length; index++) {
                assertEquals(
                        Byte.toUnsignedInt(content[index]),
                        Byte.toUnsignedInt(pixels.get(index)),
                        "iris global 3D texel at " + index
                );
            }

            textures.close();
            assertTrue(texture.isClosed(), "iris global custom texture must be owned and retired");
            assertTrue(binding.textureView().isClosed());
            assertTrue(((MetalGpuSampler) binding.sampler()).isClosed());
        }
    }

    @Test
    void unsupportedKindsFailClosedOnlyWhenTheirStageSamplerIsRequested() {
        List<CustomTextureData> unsupported = List.of(
                new CustomTextureData.LightmapMarker(),
                new CustomTextureData.RawData1D(
                        new byte[4], filtering(), InternalTextureFormat.RGBA8,
                        PixelFormat.RGBA, PixelType.UNSIGNED_BYTE, 1
                ),
                new CustomTextureData.RawData2D(
                        new byte[4], filtering(), InternalTextureFormat.RGB8,
                        PixelFormat.RGB, PixelType.UNSIGNED_BYTE, 1, 1
                ),
                new CustomTextureData.RawDataRect(
                        new byte[4], filtering(), InternalTextureFormat.RGBA8,
                        PixelFormat.RGBA, PixelType.UNSIGNED_BYTE, 1, 1
                )
        );

        for (CustomTextureData data : unsupported) {
            String type = data.getClass().getSimpleName();
            try (IrisMetalCustomTextures textures = new IrisMetalCustomTextures(
                    device,
                    definitions(TextureStage.SHADOWCOMP, "requiredInput", data)
            )) {
                assertNull(
                        textures.resolve(TextureStage.DEFERRED, "requiredInput"),
                        "unused stage-scoped unsupported data must not block pack load"
                );
                assertNull(
                        textures.resolve(TextureStage.SHADOWCOMP, "unreferencedInput"),
                        "unreferenced unsupported sampler must remain lazy"
                );

                UnsupportedOperationException failure = assertThrows(
                        UnsupportedOperationException.class,
                        () -> textures.resolve(TextureStage.SHADOWCOMP, "requiredInput")
                );
                assertTrue(failure.getMessage().contains("stage=SHADOWCOMP"));
                assertTrue(failure.getMessage().contains("sampler=requiredInput"));
                assertTrue(failure.getMessage().contains("type=" + type));
            }
        }
    }

    private AbstractTexture borrowedTexture() {
        GpuTexture texture = device.createTexture(
                () -> "borrowed iris resource texture",
                GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                1,
                1,
                1,
                1
        );
        GpuTextureView view = device.createTextureView(texture);
        GpuSampler sampler = device.createSampler(
                AddressMode.REPEAT, AddressMode.REPEAT,
                FilterMode.NEAREST, FilterMode.NEAREST,
                1, OptionalDouble.of(0.0)
        );
        return new BorrowedTestTexture(texture, view, sampler);
    }

    private ByteBuffer readback(final MetalGpuTexture texture) {
        int depth = texture.isTexture3D() ? texture.getDepthOrLayers() : 1;
        int size = texture.getWidth(0) * texture.getHeight(0) * depth * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "iris custom texture readback",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                size
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer source = buffer.currentStorage().limit(size).slice().order(ByteOrder.nativeOrder());
            ByteBuffer copy = ByteBuffer.allocate(size);
            copy.put(source);
            copy.flip();
            return copy;
        }
    }

    private static EnumMap<TextureStage, Object2ObjectOpenHashMap<String, CustomTextureData>> definitions(
            final TextureStage stage,
            final String sampler,
            final CustomTextureData data
    ) {
        EnumMap<TextureStage, Object2ObjectOpenHashMap<String, CustomTextureData>> definitions =
                new EnumMap<>(TextureStage.class);
        Object2ObjectOpenHashMap<String, CustomTextureData> stageDefinitions = new Object2ObjectOpenHashMap<>();
        stageDefinitions.put(sampler, data);
        definitions.put(stage, stageDefinitions);
        return definitions;
    }

    private static CustomTextureData.PngData png(
            final boolean blur,
            final boolean clamp,
            final int... argb
    ) throws IOException {
        BufferedImage image = new BufferedImage(argb.length, 1, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < argb.length; x++) {
            image.setRGB(x, 0, argb[x]);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return new CustomTextureData.PngData(new TextureFilteringData(blur, clamp), output.toByteArray());
    }

    private static TextureFilteringData filtering() {
        return new TextureFilteringData(false, false);
    }

    private static final class BorrowedTestTexture extends AbstractTexture {
        private BorrowedTestTexture(
                final GpuTexture texture,
                final GpuTextureView textureView,
                final GpuSampler sampler
        ) {
            this.texture = texture;
            this.textureView = textureView;
            this.sampler = sampler;
        }
    }

    private static void assertPixel(
            final ByteBuffer pixels,
            final int index,
            final int red,
            final int green,
            final int blue,
            final int alpha
    ) {
        int offset = index * 4;
        assertEquals(red, Byte.toUnsignedInt(pixels.get(offset)), "red at pixel " + index);
        assertEquals(green, Byte.toUnsignedInt(pixels.get(offset + 1)), "green at pixel " + index);
        assertEquals(blue, Byte.toUnsignedInt(pixels.get(offset + 2)), "blue at pixel " + index);
        assertEquals(alpha, Byte.toUnsignedInt(pixels.get(offset + 3)), "alpha at pixel " + index);
    }
}
