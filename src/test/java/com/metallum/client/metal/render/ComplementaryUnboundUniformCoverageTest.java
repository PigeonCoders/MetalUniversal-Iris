package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.BiomeUniforms;
import net.irisshaders.iris.uniforms.CameraUniforms;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.IdMapUniforms;
import net.irisshaders.iris.uniforms.IrisTimeUniforms;
import net.irisshaders.iris.uniforms.MatrixUniforms;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.irisshaders.iris.uniforms.ViewportUniforms;
import net.irisshaders.iris.uniforms.WorldTimeUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the Complementary Unbound r5.9.3 prewarm crash family:
 * its {@code shaders.properties:269-275} biome flags
 * ({@code inNetherWastes}, {@code inCrimsonForest}, {@code inWarpedForest},
 * {@code inBasaltDeltas}, {@code inSoulValley}, {@code inPaleGarden},
 * {@code inSulfurCaves}) are defined as
 * {@code smooth(..., if(in(biome, BIOME_*), 1, 0), ...)}, but no upstream Iris
 * build (pinned 20e226b included) supplies the {@code BIOME_*} constants.
 * Stareval therefore drops the variables, desktop GL leaves the declared
 * uniforms at the GLSL default 0, and the port's strict prewarm used to throw
 * {@code has no Metal or Iris value source}. The writer now answers them with
 * the faithful 0; if upstream ever adds the constants the custom-uniform graph
 * resolves first and the fallback becomes inert.
 *
 * <p>The test mirrors {@code MetalWorldRenderingPipeline.prepareWorldUniforms}
 * — the six sodium terrain keys plus every
 * {@link IrisMetalWorldBridge#WORLD_OVERRIDE_KEYS} entry — and the graph
 * programs (deferred/composite/final), across the three dimension program sets
 * and the shipped vs {@code RP_MODE=3} option sets, feeding every linked
 * member through the production value-source resolution with the pack's real
 * {@link CustomUniforms} graph. A member counts as rejected only when neither
 * a writer case nor the graph supplies it.
 */
final class ComplementaryUnboundUniformCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "ComplementaryUnbound.zip");
    /** The seven biome smooth-flags the crash family covers. */
    private static final Set<String> BIOME_FLAGS = Set.of(
            "inNetherWastes", "inCrimsonForest", "inWarpedForest",
            "inBasaltDeltas", "inSoulValley", "inPaleGarden", "inSulfurCaves"
    );
    /** The six keys {@code prepareWorldUniforms} prewarms first. */
    private static final ShaderKey[] SODIUM_KEYS = {
            ShaderKey.SODIUM_TERRAIN_SOLID,
            ShaderKey.SODIUM_TERRAIN_CUTOUT,
            ShaderKey.SODIUM_TERRAIN_TRANSLUCENT,
            ShaderKey.SHADOW_SODIUM_TERRAIN_SOLID,
            ShaderKey.SHADOW_SODIUM_TERRAIN_CUTOUT,
            ShaderKey.SHADOW_SODIUM_TERRAIN_TRANSLUCENT
    };
    /**
     * Core-draw transforms that {@code upload()} skips for Mojang-core tokens
     * and {@code materializeDrawUniforms()} fills from the bound
     * DynamicTransforms/Projection blocks; the switch is never their source.
     * Mirrors the writer's private core-draw constants.
     */
    private static final Set<String> CORE_DRAW_UNIFORMS = Set.of(
            "iris_ModelViewMatInverse", "iris_ProjMatInverse", "iris_NormalMat"
    );
    /** {@code shaders.properties} option in parentheses. */
    private static final Map<String, Map<String, String>> CONFIGS = new LinkedHashMap<>();
    /** Program sets the port can create; the crash family is per-dimension. */
    private static final Map<String, NamespacedId> DIMENSIONS = new LinkedHashMap<>();

    static {
        CONFIGS.put("default", Map.of());
        CONFIGS.put("RP_MODE=3", Map.of("RP_MODE", "3"));
        DIMENSIONS.put("overworld", new NamespacedId("minecraft", "overworld"));
        DIMENSIONS.put("nether", new NamespacedId("minecraft", "the_nether"));
        DIMENSIONS.put("end", new NamespacedId("minecraft", "the_end"));
    }

    @Test
    void unboundWorldUniformBlocksHaveNoUnsupportedMembers() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        Map<String, Audit> audits = new LinkedHashMap<>();
        for (Map.Entry<String, NamespacedId> dimension : DIMENSIONS.entrySet()) {
            for (Map.Entry<String, Map<String, String>> config : CONFIGS.entrySet()) {
                audits.put(
                        dimension.getKey() + "/" + config.getKey(),
                        audit(dimension.getValue(), config.getValue())
                );
            }
        }

        for (Map.Entry<String, Audit> entry : audits.entrySet()) {
            System.out.println("[unbound-uniform-coverage] config=" + entry.getKey()
                    + " linkedPrograms=" + entry.getValue().linkedPrograms
                    + " declared=" + entry.getValue().declared.size()
                    + " graphSupplied=" + entry.getValue().graphSupplied
                    + " rejected=" + entry.getValue().rejected);
        }

        for (Map.Entry<String, Audit> entry : audits.entrySet()) {
            Audit audit = entry.getValue();
            assertTrue(audit.linkedPrograms > 0,
                    "Unbound '" + entry.getKey() + "': no program linked at all");
            assertTrue(audit.rejected.isEmpty(),
                    "Unbound '" + entry.getKey() + "' uniforms have neither a writer case"
                            + " nor an Iris graph source: " + audit.rejected
                            + " (graph-supplied declared names: " + audit.graphSupplied + ")");
        }

        // Every crashed biome flag must stay declared (so this gate keeps
        // exercising them) and writer-handled wherever it appears.
        Set<String> seenFlags = new LinkedHashSet<>();
        for (Map.Entry<String, Audit> entry : audits.entrySet()) {
            for (String flag : BIOME_FLAGS) {
                if (entry.getValue().declared.contains(flag)) {
                    seenFlags.add(flag);
                    assertFalse(entry.getValue().unsupported.contains(flag),
                            flag + " must have a writer value source in '" + entry.getKey() + "'");
                }
            }
        }
        Set<String> missingFlags = new LinkedHashSet<>(BIOME_FLAGS);
        missingFlags.removeAll(seenFlags);
        assertEquals(BIOME_FLAGS, seenFlags,
                "the gate must exercise every crashed biome flag; missing=" + missingFlags);

        // Pin the exact writer path with synthetic members (layout-independent):
        // the neutral frame must answer each flag with the GLSL default 0.
        Audit overworld = audits.get("overworld/default");
        for (String flag : BIOME_FLAGS) {
            ByteBuffer bytes = overworld.relaxed.writeUniformForGate(
                    new IrisMetalGlslLinker.UniformMember("float", flag, 0, 0, 4)
            );
            assertFalse(overworld.relaxed.unsupportedNames().contains(flag),
                    flag + " must be answered by the writer's explicit default");
            assertEquals(0.0f, bytes.getFloat(0), flag + " default must be 0");
        }
    }

    private static Audit audit(
            final NamespacedId dimension,
            final Map<String, String> changedConfigs
    ) throws Exception {
        try (FileSystem fileSystem = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fileSystem.getPath("/shaders"),
                    changedConfigs,
                    StandardMacros.createStandardEnvironmentDefines(),
                    false
            );
            ProgramSet programs = pack.getProgramSet(dimension);
            FrameUpdateNotifier notifier = new FrameUpdateNotifier();
            CustomUniforms graph = pack.customUniforms.build(
                    holder -> registerTestInputUniforms(holder, pack, programs, notifier)
            );
            Audit audit = new Audit(
                    graph,
                    new IrisMetalUniformValues(0.0f, () -> 0)
            );

            try (IrisMetalWorldPrograms worldPrograms = new IrisMetalWorldPrograms(1, programs)) {
                for (ShaderKey key : SODIUM_KEYS) {
                    audit.inspect(worldPrograms.sodium(key.getProgram(), key.getAlphaTest()), false);
                }
                for (ShaderKey key : IrisMetalWorldBridge.WORLD_OVERRIDE_KEYS) {
                    IrisMetalWorldBridge.ProgramRequest request =
                            IrisMetalWorldBridge.shaderKeyToProgramRequest(key);
                    audit.inspect(
                            worldPrograms.vanilla(
                                    request.program(),
                                    request.alphaTest(),
                                    request.lines(),
                                    request.clouds(),
                                    request.inputs()
                            ),
                            IrisMetalUniformValues.usesMojangCoreTransforms(key)
                    );
                }
                for (ProgramSource source : programs.getComposite(ProgramArrayId.Deferred)) {
                    if (source != null && source.isValid()) {
                        audit.inspect(Optional.of(worldPrograms.composite(
                                source, TextureStage.DEFERRED
                        )), false);
                    }
                }
                for (ProgramSource source : programs.getComposite(ProgramArrayId.Composite)) {
                    if (source != null && source.isValid()) {
                        audit.inspect(Optional.of(worldPrograms.composite(
                                source, TextureStage.COMPOSITE_AND_FINAL
                        )), false);
                    }
                }
                IrisMetalGlslLinker.LinkedRasterProgram finalProgram = worldPrograms.finalProgram();
                if (finalProgram != null) {
                    audit.inspect(Optional.of(finalProgram), false);
                }
            }
            return audit;
        }
    }

    /**
     * Registers the official Iris inputs the pack's custom uniforms can
     * reference, mirroring {@code CommonUniforms.addNonDynamicUniforms} minus
     * its {@code IrisExclusiveUniforms} block: that one dereferences
     * {@code Minecraft.getInstance().level} while registering and cannot run
     * headless. The manual registrations below mirror the
     * {@code generalCommonUniforms} names Unbound's world layouts declare.
     */
    private static void registerTestInputUniforms(
            final UniformHolder holder,
            final ShaderPack pack,
            final ProgramSet programs,
            final FrameUpdateNotifier notifier
    ) {
        PackDirectives directives = programs.getPackDirectives();
        CameraUniforms.addCameraUniforms(holder, notifier);
        ViewportUniforms.addViewportUniforms(holder);
        WorldTimeUniforms.addWorldTimeUniforms(holder);
        SystemTimeUniforms.addSystemTimeUniforms(holder);
        BiomeUniforms.addBiomeUniforms(holder);
        new CelestialUniforms(directives.getSunPathRotation()).addCelestialUniforms(holder);
        IrisTimeUniforms.addTimeUniforms(holder);
        MatrixUniforms.addMatrixUniforms(holder, directives);
        IdMapUniforms.addIdMapUniforms(notifier, holder, pack.getIdMap(), directives.isOldHandLight());
        // CommonUniforms.generalCommonUniforms and
        // IrisExclusiveUniforms.addIrisExclusiveUniforms cannot run headless
        // (they dereference Minecraft.getInstance() while registering), so
        // register the names Unbound's custom uniforms and world layouts take
        // from them: eyeBrightness/rainStrength/isEyeInWater/is_invisible from
        // generalCommonUniforms (isEyeInCave and the smoothed eyeBrightness
        // variables resolve through them) and playerLookVector from
        // IrisExclusiveUniforms.
        holder.uniform2i(UniformUpdateFrequency.PER_FRAME, "eyeBrightness", () -> new Vector2i());
        holder.uniform1i(UniformUpdateFrequency.PER_FRAME, "isEyeInWater", () -> 0);
        holder.uniform1f(UniformUpdateFrequency.PER_TICK, "rainStrength", () -> 0.0f);
        holder.uniform1b(UniformUpdateFrequency.PER_FRAME, "is_invisible", () -> false);
        holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "blindness", () -> 0.0f);
        holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "darknessLightFactor", () -> 0.0f);
        holder.uniform3d(UniformUpdateFrequency.PER_FRAME, "playerLookVector", () -> new Vector3d());
    }

    /** Mutable audit accumulator. */
    private static final class Audit {
        private final CustomUniforms graph;
        private final IrisMetalUniformValues relaxed;
        private final Set<IrisMetalGlslLinker.LinkedRasterProgram> checked =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<String> declared = new LinkedHashSet<>();
        private final Set<String> graphSupplied = new LinkedHashSet<>();
        private final Set<String> unsupported = new LinkedHashSet<>();
        private final Set<String> rejected = new LinkedHashSet<>();
        private int linkedPrograms;

        private Audit(final CustomUniforms graph, final IrisMetalUniformValues relaxed) {
            this.graph = graph;
            this.relaxed = relaxed;
        }

        private void inspect(
                final Optional<IrisMetalGlslLinker.LinkedRasterProgram> linked,
                final boolean coreTransforms
        ) {
            if (linked.isEmpty() || !checked.add(linked.get())) {
                return;
            }
            this.linkedPrograms++;
            for (IrisMetalGlslLinker.UniformMember member : linked.get().uniformLayout()) {
                if (coreTransforms && CORE_DRAW_UNIFORMS.contains(member.name())) {
                    continue;
                }
                this.declared.add(member.name());
                this.relaxed.writeUniformForGate(member);
                boolean graphHas = this.graph.hasVariable(member.name());
                if (graphHas) {
                    this.graphSupplied.add(member.name());
                } else if (this.relaxed.unsupportedNames().contains(member.name())) {
                    this.unsupported.add(member.name());
                    this.rejected.add(member.name() + " (" + member.type() + ")");
                }
            }
        }
    }
}
