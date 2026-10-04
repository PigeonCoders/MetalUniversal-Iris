package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.BufferBlendInformation;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CR r5.9.3 smoke test: {@code shaders/shaders.properties} carries
 * unconditional {@code blend.gbuffers_*.colortexN=off} overrides, which Iris
 * rejects unless {@code supportsBufferBlending()} reports true. This pins the
 * property parsing plus the program&rarr;colortex mapping the Metal PSO
 * consumes; the mixin that flips the capability bit is exercised on device
 * (the headless stub already returns true, so a unit test cannot regress it).
 */
final class ComplementaryReimaginedBlendDirectiveTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "ComplementaryReimagined_r5.9.3.zip");

    @Test
    void unconditionalColorTargetBlendOverridesParse() throws Exception {
        assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;
        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));

            assertBlendOff(programs.get(ProgramId.Textured).orElseThrow(), 4);
            ProgramSource water = programs.get(ProgramId.Water).orElseThrow();
            assertBlendOff(water, 4);
            assertBlendOff(water, 8);
        }
    }

    private static void assertBlendOff(final ProgramSource source, final int colortex) {
        List<BufferBlendInformation> overrides = source.getDirectives().getBufferBlendOverrides();
        assertTrue(
                overrides.stream().anyMatch(override -> override.index() == colortex && override.blendMode() == null),
                source.getName() + " is missing blend off for colortex" + colortex + ": " + overrides
        );
    }
}
