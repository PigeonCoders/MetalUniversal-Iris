package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the MakeUp-UltraFast "Iris pass composite is missing
 * required sampler 'gaux3'" crash.
 *
 * <p>The pack is older than the colortexN naming convention and samples the
 * legacy render-target aliases in its raster programs
 * ({@code gbuffers_*}: {@code gaux1}/{@code gaux2}/{@code gaux4}) and in its
 * graph programs ({@code deferred}, {@code composite}, {@code composite1},
 * {@code composite2}, {@code final}: {@code gaux3} for exposure,
 * {@code gaux2} for clouds). The execution graph resolved sampler names
 * through {@code colortexN} only, so an active {@code gaux3} came back null
 * and {@code bindRaster} threw before the pass draw.
 *
 * <p>This test links the real pack without native libraries and pins that
 * every active sampler name resolves under the same name rules as
 * {@code IrisMetalExecutionGraph.textureBinding} /
 * {@code IrisMetalWorldBridge.standardSampler} (including
 * {@link IrisMetalRenderTargets#renderTargetIndex}), and that the crash's
 * {@code gaux3} is among the names actually exercised.
 */
final class MakeUpSamplerCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "MakeUp-UltraFast-9.5f.zip");
    /** MakeUp's legacy aliases: the exposure aux buffer is read by graph programs. */
    private static final Set<String> EXPECTED_LEGACY_ACTIVE = Set.of("gaux1", "gaux2", "gaux3", "gaux4");
    private static final Pattern DECL = Pattern.compile(
            "(?m)^\\s*(?:(?:flat|noperspective|centroid|coherent|volatile|restrict|readonly|writeonly)\\s+)*"
                    + "uniform\\s+(\\w+)\\s+(\\w+)\\s*;");

    @Test
    void makeUpLegacySamplersResolveForActiveSet() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            int targetCount = IrisMetalRenderTargetFormats.from(programSet.getPackDirectives()).length;
            int shadowColorCount = pack.hasFeature(FeatureFlags.HIGHER_SHADOWCOLOR)
                    ? net.irisshaders.iris.shaderpack.properties.PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_IRIS
                    : net.irisshaders.iris.shaderpack.properties.PackShadowDirectives.MAX_SHADOW_COLOR_BUFFERS_OF;
            Map<TextureStage, ?> customTextures = pack.getCustomTextureDataMap();
            List<String> checked = new ArrayList<>();
            Set<String> activeMappedTargets = new LinkedHashSet<>();
            Map<String, Set<String>> activeByProgram = new LinkedHashMap<>();

            try (IrisMetalWorldPrograms programs = new IrisMetalWorldPrograms(1, programSet)) {
                for (ProgramSource source : programSet.getComposite(ProgramArrayId.Deferred)) {
                    if (source != null && source.isValid()) {
                        activeByProgram.put(source.getName(), inspect(
                                "deferred", source, TextureStage.DEFERRED,
                                programs.composite(source, TextureStage.DEFERRED),
                                targetCount, shadowColorCount, customTextures, checked, activeMappedTargets, false));
                    }
                }
                for (ProgramSource source : programSet.getComposite(ProgramArrayId.Composite)) {
                    if (source != null && source.isValid()) {
                        activeByProgram.put(source.getName(), inspect(
                                "composite", source, TextureStage.COMPOSITE_AND_FINAL,
                                programs.composite(source, TextureStage.COMPOSITE_AND_FINAL),
                                targetCount, shadowColorCount, customTextures, checked, activeMappedTargets, false));
                    }
                }
                IrisMetalGlslLinker.LinkedRasterProgram finalProgram = programs.finalProgram();
                if (finalProgram != null) {
                    activeByProgram.put("final", inspect(
                            "final", finalProgram.program().resolution().source(),
                            TextureStage.COMPOSITE_AND_FINAL, finalProgram,
                            targetCount, shadowColorCount, customTextures, checked, activeMappedTargets, false));
                }

                // Every gbuffers and shadow raster program the pack ships.
                ShaderAttributeInputs inputs = new ShaderAttributeInputs(true, true, true, true, true);
                for (ProgramId id : ProgramId.values()) {
                    if (id == ProgramId.Final || id.getGroup() == ProgramGroup.Dh) {
                        continue;
                    }
                    Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked =
                            programs.vanilla(id, AlphaTest.ALWAYS, false, false, inputs);
                    if (linked.isPresent()) {
                        IrisMetalGlslLinker.LinkedRasterProgram program = linked.orElseThrow();
                        inspect("raster:" + id.name(), program.program().resolution().source(),
                                TextureStage.GBUFFERS_AND_SHADOW, program,
                                targetCount, shadowColorCount, customTextures, checked, activeMappedTargets, true);
                    }
                }
            }

            assertFalse(checked.isEmpty(), "no MakeUp program was linked");
            // The crash was exactly this pass: composite's vertex stage samples
            // gaux3 for exposure.
            assertTrue(activeByProgram.getOrDefault("composite", Set.of()).contains("gaux3"),
                    "MakeUp 'composite' must actively sample gaux3 (the crash trigger); composite active="
                            + activeByProgram.get("composite"));
            Set<String> activeLegacy = new LinkedHashSet<>(activeMappedTargets);
            activeLegacy.removeIf(name -> name.startsWith("colortex"));
            assertTrue(activeLegacy.contains("gaux3"),
                    "MakeUp graph programs must actively sample gaux3 (the crash trigger); active legacy="
                            + activeLegacy);
            assertTrue(activeLegacy.containsAll(EXPECTED_LEGACY_ACTIVE),
                    "MakeUp must exercise gaux1-4; active legacy=" + activeLegacy);
            System.out.println("[makeup-sampler-coverage] linked=" + checked.size()
                    + " targetCount=" + targetCount
                    + " shadowColorCount=" + shadowColorCount
                    + " activeLegacy=" + activeLegacy
                    + " activeMappedTargets=" + activeMappedTargets);
        }
    }

    private static Set<String> inspect(
            final String kind,
            final ProgramSource source,
            final TextureStage stage,
            final IrisMetalGlslLinker.LinkedRasterProgram linked,
            final int targetCount,
            final int shadowColorCount,
            final Map<TextureStage, ?> customTextures,
            final List<String> checked,
            final Set<String> activeMappedTargets,
            final boolean rasterProgram
    ) {
        Set<String> used = usedSamplerNames(linked);
        Set<String> activeNames = new LinkedHashSet<>();
        for (IrisMetalGlslLinker.SamplerDecl sampler
                : IrisMetalExecutionGraph.activeSampledDeclarations(linked.samplers(), used)) {
            activeNames.add(sampler.name());
            if (IrisMetalRenderTargets.renderTargetIndex(sampler.name()) >= 0) {
                activeMappedTargets.add(sampler.name());
            }
            assertTrue(
                    canResolve(sampler.name(), targetCount, shadowColorCount, customTextures.get(stage), rasterProgram),
                    source.getName() + " actively samples '" + sampler.name()
                            + "' but the binding table cannot resolve it"
            );
        }
        assertEquals(used, activeNames,
                source.getName() + " active sampler set disagrees with the linked usage set");
        checked.add(kind + ":" + source.getName());
        return activeNames;
    }

    /**
     * Samplers sampled by the linked source, excluding their declarations. This
     * mirrors the per-stage active set the shaderpack compiler derives from
     * SPIRV-Cross reflection (which needs the native library).
     */
    private static Set<String> usedSamplerNames(final IrisMetalGlslLinker.LinkedRasterProgram linked) {
        List<String> declared = new ArrayList<>();
        for (IrisMetalGlslLinker.SamplerDecl sampler : linked.samplers()) {
            if (sampler.sampled()) {
                declared.add(sampler.name());
            }
        }
        Set<String> used = new LinkedHashSet<>();
        for (String name : declared) {
            Pattern use = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
            if (countUses(linked.vertexGlsl(), name, use) + countUses(linked.fragmentGlsl(), name, use) > 0) {
                used.add(name);
            }
        }
        return used;
    }

    private static int countUses(final String source, final String name, final Pattern use) {
        int declarations = 0;
        Matcher declaration = DECL.matcher(source);
        while (declaration.find()) {
            if (declaration.group(2).equals(name)) {
                declarations++;
            }
        }
        int total = 0;
        Matcher occurrence = use.matcher(source);
        while (occurrence.find()) {
            total++;
        }
        return total - declarations;
    }

    /**
     * Name rules shared by {@code IrisMetalExecutionGraph.textureBinding} and
     * the world/terrain raster bridges. All route through
     * {@link IrisMetalRenderTargets#renderTargetIndex}: an in-range legacy
     * alias binds its read view and an out-of-range alias falls back to the
     * white pixel (the graph keeps the strict throw for explicit {@code
     * colortexN} only). The raster bridges additionally own the
     * vanilla/terrain alias names.
     */
    private static boolean canResolve(
            final String name,
            final int targetCount,
            final int shadowColorCount,
            final Object customStageTextures,
            final boolean rasterProgram
    ) {
        if (rasterProgram && (IrisMetalWorldBridge.isAlbedoAlias(name)
                || name.equals("iris_overlay") || name.equals("lightmap")
                || name.equals("u_BlockTex") || name.equals("u_LightTex"))) {
            return true;
        }
        if (name.equals(IrisMetalCenterDepthSampler.SAMPLER_NAME) || name.equals("centerDepthSmooth")
                || name.equals("noisetex")
                || name.equals("depthtex0") || name.equals("depthtex1") || name.equals("depthtex2")) {
            return true;
        }
        if (name.startsWith("shadowtex")) {
            return true;
        }
        if (name.startsWith("shadowcolor")) {
            int color = name.equals("shadowcolor") ? 0 : suffix(name, "shadowcolor");
            return color >= 0 && color < shadowColorCount;
        }
        if (IrisMetalRenderTargets.renderTargetIndex(name) >= 0) {
            // Explicit colortexN must exist; a legacy alias additionally
            // tolerates an out-of-range target by binding the white pixel.
            return name.startsWith("colortex")
                    ? IrisMetalRenderTargets.renderTargetIndex(name) < targetCount
                    : true;
        }
        int image = suffix(name, "colorimg");
        if (image >= 0) {
            return image < targetCount;
        }
        return customStageTextures instanceof Map<?, ?> map && map.containsKey(name);
    }

    private static int suffix(final String name, final String prefix) {
        if (!name.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
