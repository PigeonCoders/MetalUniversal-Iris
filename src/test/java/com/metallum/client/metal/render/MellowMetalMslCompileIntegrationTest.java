package com.metallum.client.metal.render;

import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MSL-binding regression for the Mellow v3.3 acceptance target. Mellow's
 * all_the_uniforms.glsl declares many feature-guarded samplers; before
 * active-resource compaction gbuffers_terrain_solid generated
 * {@code [[sampler(17)]]}, which Apple's Metal compiler rejects.
 */
@EnabledOnOs(OS.MAC)
final class MellowMetalMslCompileIntegrationTest {
    private static final String FIXTURE = "/shaderpacks/Mellow Shader v3.3.zip";
    private static final Pattern SAMPLER_BINDING = Pattern.compile("\\[\\[sampler\\((\\d+)\\)]]");
    private static final Pattern TEXTURE_BINDING = Pattern.compile("\\[\\[texture\\((\\d+)\\)]]");

    @Test
    void mellowTerrainAndDeferredSamplersStayWithinMetalLimits() throws Exception {
        Assumptions.assumeTrue(canLoadSpvc(), "native libspvc unavailable on this host");
        Path zip = Files.createTempFile("mellow-msl-compile-", ".zip");
        try {
            try (var input = getClass().getResourceAsStream(FIXTURE)) {
                assertNotNull(input, "missing Mellow fixture " + FIXTURE);
                Files.copy(input, zip, StandardCopyOption.REPLACE_EXISTING);
            }
            try (FileSystem fs = FileSystems.newFileSystem(zip, Map.of())) {
                ShaderPack pack = new ShaderPack(
                        fs.getPath("/shaders"),
                        StandardMacros.createStandardEnvironmentDefines(),
                        false
                );
                ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
                try (IrisMetalWorldPrograms programs = new IrisMetalWorldPrograms(7, programSet)) {
                    var terrain = programs.sodium(ProgramId.TerrainSolid, AlphaTest.ALWAYS).orElseThrow();
                    assertBindingsWithinLimits(
                            MetalCrossShaderCompiler.tryCompileShaderpackMsl(
                                    terrain.name(),
                                    terrain.vertexGlsl(),
                                    null,
                                    null,
                                    null,
                                    terrain.fragmentGlsl(),
                                    null
                            )
                    );

                    ProgramSource deferred = Arrays.stream(programSet.getComposite(ProgramArrayId.Deferred))
                            .filter(source -> source != null && source.isValid())
                            .findFirst()
                            .orElseThrow();
                    var deferredProgram = programs.composite(deferred, TextureStage.DEFERRED);
                    assertBindingsWithinLimits(
                            MetalCrossShaderCompiler.tryCompileShaderpackMsl(
                                    deferredProgram.name(),
                                    deferredProgram.vertexGlsl(),
                                    null,
                                    null,
                                    null,
                                    deferredProgram.fragmentGlsl(),
                                    null
                            )
                    );
                }
            }
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private static boolean canLoadSpvc() {
        try {
            MetalCrossShaderCompiler.storageBufferLogicalBinding("probe");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void assertBindingsWithinLimits(final MetalCrossShaderCompiler.ShaderpackMslResult result) {
        assertNotNull(result);
        assertBindingsWithinLimits(result.vertexMsl(), "vertex");
        assertBindingsWithinLimits(result.fragmentMsl(), "fragment");
        assertTrue(
                result.vertexMsl().contains("out.gl_Position.z = (out.gl_Position.z + out.gl_Position.w) * 0.5"),
                "vertex MSL is missing the clip-space Z remap to Metal [0,w]; front-half geometry would be clipped"
        );
    }

    private static void assertBindingsWithinLimits(final String msl, final String stage) {
        Matcher sampler = SAMPLER_BINDING.matcher(msl);
        while (sampler.find()) {
            int binding = Integer.parseInt(sampler.group(1));
            assertTrue(binding <= 15, stage + " MSL uses sampler binding " + binding + " (> 15): " + sampler.group());
        }
        Matcher texture = TEXTURE_BINDING.matcher(msl);
        while (texture.find()) {
            int binding = Integer.parseInt(texture.group(1));
            assertTrue(binding <= 30, stage + " MSL uses texture binding " + binding + " (> 30): " + texture.group());
        }
    }
}
