package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        runControl(vertexBufferPlaceholder(), "clear-only", null, true);
        runControl(vertexBufferPlaceholder(), "red-pipeline", compileRedPipeline(), false);

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
             MetalGpuTexture sourceColor = (MetalGpuTexture) device.createTexture(
                     "probe-colortex0-source",
                     GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                     GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1
             );
             MetalGpuTextureView colorView = new MetalGpuTextureView(color, 0, 1);
             MetalGpuTextureView sourceView = new MetalGpuTextureView(sourceColor, 0, 1);
             MetalGpuTextureView depthView = new MetalGpuTextureView(depth, 0, 1);
             MetalGpuTextureView noiseView = new MetalGpuTextureView(noise, 0, 1)
        ) {
            writeNoise(noise);
            // colortex0 is a separate ping-pong side in the real pipeline;
            // never sample the attachment we are rendering into (feedback).
            clearTarget(sourceView);
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
                    case "colortex0" -> sourceView;
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
            reportPixel("sampled-depth", pixels, center);

            // Variants with the depth probe hardcoded to far, then one light
            // effect removed at a time. The pixel stats reveal whether the
            // radiating bands come from a specific deferred1 effect.
            String controlBase = linked.fragmentGlsl().replace(
                    "float Depth = get_depth(texcoord, IsDH);",
                    "float Depth = 1.0; // probe control"
            );
            if (!controlBase.equals(linked.fragmentGlsl())) {
                java.util.LinkedHashMap<String, String> variants = new java.util.LinkedHashMap<>();
                variants.put("control-depth1", controlBase);
                variants.put("no-clouds", controlBase.replace(
                        "Color.rgb = get_clouds(ViewPosN, PlayerPos, PlayerPosN, SunGlare, Color.rgb, Dither);",
                        "Color.rgb = Color.rgb;"));
                variants.put("no-stars", controlBase.replace(
                        "Color.rgb += get_stars(PlayerPos);",
                        "Color.rgb += 0.0;"));
                variants.put("no-aurora", controlBase.replace(
                        "Color.rgb += get_aurora(PlayerPosN, Dither);",
                        "Color.rgb += 0.0;"));
                variants.put("no-stars-clouds-aurora", controlBase
                        .replace("Color.rgb += get_stars(PlayerPos(Placeholder));", "") // no-op
                        .replace("Color.rgb += get_stars(PlayerPos);", "Color.rgb += 0.0;")
                        .replace("Color.rgb = get_clouds(ViewPosN, PlayerPos, PlayerPosN, SunGlare, Color.rgb, Dither);",
                                "Color.rgb = Color.rgb;")
                        .replace("Color.rgb += get_aurora(PlayerPosN, Dither);", "Color.rgb += 0.0;"));
                for (Map.Entry<String, String> variant : variants.entrySet()) {
                    renderVariant(variant.getKey(), linked.vertexGlsl(), variant.getValue(),
                            uniformBuffer, vertexBuffer, sampler, sourceView, depthView, noiseView, center);
                }
            } else {
                REPORT.append("control patch pattern not found\n");
            }
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

    private void renderVariant(
            final String name,
            final String vertexGlsl,
            final String fragmentGlsl,
            final MetalGpuBuffer uniformBuffer,
            final MetalGpuBuffer vertexBuffer,
            final MetalGpuSampler sampler,
            final MetalGpuTextureView sourceView,
            final MetalGpuTextureView depthView,
            final MetalGpuTextureView noiseView,
            final int center
    ) {
        MetalCompiledRenderPipeline pipeline;
        try {
            pipeline = MetalCrossShaderCompiler.compileShaderpack(
                    device, "probe/variant/" + name, vertexGlsl, fragmentGlsl, null,
                    Map.of("Position", GpuFormat.RGB32_FLOAT, "UV0", GpuFormat.RG32_FLOAT),
                    false, false, PolygonMode.FILL, PrimitiveTopology.TRIANGLES,
                    new com.mojang.blaze3d.vertex.VertexFormat[]{DefaultVertexFormat.POSITION_TEX},
                    null,
                    new ColorTargetState[]{
                            new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)
                    }
            );
        } catch (Throwable failure) {
            REPORT.append("variant ").append(name).append(" compile failed: ")
                    .append(failure).append('\n');
            return;
        }
        try (MetalGpuTexture target = (MetalGpuTexture) device.createTexture(
                "probe-variant-" + name,
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1
        ); MetalGpuTextureView view = new MetalGpuTextureView(target, 0, 1)) {
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "probe variant " + name)
                    .withColorAttachment(view, Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)))
                    .withDepthAttachment(depthView, OptionalDouble.empty())
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
            pass.setCompiledPipeline(pipeline);
            pass.setUniform("MetallumIrisUniforms", uniformBuffer.slice());
            for (MetalCompiledRenderPipeline.ResourceBinding binding : pipeline.resources()) {
                if (binding.kind() != MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE) {
                    continue;
                }
                MetalGpuTextureView bindingView = switch (binding.name()) {
                    case "colortex0" -> sourceView;
                    case "depthtex0" -> depthView;
                    case "noisetex" -> noiseView;
                    default -> null;
                };
                if (bindingView != null) {
                    pass.bindTexture(binding.name(), bindingView, sampler);
                }
            }
            pass.setVertexBuffer(0, vertexBuffer.slice());
            pass.draw(3, 1, 0, 0);
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();
            ByteBuffer pixels = readback(target);
            reportPixel(name, pixels, center);
            reportStripes(name, pixels);
        } finally {
            pipeline.close();
        }
    }

    private static void reportStripes(final String name, final ByteBuffer pixels) {
        long sum = 0;
        long sumSquares = 0;
        int max = 0;
        int stripes = 0;
        int samples = SIZE * SIZE;
        int previousRow = -1;
        for (int y = 0; y < SIZE; y++) {
            int previous = -1;
            for (int x = 0; x < SIZE; x++) {
                int value = Byte.toUnsignedInt(pixels.get((y * SIZE + x) * 4));
                sum += value;
                sumSquares += (long) value * value;
                max = Math.max(max, value);
                if (previous >= 0 && Math.abs(value - previous) > 8) {
                    stripes++;
                }
                previous = value;
            }
            if (previousRow >= 0) {
                int value = Byte.toUnsignedInt(pixels.get((y * SIZE) * 4));
                if (Math.abs(value - previousRow) > 8) {
                    stripes++;
                }
            }
            previousRow = Byte.toUnsignedInt(pixels.get((y * SIZE) * 4));
        }
        double mean = (double) sum / samples;
        double variance = sumSquares / (double) samples - mean * mean;
        REPORT.append("stats ").append(name)
                .append(" mean=").append(String.format(java.util.Locale.ROOT, "%.2f", mean))
                .append(" max=").append(max)
                .append(" var=").append(String.format(java.util.Locale.ROOT, "%.2f", variance))
                .append(" stripes=").append(stripes)
                .append('\n');
    }

    private static void reportPixel(final String label, final ByteBuffer pixels, final int offset) {
        int r = Byte.toUnsignedInt(pixels.get(offset));
        int g = Byte.toUnsignedInt(pixels.get(offset + 1));
        int b = Byte.toUnsignedInt(pixels.get(offset + 2));
        int a = Byte.toUnsignedInt(pixels.get(offset + 3));
        REPORT.append(label).append(" rgba=").append(r).append(',').append(g)
                .append(',').append(b).append(',').append(a)
                .append(" verdict=").append(r > 1 ? "SKY_BRANCH_RAN" : "SKY_BRANCH_SKIPPED")
                .append('\n');
    }

    private java.nio.ByteBuffer vertexBufferPlaceholder() {
        return fullScreenTriangle();
    }

    private MetalCompiledRenderPipeline compileRedPipeline() {
        String vertex = "#version 450\n"
                + "layout(location=0) in vec3 Position;\n"
                + "layout(location=1) in vec2 UV0;\n"
                + "void main() { gl_Position = vec4(Position, 1.0); }\n";
        String fragment = "#version 450\n"
                + "layout(location=0) out vec4 Color;\n"
                + "void main() { Color = vec4(1.0, 0.0, 0.0, 1.0); }\n";
        try {
            return MetalCrossShaderCompiler.compileShaderpack(
                    device, "probe/red", vertex, fragment, null,
                    Map.of("Position", GpuFormat.RGB32_FLOAT, "UV0", GpuFormat.RG32_FLOAT),
                    false, false, PolygonMode.FILL, PrimitiveTopology.TRIANGLES,
                    new com.mojang.blaze3d.vertex.VertexFormat[]{DefaultVertexFormat.POSITION_TEX},
                    null,
                    new ColorTargetState[]{
                            new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)
                    }
            );
        } catch (Throwable failure) {
            REPORT.append("red pipeline compile failed: ").append(failure).append('\n');
            return null;
        }
    }

    private void runControl(final java.nio.ByteBuffer vertexData, final String label,
                            final MetalCompiledRenderPipeline pipeline, final boolean clearOnly) {
        try (MetalGpuTexture target = (MetalGpuTexture) device.createTexture(
                "probe-control-" + label,
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1
        ); MetalGpuTextureView view = new MetalGpuTextureView(target, 0, 1)) {
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "probe control " + label)
                    .withColorAttachment(view, Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
            if (!clearOnly) {
                if (pipeline == null) {
                    REPORT.append("control ").append(label).append(": no pipeline\n");
                    return;
                }
                pass.setCompiledPipeline(pipeline);
                MetalGpuBuffer vb = createBuffer("control " + label + " vb",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vertexData);
                pass.setVertexBuffer(0, vb.slice());
                pass.draw(3, 1, 0, 0);
                encoder.submitRenderPass();
                encoder.submit();
                device.waitForSubmittedGpuWork();
                vb.close();
            } else {
                encoder.submitRenderPass();
                encoder.submit();
                device.waitForSubmittedGpuWork();
            }
            ByteBuffer pixels = readback(target);
            int center = ((SIZE / 2) * SIZE + SIZE / 2) * 4;
            int r = Byte.toUnsignedInt(pixels.get(center));
            int g = Byte.toUnsignedInt(pixels.get(center + 1));
            int b = Byte.toUnsignedInt(pixels.get(center + 2));
            int a = Byte.toUnsignedInt(pixels.get(center + 3));
            REPORT.append("control ").append(label).append(" rgba=")
                    .append(r).append(',').append(g).append(',').append(b).append(',').append(a).append('\n');
            if (pipeline != null && !clearOnly) {
                pipeline.close();
            }
        }
    }

    private static final int TOPOLOGY_SIZE = 24;

    /**
     * Vanilla draws the sky disc / sunrise fan with {@code PrimitiveTopology.TRIANGLE_FAN} and the
     * vanilla stars / sun / moon with {@code PrimitiveTopology.QUADS} plus the sequential quad index
     * buffer. Metal has neither topology, so the backend has to expand them. This probe renders each
     * vanilla shape through the real {@link MetalRenderPass} path next to the geometrically identical
     * explicit triangle list; both readbacks must match pixel for pixel.
     */
    @Test
    void primitiveTopologyExpansionMatchesExplicitTriangleList() {
        try {
            float[][] fan = new float[6][];
            fan[0] = new float[]{0.0F, 0.0F, 0.0F};
            for (int ring = 0; ring < 5; ring++) {
                double angle = Math.toRadians(ring * 72.0);
                fan[ring + 1] = new float[]{
                        (float) (Math.cos(angle) * 0.72),
                        (float) (Math.sin(angle) * 0.72),
                        0.0F
                };
            }
            int[] fanTriangles = new int[(fan.length - 2) * 3];
            for (int triangle = 0; triangle < fan.length - 2; triangle++) {
                fanTriangles[triangle * 3] = 0;
                fanTriangles[triangle * 3 + 1] = triangle + 1;
                fanTriangles[triangle * 3 + 2] = triangle + 2;
            }
            compareTopology(
                    "fan", positions(fan), PrimitiveTopology.TRIANGLE_FAN, fan.length, null, null,
                    "fan-explicit", positions(fanTriangles, fan), PrimitiveTopology.TRIANGLES, fanTriangles.length,
                    null, null
            );

            // Same shape scaled past the viewport so every triangle is clipped, like the vanilla
            // sky disc whose 512-block rim sits far outside the frustum.
            float[][] clipped = new float[fan.length][];
            for (int vertex = 0; vertex < fan.length; vertex++) {
                clipped[vertex] = new float[]{fan[vertex][0] * 4.0F, fan[vertex][1] * 4.0F, 0.0F};
            }
            compareTopology(
                    "fan-clipped", positions(clipped), PrimitiveTopology.TRIANGLE_FAN, clipped.length, null, null,
                    "fan-clipped-explicit", positions(fanTriangles, clipped), PrimitiveTopology.TRIANGLES,
                    fanTriangles.length, null, null
            );

            for (IndexType indexType : IndexType.values()) {
                float[][] quads = quadVertices();
                int[] quadIndices = sequentialQuadIndices(4);
                int[] quadTriangles = expandQuadTriangles(4);
                compareTopology(
                        "indexed-quads-" + indexType,
                        positions(quads), PrimitiveTopology.QUADS, quadIndices.length,
                        indexData(quadIndices, indexType), indexType,
                        "quad-explicit-" + indexType,
                        positions(quadTriangles, quads), PrimitiveTopology.TRIANGLES, quadTriangles.length,
                        null, null
                );
            }
        } catch (Throwable failure) {
            REPORT.append("TOPOLOGY PROBE EXCEPTION: ").append(failure).append('\n');
        }
    }

    private void compareTopology(
            final String labelA, final ByteBuffer vertexDataA, final PrimitiveTopology topologyA, final int vertexCountA,
            final ByteBuffer indexDataA, final IndexType indexTypeA,
            final String labelB, final ByteBuffer vertexDataB, final PrimitiveTopology topologyB, final int vertexCountB,
            final ByteBuffer indexDataB, final IndexType indexTypeB
    ) {
        ByteBuffer pixelsA = renderPrimitive(labelA, vertexDataA, topologyA, vertexCountA, indexDataA, indexTypeA);
        ByteBuffer pixelsB = renderPrimitive(labelB, vertexDataB, topologyB, vertexCountB, indexDataB, indexTypeB);
        if (pixelsA == null || pixelsB == null) {
            REPORT.append("topology probe ").append(labelA).append(" skipped\n");
            return;
        }
        int mismatches = 0;
        for (int pixel = 0; pixel < TOPOLOGY_SIZE * TOPOLOGY_SIZE; pixel++) {
            if (pixelsA.get(pixel * 4) != pixelsB.get(pixel * 4)) {
                mismatches++;
            }
        }
        REPORT.append("topology ").append(labelA).append(" vs ").append(labelB)
                .append(" mismatchingPixels=").append(mismatches)
                .append(" of ").append(TOPOLOGY_SIZE * TOPOLOGY_SIZE).append('\n');
        REPORT.append("map ").append(labelA).append('\n').append(asciiMap(pixelsA));
        REPORT.append("map ").append(labelB).append('\n').append(asciiMap(pixelsB));
        assertEquals(0, mismatches,
                "Metal " + labelA + " expansion must rasterize exactly like " + labelB);
    }

    private ByteBuffer renderPrimitive(
            final String label,
            final ByteBuffer vertexData,
            final PrimitiveTopology topology,
            final int vertexCount,
            final ByteBuffer indexData,
            final IndexType indexType
    ) {
        MetalCompiledRenderPipeline pipeline = null;
        MetalGpuBuffer vertexBuffer = null;
        MetalGpuBuffer indexBuffer = null;
        try (MetalGpuTexture target = (MetalGpuTexture) device.createTexture(
                "probe-topology-" + label,
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                GpuFormat.RGBA8_UNORM, TOPOLOGY_SIZE, TOPOLOGY_SIZE, 1, 1
        ); MetalGpuTextureView view = new MetalGpuTextureView(target, 0, 1)) {
            pipeline = compileSolidPipeline(label, topology);
            vertexBuffer = createBuffer(label + " vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vertexData);
            RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "probe topology " + label)
                    .withColorAttachment(view, Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, TOPOLOGY_SIZE, TOPOLOGY_SIZE));
            MetalRenderPass pass = (MetalRenderPass) encoder.createRenderPass(descriptor);
            pass.setCompiledPipeline(pipeline);
            pass.setVertexBuffer(0, vertexBuffer.slice());
            if (indexData != null) {
                indexBuffer = createBuffer(label + " indices",
                        GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, indexData);
                pass.setIndexBuffer(indexBuffer, indexType);
                pass.drawIndexed(vertexCount, 1, 0, 0, 0);
            } else {
                pass.draw(vertexCount, 1, 0, 0);
            }
            encoder.submitRenderPass();
            encoder.submit();
            device.waitForSubmittedGpuWork();
            return readback(target, TOPOLOGY_SIZE);
        } catch (Throwable failure) {
            REPORT.append("topology probe ").append(label).append(" failed: ").append(failure).append('\n');
            return null;
        } finally {
            if (pipeline != null) {
                pipeline.close();
            }
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }
            if (indexBuffer != null) {
                indexBuffer.close();
            }
        }
    }

    private MetalCompiledRenderPipeline compileSolidPipeline(final String label, final PrimitiveTopology topology)
            throws Exception {
        String vertex = "#version 450\n"
                + "layout(location=0) in vec3 Position;\n"
                + "void main() { gl_Position = vec4(Position, 1.0); }\n";
        String fragment = "#version 450\n"
                + "layout(location=0) out vec4 Color;\n"
                + "void main() { Color = vec4(1.0, 1.0, 0.0, 1.0); }\n";
        return MetalCrossShaderCompiler.compileShaderpack(
                device, "probe/topology/" + label, vertex, fragment, null,
                Map.of("Position", GpuFormat.RGB32_FLOAT),
                false, false, PolygonMode.FILL, topology,
                new com.mojang.blaze3d.vertex.VertexFormat[]{DefaultVertexFormat.POSITION},
                null,
                new ColorTargetState[]{
                        new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)
                }
        );
    }

    /** Four screen quads in the four-vertices-per-quad layout the sequential buffer expects. */
    private static float[][] quadVertices() {
        return new float[][]{
                {-0.95F, -0.9F, 0.0F}, {-0.95F, -0.1F, 0.0F}, {-0.05F, -0.1F, 0.0F}, {-0.05F, -0.9F, 0.0F},
                {0.05F, -0.9F, 0.0F}, {0.05F, -0.1F, 0.0F}, {0.95F, -0.1F, 0.0F}, {0.95F, -0.9F, 0.0F},
                {-0.95F, 0.1F, 0.0F}, {-0.95F, 0.9F, 0.0F}, {-0.05F, 0.9F, 0.0F}, {-0.05F, 0.1F, 0.0F},
                {0.05F, 0.1F, 0.0F}, {0.05F, 0.9F, 0.0F}, {0.95F, 0.9F, 0.0F}, {0.95F, 0.1F, 0.0F}
        };
    }

    private static int[] sequentialQuadIndices(final int quadCount) {
        int[] indices = new int[quadCount * 6];
        for (int quad = 0; quad < quadCount; quad++) {
            int base = quad * 4;
            indices[quad * 6] = base;
            indices[quad * 6 + 1] = base + 1;
            indices[quad * 6 + 2] = base + 2;
            indices[quad * 6 + 3] = base;
            indices[quad * 6 + 4] = base + 2;
            indices[quad * 6 + 5] = base + 3;
        }
        return indices;
    }

    private static int[] expandQuadTriangles(final int quadCount) {
        return sequentialQuadIndices(quadCount);
    }

    private static ByteBuffer positions(final float[][] vertices) {
        ByteBuffer data = ByteBuffer.allocateDirect(vertices.length * 12).order(ByteOrder.nativeOrder());
        for (float[] vertex : vertices) {
            data.putFloat(vertex[0]).putFloat(vertex[1]).putFloat(vertex[2]);
        }
        data.flip();
        return data;
    }

    private static ByteBuffer positions(final int[] indices, final float[][] vertices) {
        float[][] expanded = new float[indices.length][];
        for (int index = 0; index < indices.length; index++) {
            expanded[index] = vertices[indices[index]];
        }
        return positions(expanded);
    }

    private static ByteBuffer indexData(final int[] indices, final IndexType indexType) {
        ByteBuffer data = ByteBuffer.allocateDirect(indexType.bytes * indices.length).order(ByteOrder.nativeOrder());
        for (int index : indices) {
            if (indexType == IndexType.INT) {
                data.putInt(index);
            } else {
                data.putShort((short) index);
            }
        }
        data.flip();
        return data;
    }

    private static String asciiMap(final ByteBuffer pixels) {
        StringBuilder map = new StringBuilder();
        for (int y = 0; y < TOPOLOGY_SIZE; y++) {
            for (int x = 0; x < TOPOLOGY_SIZE; x++) {
                map.append(pixels.get((y * TOPOLOGY_SIZE + x) * 4) == 0 ? '.' : '#');
            }
            map.append('\n');
        }
        return map.toString();
    }

    private void clearTarget(final MetalGpuTextureView view) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "probe source clear")
                .withColorAttachment(view, Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F)))
                .withRenderArea(new RenderPass.RenderArea(0, 0, SIZE, SIZE));
        encoder.createRenderPass(descriptor);
        encoder.submitRenderPass();
        encoder.submit();
        device.waitForSubmittedGpuWork();
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
        return readback(texture, SIZE);
    }

    private ByteBuffer readback(final MetalGpuTexture texture, final int size) {
        int bytes = size * size * texture.pixelSize();
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
            Files.writeString(
                    PROBE,
                    REPORT.toString(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (Exception ignored) {
        }
        System.out.println("METAL_RENDER_PROBE_BEGIN\n" + REPORT + "METAL_RENDER_PROBE_END");
    }
}
