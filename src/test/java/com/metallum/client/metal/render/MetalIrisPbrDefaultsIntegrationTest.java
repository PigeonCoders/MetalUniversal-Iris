package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** GPU coverage for Iris's default PBR normal/specular bindings on Metal. */
@EnabledOnOs(OS.MAC)
final class MetalIrisPbrDefaultsIntegrationTest {
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
                "Iris PBR defaults integration device",
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
    void pbrDefaultsUseIrisSingleColorValuesAndAreOwnedByTheGeneration() {
        IrisMetalWorldResources resources = new IrisMetalWorldResources(
                device,
                7,
                new GpuFormat[]{GpuFormat.RGBA8_UNORM},
                8,
                8,
                Map.of(),
                Set.of(),
                Map.of(),
                Map.of(),
                8,
                null
        );
        MetalRenderPass.TextureViewAndSampler normals = resources.pbrNormals();
        MetalRenderPass.TextureViewAndSampler specular = resources.pbrSpecular();

        ByteBuffer normalPixels = readback((MetalGpuTexture) normals.textureView().texture());
        assertPixel(normalPixels, 127, 127, 255, 255);
        ByteBuffer specularPixels = readback((MetalGpuTexture) specular.textureView().texture());
        assertPixel(specularPixels, 0, 0, 0, 0);

        resources.close();
        assertTrue(normals.textureView().isClosed());
        assertTrue(specular.textureView().isClosed());
        assertTrue(((MetalGpuSampler) normals.sampler()).isClosed());
        assertTrue(((MetalGpuSampler) specular.sampler()).isClosed());
    }

    private ByteBuffer readback(final MetalGpuTexture texture) {
        int size = texture.getWidth(0) * texture.getHeight(0) * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "iris pbr default readback",
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

    private static void assertPixel(
            final ByteBuffer pixels,
            final int red,
            final int green,
            final int blue,
            final int alpha
    ) {
        assertEquals(red, Byte.toUnsignedInt(pixels.get(0)));
        assertEquals(green, Byte.toUnsignedInt(pixels.get(1)));
        assertEquals(blue, Byte.toUnsignedInt(pixels.get(2)));
        assertEquals(alpha, Byte.toUnsignedInt(pixels.get(3)));
    }
}
