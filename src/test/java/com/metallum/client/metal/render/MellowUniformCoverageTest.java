package com.metallum.client.metal.render;

import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline uniform coverage for the Mellow v3.3 acceptance target. Links every
 * raster program the Metal pipeline registers for the pack and writes each
 * uniform block through {@link IrisMetalUniformValues}; any name still without
 * a value source must be exactly the set owned by the pack's custom-uniform
 * graph (which cannot resolve headless without Minecraft's fixed inputs).
 * Everything else must be covered by the built-in sources or the optional-mod
 * zero-fill list.
 */
final class MellowUniformCoverageTest {
    private static final String FIXTURE = "/shaderpacks/Mellow Shader v3.3.zip";

    private static final Set<String> PACK_CUSTOM_GRAPH_UNIFORMS = Set.of(
            "dayStrength",
            "fogAmount",
            "isOutdoorsSmooth",
            "nightStrength",
            "precipitationSmooth",
            "rainbowStrength",
            "resolution",
            "resolutionInv",
            "sunPosN",
            "sunsetStrength",
            "taaJitter"
    );

    @Test
    void mellowRasterUniformsAreCoveredByMetalSources() throws Exception {
        Path zip = Files.createTempFile("mellow-uniform-coverage-", ".zip");
        try {
            try (var input = getClass().getResourceAsStream(FIXTURE)) {
                assertTrue(input != null, "missing Mellow fixture " + FIXTURE);
                Files.copy(input, zip, StandardCopyOption.REPLACE_EXISTING);
            }
            try (FileSystem fs = FileSystems.newFileSystem(zip, Map.of())) {
                ShaderPack pack = new ShaderPack(
                        fs.getPath("/shaders"),
                        StandardMacros.createStandardEnvironmentDefines(),
                        false
                );
                ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
                // Headless graph: register no fixed inputs, so every pack
                // uniform.* expression fails to resolve exactly as intended.
                CustomUniforms customUniforms = pack.customUniforms.build(holder -> {
                });
                IrisMetalFrameState frameState = new IrisMetalFrameState();

                Set<String> missing = new LinkedHashSet<>();
                try (IrisMetalWorldPrograms programs = new IrisMetalWorldPrograms(7, programSet)) {
                    IrisMetalUniformValues values = new IrisMetalUniformValues(
                            programSet.getPackDirectives().getSunPathRotation(),
                            customUniforms,
                            frameState.updateNotifier(),
                            () -> 0,
                            false
                    );
                    registerSodium(
                            values, programs, ShaderKey.SODIUM_TERRAIN_SOLID,
                            "sodium_terrain_solid", missing
                    );
                    registerSodium(
                            values, programs, ShaderKey.SODIUM_TERRAIN_CUTOUT,
                            "sodium_terrain_cutout", missing
                    );
                    registerSodium(
                            values, programs, ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
                            "sodium_terrain_translucent", missing
                    );
                    registerSodium(
                            values, programs, ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID,
                            "shadow_sodium_terrain_solid", missing
                    );
                    registerSodium(
                            values, programs, ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT,
                            "shadow_sodium_terrain_cutout", missing
                    );
                    registerSodium(
                            values, programs, ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT,
                            "shadow_sodium_terrain_translucent", missing
                    );
                    auditArray(
                            values, programs, programSet, ProgramArrayId.Setup,
                            TextureStage.SETUP, "setup", missing
                    );
                    auditArray(
                            values, programs, programSet, ProgramArrayId.Begin,
                            TextureStage.BEGIN, "begin", missing
                    );
                    auditArray(
                            values, programs, programSet, ProgramArrayId.ShadowComposite,
                            TextureStage.SHADOWCOMP, "shadowcomp", missing
                    );
                    auditArray(
                            values, programs, programSet, ProgramArrayId.Prepare,
                            TextureStage.PREPARE, "prepare", missing
                    );
                    auditArray(
                            values, programs, programSet, ProgramArrayId.Deferred,
                            TextureStage.DEFERRED, "deferred", missing
                    );
                    auditArray(
                            values, programs, programSet, ProgramArrayId.Composite,
                            TextureStage.COMPOSITE_AND_FINAL, "composite", missing
                    );
                    var finalProgram = programs.finalProgram();
                    if (finalProgram != null) {
                        values.register("final", "final", finalProgram);
                        missing.addAll(values.offlineUnsupported("final"));
                    }
                }

                assertEquals(PACK_CUSTOM_GRAPH_UNIFORMS, missing);
                assertFalse(missing.contains("entityId"), "entityId must have a built-in source");
            }
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private static void registerSodium(
            final IrisMetalUniformValues values,
            final IrisMetalWorldPrograms programs,
            final ShaderKey key,
            final String token,
            final Set<String> missing
    ) {
        programs.sodium(key.getProgram(), key.getAlphaTest()).ifPresent(linked -> {
            values.register(token, token, linked);
            missing.addAll(values.offlineUnsupported(token));
        });
    }

    private static void auditArray(
            final IrisMetalUniformValues values,
            final IrisMetalWorldPrograms programs,
            final ProgramSet programSet,
            final ProgramArrayId arrayId,
            final TextureStage stage,
            final String prefix,
            final Set<String> missing
    ) {
        ProgramSource[] sources = programSet.getComposite(arrayId);
        for (int index = 0; index < sources.length; index++) {
            ProgramSource source = sources[index];
            if (source == null || !source.isValid()) {
                continue;
            }
            String token = prefix + "/" + index;
            values.register(token, token, programs.composite(source, stage));
            missing.addAll(values.offlineUnsupported(token));
        }
    }
}
