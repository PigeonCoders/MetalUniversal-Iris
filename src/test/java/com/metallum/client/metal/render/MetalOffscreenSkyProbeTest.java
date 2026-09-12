package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Offscreen Metal render probe for the deferred1 sky branch. This test does not
 * fail on pixel values; it writes {@code build/render-probe.txt} and the CI
 * workflow publishes that file into the job summary so a failed sky branch is
 * visible without an iPad in the loop.
 */
@EnabledOnOs(OS.MAC)
final class MetalOffscreenSkyProbeTest {
    private static final int SIZE = 16;
    private static final Path PROBE = Path.of("build", "render-probe.txt");
    private static final StringBuilder REPORT = new StringBuilder();

    private MetalDevice device;
    private MetalCommandEncoder encoder;

    @BeforeEach
    void createDevice() {
        Assumptions.assumeTrue(canLoadSpvc(), "libspvc unavailable on this host");
        MemorySegment nativeDevice = MetalNativeBridge.metallum_create_system_default_device();
        assertFalse(MetalNativeBridge.isNullHandle(nativeDevice), "MTLCreateSystemDefaultDevice returned null");
        ShaderSource fallback = (identifier, type) -> type == ShaderType.VERTEX
                ? "#version 450\nvoid main(){gl_Position=vec4(0.0);}"
                : "#version 450\nlayout(location=0) out vec4 c; void main(){c=vec4(0.0);}";
        device = new MetalDevice(
                fallback,
                new GpuDebugOptions(2, true, true, true),
                nativeDevice,
                MemorySegment.NULL,
                "offscreen sky probe device",
                MemorySegment.NULL
        );
        encoder = device.createCommandEncoder();
        REPORT.setLength(0);
    }

    @AfterEach
    void closeDevice() {
        if (device != null) {
            device.close();
        }
        writeReport();
    }

    @Test
    void deferredSkyBranchProducesNonBlackWhenDepthIsFar() throws Exception {
        Path zip = Files.createTempFile("mellow-sky-probe-", ".zip");
        try {
            try (var input = getClass().getResourceAsStream("/shaderpacks/Mellow Shader v3.3.zip")) {
                assertNotNull(input, "missing Mellow fixture");
                Files.copy(input, zip, StandardCopyOption.REPLACE_EXISTING);
            }
            try (var fs = java.nio.file.FileSystems.newFileSystem(zip, Map.of())) {
                ShaderPack pack = new ShaderPack(
                        fs.getPath("/shaders"),
                        StandardMacros.createStandardEnvironmentDefines(),
                        false
                );
                ProgramSet set = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
                ProgramSource deferred = java.util.Arrays.stream(set.getComposite(ProgramArrayId.Deferred))
                        .filter(source -> source != null && source.isValid())
                        .findFirst().orElseThrow();
                probe(pack, deferred, set);
            }
        } catch (Throwable failure) {
            REPORT.append("PROBE EXCEPTION: ").append(failure).append('\n');
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private void probe(
            final ShaderPack pack,
            final ProgramSource deferred,
            final ProgramSet set
    ) throws Exception {
        IrisMetalProgramFrontend frontend = new IrisMetalProgramFrontend(set);
        IrisMetalGlslLinker.LinkedRasterProgram linked =
                IrisMetalGlslLinker.linkDefault(frontend.patchComposite(deferred, TextureStage.DEFERRED));
        REPORT.append("linked=").append(linked.name()).append('\n');
        REPORT.append("samplers=").append(linked.samplers().stream()
                .map(s -> s.name() + ":" + s.glslType()).toList()).append('\n');

        MetalCrossShaderCompiler.ShaderpackMslResult msl =
                MetalCrossShaderCompiler.tryCompileShaderpackMsl(
                        linked.name(), linked.vertexGlsl(), null, null, null, linked.fragmentGlsl(), null
                );
        String fragmentMsl = msl.fragmentMsl();
        REPORT.append("msl uses depth2d=").append(fragmentMsl.contains("depth2d<float> depthtex0")).append('\n');
        REPORT.append("msl depthtex0 old texture2d=").append(fragmentMsl.contains("texture2d<float> depthtex0")).append('\n');

        IrisMetalUniformValues values = new IrisMetalUniformValues(0.0F, null, null, () -> 0, false);
        String token = "probe/deferred";
        values.register(token, token, linked);
        values.offlineUnsupported(token);
        ByteBuffer stagingSource = values.lastUpload(token);
        ByteBuffer uniforms = ByteBuffer.allocateDirect(linked.uniformBlockSize()).order(ByteOrder.nativeOrder());
        if (stagingSource != null) {
            ByteBuffer copy = stagingSource.duplicate();
            copy.clear();
            uniforms.put(copy);
            uniforms.flip();
        }
        putVec(uniforms, linked, "fogColor", 0.02F, 0.02F, 0.04F);
        putVec(uniforms, linked, "skyColor", 0.02F, 0.02F, 0.04F);
        putMat4Identity(uniforms, linked, "gbufferModelView");
        putMat4Identity(uniforms, linked, "gbufferModelViewInverse");
        putMat4Identity(uniforms, linked, "gbufferProjection");
        putMat4Identity(uniforms, linked, "gbufferProjectionInverse");
        putFloat(uniforms, linked, "near", 0.05F);
        putFloat(uniforms, linked, "far", 64.0F);
        putFloat(uniforms, linked, "dayStrength", 0.0F);
        putFloat(uniforms, linked, "nightStrength", 1.0F);
        putVec(uniforms, linked, "sunPosN", 0.0F, 1.0F, 0.0F);
        putVec(uniforms, linked, "sunOrMoonPosN", 0.0F, 1.0F, 0.0F);
        putFloat(uniforms, linked, "cameraPosition", 0.0F);

        MetalCompiledRenderPipeline pipeline = MetalCrossShaderCompiler.compileShaderpack(
                device,
                "probe/deferred1",
                linked.vertexGlsl(),
                linked.fragmentGlsl(),
                null,
                Map.of("Position", GpuFormat.RGB32_FLOAT, "UV0", GpuFormat.RG32_FLOAT),
                false,
                false,
                PolygonMode.FILL,
                PrimitiveTopology.TRIANGLES,
                new com.mojang.blaze3d.vertex.VertexFormat[]{DefaultVertexFormat.POSITION_TEX},
                null,
                new ColorTargetState[]{
                        new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)
                }
        );
        REPORT.append("pipeline valid=").append(pipeline.isValid()).append('\n');

        try (MetalGpuTexture color = (MetalGpuTexture) device.createTexture(
                "probe-colortex0",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1
        );
             MetalGpuTexture depth = (MetalGpuTexture) device.createTexture(
                     "probe-depthtex0",
                     GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
                     GpuFormat.D32_FLOAT, SIZE, SIZE, 1, 1
             );
             MetalGpuTexture noise = (MetalGpuTexture) device.createTexture(
                     "probe-noisetex",
                     GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                     GpuFormat.RGBA8_UNORM, 2, 2, 1, 1
             );
             MetalGpuTextureView colorView = new MetalGpuTextureView(color, 0, 1);
             MetalGpuTextureView depthView = new MetalGpuTextureView(depth, 0, 1);
             MetalGpuTextureView noiseView = new MetalGpuTextureView(noise, 0, 1)
        ) {
            writeNoise(noise);
            MetalGpuBuffer uniformBuffer = createBuffer("probe uniforms",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, uniforms);
            MetalGpuBuffer vertexBuffer = createBuffer("probe triangle",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, fullScreenTriangle());
            MetalGpuSampler sampler = new MetalGpuSampler(
                    device, AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty()
            );
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "probe deferred pass")
                    .withColorAttachment(colorView, Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)))
                    .withDepthAttachment(depthView, OptionalDouble.of(1.0))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
            pass.setCompiledPipeline(pipeline);
            pass.setUniform("MetallumIrisUniforms", uniformBuffer.slice());
            List<String> bound = new ArrayList<>();
            for (MetalCompiledRenderPipeline.ResourceBinding binding : pipeline.resources()) {
                if (binding.kind() != MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
                    continue;
                }
                MetalGpuTextureView view = switch (binding.name()) {
                    case "colortex0" -> colorView;
                    case "depthtex0" -> depthView;
                    case "noisetex" -> noiseView;
                    default -> null;
                };
                if (view == null) {
                    REPORT.append("UNBOUND sampler ").append(binding.name()).append('\n');
                    continue;
                }
                pass.bindTexture(binding.name(), view, sampler);
                bound.add(binding.name());
            }
            REPORT.append("bound=").append(bound).append('\n');
            pass.setVertexBuffer(0, vertexBuffer.slice());
            pass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();

            ByteBuffer pixels = readback(color);
            int center = ((SIZE / 2) * SIZE + SIZE / 2) * 4;
            REPORT.append("center rgba=")
                    .append(Byte.toUnsignedInt(pixels.get(center))).append(',')
                    .append(Byte.toUnsignedInt(pixels.get(center + 1))).append(',')
                    .append(Byte.toUnsignedInt(pixels.get(center + 2))).append(',')
                    .append(Byte.toUnsignedInt(pixels.get(center + 3))).append('\n');
            REPORT.append("verdict=")
                    .append(Byte.toUnsignedInt(pixels.get(center)) > 1 ? "SKY_BRANCH_RAN" : "SKY_BRANCH_SKIPPED")
                    .append('\n');
            vertexBuffer.close();
            uniformBuffer.close();
            sampler.close();
        } finally {
            pipeline.close();
        }
        if (pack == null) {
            throw new IllegalStateException("unreachable");
        }
    }

    private void writeNoise(final MetalGpuTexture noise) {
        ByteBuffer data = ByteBuffer.allocateDirect(2 * 2 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4; i++) {
            data.put((byte) 0x80).put((byte) 0x80).put((byte) 0x80).put((byte) 0xFF);
        }
        data.flip();
        encoder.writeToTexture(noise, data, 0, 0, 0, 0, 2, 2);
    }

    private MetalGpuBuffer createBuffer(final String label, final int usage, final ByteBuffer data) {
        try {
            return (MetalGpuBuffer) device.createBuffer(() -> label, usage, data);
        } catch (Throwable failure) {
            REPORT.append("BUFFER FAIL ").append(label).append(": ").append(failure).append('\n');
            throw failure;
        }
    }

    private ByteBuffer readback(final MetalGpuTexture texture) {
        int bytes = SIZE * SIZE * texture.pixelSize();
        try (MetalGpuBuffer buffer = (MetalGpuBuffer) device.createBuffer(
                () -> "probe readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes
        )) {
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, 0);
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer source = buffer.currentStorage().limit(bytes).slice().order(ByteOrder.nativeOrder());
            ByteBuffer copy = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder());
            copy.put(source).flip();
            return copy;
        }
    }

    private static ByteBuffer fullScreenTriangle() {
        int stride = DefaultVertexFormat.POSITION_TEX.getVertexSize();
        VertexFormatElement position = DefaultVertexFormat.POSITION_TEX.getElements().get(0);
        VertexFormatElement uv = DefaultVertexFormat.POSITION_TEX.getElements().get(1);
        ByteBuffer data = ByteBuffer.allocateDirect(3 * stride).order(ByteOrder.nativeOrder());
        float[][] points = {{-1.0F, -1.0F}, {3.0F, -1.0F}, {-1.0F, 3.0F}};
        for (int index = 0; index < points.length; index++) {
            int base = index * stride;
            data.putFloat(base + position.offset(), points[index][0]);
            data.putFloat(base + position.offset() + Float.BYTES, points[index][1]);
            data.putFloat(base + position.offset() + 2 * Float.BYTES, 0.0F);
            data.putFloat(base + uv.offset(), points[index][0] * 0.5F + 0.5F);
            data.putFloat(base + uv.offset() + Float.BYTES, points[index][1] * 0.5F + 0.5F);
        }
        // Absolute putFloat() calls do not advance position; leaving the
        // buffer position at 0 gives createBuffer() the full vertex payload.
        return data;
    }

    private static void putFloat(final ByteBuffer out, final IrisMetalGlslLinker.LinkedRasterProgram linked,
                                 final String name, final float value) {
        linked.uniformLayout().stream().filter(m -> m.name().equals(name)).findFirst()
                .ifPresent(m -> out.putFloat(m.offset(), value));
    }

    private static void putVec(final ByteBuffer out, final IrisMetalGlslLinker.LinkedRasterProgram linked,
                               final String name, final float x, final float y, final float z) {
        linked.uniformLayout().stream().filter(m -> m.name().equals(name)).findFirst()
                .ifPresent(m -> {
                    out.putFloat(m.offset(), x);
                    out.putFloat(m.offset() + 4, y);
                    out.putFloat(m.offset() + 8, z);
                });
    }

    private static void putMat4Identity(final ByteBuffer out, final IrisMetalGlslLinker.LinkedRasterProgram linked,
                                        final String name) {
        linked.uniformLayout().stream().filter(m -> m.name().equals(name)).findFirst()
                .ifPresent(m -> {
                    for (int column = 0; column < 4; column++) {
                        for (int row = 0; row < 4; row++) {
                            out.putFloat(m.offset() + column * 16 + row * 4, column == row ? 1.0F : 0.0F);
                        }
                    }
                });
    }

    private static boolean canLoadSpvc() {
        try {
            MetalCrossShaderCompiler.storageBufferLogicalBinding("probe");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void writeReport() {
        try {
            Files.createDirectories(PROBE.getParent());
            Files.writeString(PROBE, REPORT.toString());
        } catch (Exception ignored) {
        }
        System.out.println("METAL_RENDER_PROBE_BEGIN\n" + REPORT + "METAL_RENDER_PROBE_END");
    }
}
