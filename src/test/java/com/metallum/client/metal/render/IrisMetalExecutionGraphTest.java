package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IrisMetalExecutionGraphTest {
    @Test
    void drawBuffersFlipBeforeExplicitTrueFlip() {
        BitSet before = new BitSet();
        IrisMetalExecutionGraph.FlipTransition transition = IrisMetalExecutionGraph.transition(
                before, new int[]{0}, Map.of(0, true), 2
        );

        assertEquals(new BitSet(), transition.readsFromAlt());
        assertEquals(new BitSet(), transition.stateAfter(), "the two toggles cancel");
    }

    @Test
    void explicitFalseSuppressesImplicitDrawBuffersFlip() {
        IrisMetalExecutionGraph.FlipTransition transition = IrisMetalExecutionGraph.transition(
                new BitSet(), new int[]{1}, Map.of(1, false), 2
        );

        assertEquals(new BitSet(), transition.stateAfter());
    }

    @Test
    void invalidAndRepeatedDrawBuffersFailClosed() {
        assertThrows(
                IllegalStateException.class,
                () -> IrisMetalExecutionGraph.validateDrawBuffers("bad", new int[]{2}, 2)
        );
        assertThrows(
                IllegalStateException.class,
                () -> IrisMetalExecutionGraph.validateDrawBuffers("duplicate", new int[]{0, 0}, 2)
        );
    }

    @Test
    void explicitFlipTargetRangeIsStrict() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IrisMetalExecutionGraph.transition(
                        new BitSet(), new int[]{0}, Map.of(2, true), 2
                )
        );
    }

    @Test
    void rasterStorageBindingsKeepLogicalSsboIdentity() {
        String descriptor = MetalCrossShaderCompiler.storageBufferDescriptorName(7, "voxelData");
        assertEquals("iris_ssbo/7/voxelData", descriptor);
        assertEquals(7, MetalCrossShaderCompiler.storageBufferLogicalBinding(descriptor));
        assertEquals(-1, MetalCrossShaderCompiler.storageBufferLogicalBinding("voxelData"));
    }

    @Test
    void linkerDistinguishesStorageImagesFromSampledSamplers() {
        assertEquals(
                true,
                new IrisMetalGlslLinker.SamplerDecl("lightimg0", "image3D").storageImage()
        );
        assertEquals(
                false,
                new IrisMetalGlslLinker.SamplerDecl("voxeltex", "sampler3D").storageImage()
        );
    }

    /**
     * The {@code beginFrame} aliasing regression: {@code executeStage} assigns
     * {@code state = plan.stateAfter()} and the next frame's {@code beginFrame}
     * clears that graph field in place. With the record's default accessor the
     * clear also wiped the plan's own bookmark, so every later frame ran with
     * an empty flip state: MakeUp's exposure history was never canonicalized
     * back to {@code main} and its output stayed black. The accessors (and the
     * compact constructor) must copy.
     */
    @Test
    void rasterPlanAccessorsReturnIndependentSnapshots() {
        BitSet reads = new BitSet();
        reads.set(1);
        BitSet after = new BitSet();
        after.set(4);
        after.set(6);
        IrisMetalExecutionGraph.RasterPlan plan = new IrisMetalExecutionGraph.RasterPlan(
                IrisMetalExecutionGraph.Stage.COMPOSITE, 0, "composite", null,
                new int[]{0, 3}, reads, after, "token"
        );

        // Constructor copies: reusing/clearing the caller's BitSets is safe.
        reads.clear();
        after.clear();
        assertTrue(plan.readsFromAlt().get(1), "constructor must copy readsFromAlt");
        assertTrue(plan.stateAfter().get(6), "constructor must copy stateAfter");

        // The exact bug: the graph field aliases the accessor result, then
        // beginFrame() clears it in place.
        BitSet state = plan.stateAfter();
        state.clear();
        assertTrue(plan.stateAfter().get(4), "stateAfter must survive an in-place clear");
        assertTrue(plan.stateAfter().get(6), "stateAfter must survive an in-place clear");
        BitSet shadowState = plan.readsFromAlt();
        shadowState.clear();
        assertTrue(plan.readsFromAlt().get(1), "readsFromAlt must survive an in-place clear");

        // Callers cannot reach the plan's draw-buffer array either.
        int[] buffers = plan.drawBuffers();
        buffers[0] = 99;
        assertEquals(0, plan.drawBuffers()[0], "drawBuffers must return a copy");
    }
}
