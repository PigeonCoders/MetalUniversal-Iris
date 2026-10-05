package com.metallum.client.metal.render;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native-free frame-flow gate for the MakeUp-UltraFast black-screen report.
 *
 * <p>MakeUp is the first tested pack that (a) has a {@code prepare} program
 * writing {@code colortex1 + gaux4}, and (b) reads its {@code gaux3}
 * auto-exposure history in the <em>vertex</em> stage of {@code composite} and
 * {@code final}. The exposure loop is
 * {@code exposure = mix(f(scene), texture(gaux3, .5), exp(-frameTime*1.25))},
 * so if the previous frame's {@code gaux3} write is not visible to the next
 * frame's composite, exposure collapses to {@code (1-k)*f} (~2% of the scene)
 * and only saturated sky/sun pixels survive — the reported "nearly black
 * screen, sky flashing on camera movement" symptom.
 *
 * <p>This test runs the graph's real plans (no Metal device) through a symbolic
 * two-frame ping-pong simulation that mirrors {@code executeStage},
 * {@code executeFinal}'s end-of-frame canonicalization and the frame-start
 * history canonicalization, and pins:
 * <ul>
 *   <li>{@code prepare} is planned with {@code DRAWBUFFERS:17} (colortex1 +
 *       gaux4);</li>
 *   <li>{@code composite} reads {@code gaux3} from the main side and writes
 *       the new exposure to the alt side (then canonicalized to main);</li>
 *   <li>frame N+1's composite sees frame N's exposure — not a cleared
 *       buffer;</li>
 *   <li>{@code final} reads the same-frame {@code composite} exposure and the
 *       same-frame {@code composite2} scene output.</li>
 * </ul>
 */
final class MakeUpFrameFlowTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "MakeUp-UltraFast-9.5f.zip");
    private static final Map<String, String> NO_EFFECTS = Map.of(
            "AA_TYPE", "0", "BLOOM", "false", "DOF", "false", "MOTION_BLUR", "false"
    );

    @Test
    void makeUpFrameFlowKeepsExposureHistoryAndSceneSides() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);
        Iris.testing = true;

        // Default (bloom on): composite writes bloom + scene + exposure.
        assertFlow("default", Map.of(), new int[]{0, 1, 6});
        // no_effects profile approximation (AA/DOF/BLOOM/motion-blur off):
        // composite writes scene + exposure only.
        assertFlow("no_effects", NO_EFFECTS, new int[]{1, 6});
    }

    private static void assertFlow(
            final String label,
            final Map<String, String> config,
            final int[] expectedCompositeBuffers
    ) throws Exception {
        try (FileSystem fs = FileSystems.newFileSystem(PACK, Map.of())) {
            ShaderPack pack = new ShaderPack(
                    fs.getPath("/shaders"), config,
                    StandardMacros.createStandardEnvironmentDefines(), false
            );
            ProgramSet programSet = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
            int targetCount = IrisMetalRenderTargetFormats.from(programSet.getPackDirectives()).length;
            try (IrisMetalWorldPrograms programs = new IrisMetalWorldPrograms(1, programSet)) {
                IrisMetalExecutionGraph graph =
                        new IrisMetalExecutionGraph(1, programSet, programs, targetCount);
                List<IrisMetalExecutionGraph.PlannedPass> passes = graph.plannedPasses();

                IrisMetalExecutionGraph.PlannedPass prepare = pass(passes, "prepare");
                assertNotNull(prepare, label + ": MakeUp prepare was not planned");
                assertEquals("[1, 7]", Arrays.toString(prepare.drawBuffers()),
                        label + ": prepare must write colortex1 (main) + gaux4 (sky/fog)");

                IrisMetalExecutionGraph.PlannedPass composite = pass(passes, "composite");
                assertNotNull(composite, label + ": MakeUp composite was not planned");
                assertEquals(Arrays.toString(expectedCompositeBuffers),
                        Arrays.toString(composite.drawBuffers()),
                        label + ": composite draw buffers");

                String[][] side = new String[targetCount][2]; // [target][0=main, 1=alt]
                BitSet flipped = new BitSet();
                String frame1CompositeRead = null;
                String frame1CompositeWrite = null;
                String frame2CompositeRead = null;
                String frame2FinalExposure = null;
                String frame2FinalScene = null;

                for (int frame = 1; frame <= 2; frame++) {
                    // beginFrame: canonicalize the previous frame's read side
                    // into main, then reset the per-frame flip state.
                    for (int t = flipped.nextSetBit(0); t >= 0; t = flipped.nextSetBit(t + 1)) {
                        side[t][0] = side[t][1];
                    }
                    flipped.clear();
                    if (frame == 1) {
                        for (int t = 0; t < targetCount; t++) {
                            side[t][0] = "clear";
                            side[t][1] = "clear";
                        }
                    }

                    for (IrisMetalExecutionGraph.PlannedPass pass : passes) {
                        int[] buffers = pass.drawBuffers();
                        BitSet reads = pass.readsFromAlt();
                        if (pass.name().equals("deferred")) {
                            // World gbuffer draws target readView(1) before deferred.
                            side[1][flipped.get(1) ? 1 : 0] = "f" + frame + ":gbuffers";
                        }
                        if (pass.name().equals("composite")) {
                            // Translucent world draws target readView(1) before composite.
                            side[1][flipped.get(1) ? 1 : 0] = "f" + frame + ":translucent";
                        }
                        if (pass.name().equals("composite")) {
                            String read = side[6][reads.get(6) ? 1 : 0];
                            if (frame == 1) {
                                frame1CompositeRead = read;
                            } else {
                                frame2CompositeRead = read;
                            }
                        }
                        if (pass.stage().equals("FINAL")) {
                            // The final pass renders to the main target (override color);
                            // it reads with its snapshot but writes no colortex.
                            if (frame == 2) {
                                frame2FinalExposure = side[6][reads.get(6) ? 1 : 0];
                                frame2FinalScene = side[1][reads.get(1) ? 1 : 0];
                            }
                            flipped.clear();
                            flipped.or(pass.stateAfter());
                            continue;
                        }
                        for (int t : buffers) {
                            int writeSide = reads.get(t) ? 0 : 1;
                            side[t][writeSide] = "f" + frame + ":" + pass.name();
                            if (frame == 1 && pass.name().equals("composite") && t == 6) {
                                frame1CompositeWrite = side[t][writeSide];
                            }
                        }
                        flipped.clear();
                        flipped.or(pass.stateAfter());
                    }
                    // executeFinal: canonicalize every flipped target read -> main.
                    for (int t = flipped.nextSetBit(0); t >= 0; t = flipped.nextSetBit(t + 1)) {
                        side[t][0] = side[t][1];
                    }
                }

                assertEquals("clear", frame1CompositeRead,
                        label + ": frame 1 composite must read the cleared gaux3 history");
                assertEquals("f1:composite", frame1CompositeWrite,
                        label + ": frame 1 composite must write the new exposure to gaux3");
                assertEquals("f1:composite", frame2CompositeRead,
                        label + ": frame 2 composite must read frame 1's exposure, not the cleared side"
                                + " (exposure would collapse to (1-k)*f and render black)");
                assertEquals("f2:composite", frame2FinalExposure,
                        label + ": final must read the same-frame composite exposure");
                assertEquals("f2:composite2", frame2FinalScene,
                        label + ": final must read the same-frame composite2 scene output");
                assertTrue(flipped.get(6),
                        label + ": the end-of-frame state must mark gaux3 so the history"
                                + " canonicalization covers it");
                System.out.println("[makeup-frame-flow] " + label
                        + " prepare=" + Arrays.toString(prepare.drawBuffers())
                        + " composite=" + Arrays.toString(composite.drawBuffers())
                        + " frame2Reads=" + frame2CompositeRead
                        + " finalExposure=" + frame2FinalExposure
                        + " finalScene=" + frame2FinalScene);
            }
        }
    }

    private static IrisMetalExecutionGraph.PlannedPass pass(
            final List<IrisMetalExecutionGraph.PlannedPass> passes,
            final String name
    ) {
        for (IrisMetalExecutionGraph.PlannedPass pass : passes) {
            if (pass.name().equals(name)) {
                return pass;
            }
        }
        return null;
    }
}
