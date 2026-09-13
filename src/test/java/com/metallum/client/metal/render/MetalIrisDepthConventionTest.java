package com.metallum.client.metal.render;

import com.mojang.blaze3d.platform.CompareOp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Iris GL parity checks for Blaze3D reverse-Z. Shaderpacks expect conventional
 * depth: clear 1.0 (far), near=0, and LEQUAL. Blaze3D gives clear 0.0 (far),
 * near=1, and GREATER_EQUAL; every reverse-Z op must be mirrored.
 */
final class MetalIrisDepthConventionTest {
    @Test
    void invertsReverseZCompareOpsLikeIrisGlPath() {
        assertEquals(
                CompareOp.LESS_THAN_OR_EQUAL,
                MetalIrisDepthConvention.invertForConventionalDepth(CompareOp.GREATER_THAN_OR_EQUAL)
        );
        assertEquals(
                CompareOp.LESS_THAN,
                MetalIrisDepthConvention.invertForConventionalDepth(CompareOp.GREATER_THAN)
        );
        assertEquals(
                CompareOp.GREATER_THAN_OR_EQUAL,
                MetalIrisDepthConvention.invertForConventionalDepth(CompareOp.LESS_THAN_OR_EQUAL)
        );
        assertEquals(
                CompareOp.GREATER_THAN,
                MetalIrisDepthConvention.invertForConventionalDepth(CompareOp.LESS_THAN)
        );
    }

    @Test
    void leavesOrderIndependentOpsUntouched() {
        assertEquals(
                CompareOp.ALWAYS_PASS,
                MetalIrisDepthConvention.invertForConventionalDepth(CompareOp.ALWAYS_PASS)
        );
        assertEquals(
                CompareOp.NEVER_PASS,
                MetalIrisDepthConvention.invertForConventionalDepth(CompareOp.NEVER_PASS)
        );
        assertEquals(
                CompareOp.EQUAL,
                MetalIrisDepthConvention.invertForConventionalDepth(CompareOp.EQUAL)
        );
        assertEquals(
                CompareOp.NOT_EQUAL,
                MetalIrisDepthConvention.invertForConventionalDepth(CompareOp.NOT_EQUAL)
        );
    }
}
