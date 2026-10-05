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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
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
 * Regression gate for the Sildur's Vibrant 2.02 prewarm crash
 * {@code Iris uniform 'isNether' (bool) has no Metal or Iris value source}.
 *
 * <p>{@code deferred.fsh} declares {@code uniform bool isNether;} (and the
 * per-dimension variants do too) but neither the pack nor upstream Iris
 * registers a supplier for it. OptiFine/Iris semantics for a declared uniform
 * with no supplier are the GLSL default; the port instead supplies the
 * faithful dimension answer (current world is the Nether), because Sildur's
 * uses the flag to switch its nether-specific math.
 *
 * <p>The test mirrors {@code MetalWorldRenderingPipeline.prepareWorldUniforms}
 * — the same sodium terrain keys and the same
 * {@link IrisMetalWorldBridge#WORLD_OVERRIDE_KEYS} — plus the graph programs
 * (composite + final), and feeds every linked member through the production
 * value-source resolution with the pack's real {@link CustomUniforms} graph.
 * A member counts as rejected only when neither a writer case nor the graph
 * supplies it, so a new Sildur's option cannot reintroduce the crash family
 * without failing here.
 */
final class SildursUniformCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "Sildurs.zip");
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
        CONFIGS.put("nMap=1", Map.of("nMap", "1"));
        DIMENSIONS.put("overworld", new NamespacedId("minecraft", "overworld"));
        DIMENSIONS.put("nether", new NamespacedId("minecraft", "the_nether"));
        DIMENSIONS.put("end", new NamespacedId("minecraft", "the_end"));
    }

    @Test
    void sildursWorldUniformBlocksHaveNoUnsupportedMembers() throws Exception {
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
            System.out.println("[sildurs-uniform-coverage] config=" + entry.getKey()
                    + " linkedPrograms=" + entry.getValue().linkedPrograms
                    + " declared=" + entry.getValue().declared.size()
                    + " graphSupplied=" + entry.getValue().graphSupplied
                    + " rejected=" + entry.getValue().rejected);
        }

        for (Map.Entry<String, Audit> entry : audits.entrySet()) {
            Audit audit = entry.getValue();
            assertTrue(audit.linkedPrograms > 0,
                    "Sildur's '" + entry.getKey() + "': no world program linked at all");
            assertTrue(audit.rejected.isEmpty(),
                    "Sildur's '" + entry.getKey() + "' world uniforms have neither a writer case"
                            + " nor an Iris graph source: " + audit.rejected
                            + " (graph-supplied declared names: " + audit.graphSupplied + ")");
        }

        // The crash trigger must stay declared and writer-handled in every
        // configuration, so removing the case cannot regress silently.
        for (Map.Entry<String, Audit> entry : audits.entrySet()) {
            assertTrue(entry.getValue().declared.contains("isNether"),
                    "Sildur's '" + entry.getKey() + "' layouts must declare isNether: "
                            + entry.getValue().declared);
            assertFalse(entry.getValue().unsupported.contains("isNether"),
                    "isNether must have a writer value source");
        }

        // Pin the exact writer path with a synthetic member (layout-independent):
        // the neutral frame is not the Nether, so the bool must write 0.
        Audit overworld = audits.get("overworld/default");
        ByteBuffer bytes = overworld.relaxed.writeUniformForGate(
                new IrisMetalGlslLinker.UniformMember("bool", "isNether", 0, 0, 4)
        );
        assertFalse(overworld.relaxed.unsupportedNames().contains("isNether"),
                "isNether must be answered by the writer's explicit case");
        assertEquals(0, bytes.getInt(0), "neutral frame must write isNether=false");
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
     * headless. Sildur's custom uniforms are {@code BiomeTemp=temperature}
     * (covered by {@link BiomeUniforms}) and {@code framemod8} (covered by
     * {@link SystemTimeUniforms}); the manual registrations below mirror the
     * {@code generalCommonUniforms} names its world layouts declare.
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
        // CommonUniforms.generalCommonUniforms cannot run headless (its
        // `client.gui.hud::isHidden` method reference dereferences
        // Minecraft.getInstance()). Register the names Sildur's world layouts
        // take from it; production builds the full graph in
        // MetalWorldRenderingPipeline.
        holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "blindness", () -> 0.0f);
        holder.uniform1f(UniformUpdateFrequency.PER_FRAME, "darknessLightFactor", () -> 0.0f);
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
