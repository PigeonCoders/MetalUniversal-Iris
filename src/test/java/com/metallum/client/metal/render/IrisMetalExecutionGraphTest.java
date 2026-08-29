package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void legacyGbufferAliasesMapToIrisColorTargets() {
        assertEquals(0, IrisMetalExecutionGraph.legacyColorTarget("gcolor"));
        assertEquals(1, IrisMetalExecutionGraph.legacyColorTarget("gdepth"));
        assertEquals(2, IrisMetalExecutionGraph.legacyColorTarget("gnormal"));
        assertEquals(3, IrisMetalExecutionGraph.legacyColorTarget("composite"));
        assertEquals(4, IrisMetalExecutionGraph.legacyColorTarget("gaux1"));
        assertEquals(5, IrisMetalExecutionGraph.legacyColorTarget("gaux2"));
        assertEquals(6, IrisMetalExecutionGraph.legacyColorTarget("gaux3"));
        assertEquals(7, IrisMetalExecutionGraph.legacyColorTarget("gaux4"));
        assertEquals(-1, IrisMetalExecutionGraph.legacyColorTarget("colortex4"));
        assertEquals(-1, IrisMetalExecutionGraph.legacyColorTarget("unknown"));
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
}
