package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.GlslangBridge;
import com.metallum.client.metal.render.bridge.GlslangSourcePrep;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression gate for the Mellow v3.4.1a FABULOUS on-device crash
 * {@code Failed to compile Iris pass composite10}: glslang
 * {@code 'highp' : overloaded functions must have the same parameter precision
 * qualifiers for argument 1/2/3}.
 *
 * <p>Mellow's SMAA block declares its own {@code fma} overloads for
 * {@code float}/{@code vec2}/{@code vec4}. In Vulkan-mode glslang an
 * unqualified parameter carries no precision, so redeclaring the built-in
 * {@code fma} fails. {@link GlslangSourcePrep#buildFullSource} now prepends
 * {@code precision highp float/int;} (gated by
 * {@link MetalDebugSwitches#GLSLANG_PRECISION_PREAMBLE}); the structural test
 * pins that the preamble precedes the first overload. The actual native
 * compile follows {@link BslShaderCompileTest}'s host gate.
 */
final class MellowFabulousPrecisionTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "Mellow_3.4.1a.zip");
    /** The ten option values that select profile.FABULOUS. */
    private static final Map<String, String> FABULOUS = Map.ofEntries(
            Map.entry("BLOOM", "true"),
            Map.entry("CLOUD_STYLE", "1"),
            Map.entry("WATER_NORMALS", "true"),
            Map.entry("TAA_MODE", "1"),
            Map.entry("SMAA", "true"),
            Map.entry("REFLECTIONS", "2"),
            Map.entry("GODRAYS", "true"),
            Map.entry("SSAO", "true"),
            Map.entry("USE_LQ_DEPTH", "true"),
            Map.entry("IMAGE_SHARPENING", "true")
    );

    @Test
    void fabulousComposite10PrecisionPreambleCoversFmaOverloads() throws Exception {
        Assumptions.assumeTrue(Files.exists(PACK), "Mellow fixture missing: " + PACK);
        Iris.testing = true;
        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    FABULOUS,
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            try (IrisMetalWorldPrograms worldPrograms = new IrisMetalWorldPrograms(1, programs)) {
                ProgramSource source = null;
                for (ProgramSource candidate : programs.getComposite(ProgramArrayId.Composite)) {
                    if (candidate != null && candidate.getName().equals("composite10") && candidate.isValid()) {
                        source = candidate;
                        break;
                    }
                }
                Assumptions.assumeTrue(source != null, "Mellow FABULOUS composite10 is not enabled");
                IrisMetalGlslLinker.LinkedRasterProgram linked =
                        worldPrograms.composite(source, TextureStage.COMPOSITE_AND_FINAL);

                assertTrue(MetalDebugSwitches.GLSLANG_PRECISION_PREAMBLE,
                        "precision preamble must default on");
                assertTrue(linked.fragmentGlsl().contains("float fma(float a, float b, float c)"),
                        "composite10 must still declare its fma overloads (the crash trigger)");

                String glslangSource = GlslangSourcePrep.buildFullSource(linked.fragmentGlsl(), null);
                int precision = glslangSource.indexOf("precision highp float;");
                int firstOverload = glslangSource.indexOf("float fma(float a, float b, float c)");
                assertTrue(precision >= 0,
                        "glslang source must declare the default float precision");
                assertTrue(firstOverload > precision,
                        "the precision declaration must precede the fma overload "
                                + "(precision@" + precision + ", overload@" + firstOverload + ")");
            }
        }
    }

    @Test
    void fabulousComposite10CompilesWhenGlslangIsAvailable() throws Exception {
        Assumptions.assumeTrue(Files.exists(PACK), "Mellow fixture missing: " + PACK);
        boolean canCompile;
        try {
            GlslangBridge.compileGlslToSpv(
                    GlslangBridge.Stage.VERTEX,
                    "#version 460\nvoid main(){gl_Position=vec4(0);}",
                    null
            );
            canCompile = true;
        } catch (Throwable unavailable) {
            if (Boolean.getBoolean("metallum.mellowtest.force")) {
                fail("metallum.mellowtest.force=true but native glslang could not load: " + unavailable,
                        unavailable);
            }
            canCompile = false;
        }
        Assumptions.assumeTrue(canCompile,
                "native libglslang unavailable (non-macOS host); the structural gate still runs");

        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    FABULOUS,
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            try (IrisMetalWorldPrograms worldPrograms = new IrisMetalWorldPrograms(1, programs)) {
                for (ProgramSource candidate : programs.getComposite(ProgramArrayId.Composite)) {
                    if (candidate == null || !candidate.getName().equals("composite10") || !candidate.isValid()) {
                        continue;
                    }
                    IrisMetalGlslLinker.LinkedRasterProgram linked =
                            worldPrograms.composite(candidate, TextureStage.COMPOSITE_AND_FINAL);
                    try {
                        GlslangBridge.compileGlslToSpv(
                                GlslangBridge.Stage.FRAGMENT, linked.fragmentGlsl(), null
                        );
                    } catch (GlslangBridge.ShaderCompileException failure) {
                        fail("FABULOUS composite10 fragment must compile: " + failure.getMessage(), failure);
                    }
                    return;
                }
                Assumptions.abort("Mellow FABULOUS composite10 is not enabled");
            }
        }
    }
}
