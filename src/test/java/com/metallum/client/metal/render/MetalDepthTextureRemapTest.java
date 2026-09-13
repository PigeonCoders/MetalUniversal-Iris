package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MetalDepthTextureRemapTest {
    @Test
    void remapsDepthCapturesToScalarDepth2d() {
        String msl = """
                fragment main0_out main0(
                    texture2d<float> depthtex0 [[texture(1)]],
                    texture2d<float> depthtex1 [[texture(2)]],
                    sampler depthtex0Smplr [[sampler(1)]]
                ) {
                    float Depth = depthtex0.sample(depthtex0Smplr, float2(0.5)).x;
                    float TerrainDepth = depthtex1.sample(depthtex1Smplr, float2(0.5)).x;
                    return float4(Depth + TerrainDepth);
                }
                """;
        String fixed = MetalMslClipSpace.remapDepthTextureSamplers(msl);
        assertTrue(fixed.contains("depth2d<float> depthtex0 [[texture(1)]]"));
        assertTrue(fixed.contains("depth2d<float> depthtex1"));
        assertTrue(fixed.contains("depthtex0.sample(depthtex0Smplr, float2(0.5))"));
        assertFalse(fixed.contains("depthtex0.sample(depthtex0Smplr, float2(0.5)).x"));
        assertFalse(fixed.contains("texture2d<float> depthtex0"));
    }

    @Test
    void leavesOrdinaryTexturesUntouched() {
        String msl = "float x = colortex0.sample(colortex0Smplr, float2(0.5)).x;";
        assertEquals(msl, MetalMslClipSpace.remapDepthTextureSamplers(msl));
    }
}
