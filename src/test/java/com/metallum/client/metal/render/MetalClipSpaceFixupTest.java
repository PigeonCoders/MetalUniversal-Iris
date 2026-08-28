package com.metallum.client.metal.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JVM coverage for the post-SPIRV-Cross clip-space Z remap. SPIRV-Cross
 * emits a Y-flip line when FLIP_VERTEX_Y is enabled but has no exposed
 * fixup_clipspace option, so {@code z' = (z + w) * 0.5} must be injected there.
 * Without it Metal clips every vertex with GL clip-space z &lt; 0 (the front half
 * of the frustum), which manifests as see-through terrain.
 */
final class MetalClipSpaceFixupTest {
    @Test
    void injectsZRemapBeforeEveryYFlipLine() {
        String msl = """
                vertex main0_out main0(main0_in in [[stage_in]])
                {
                    main0_out out = {};
                    out.gl_Position = in.inPos;
                    out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal
                    if (in.inPos.w > 0.5)
                    {
                        out.gl_Position = in.inPos * 2.0;
                        out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal
                    }
                    return out;
                }
                """;

        String fixed = MetalMslClipSpace.fixup(msl);

        String expected = """
                vertex main0_out main0(main0_in in [[stage_in]])
                {
                    main0_out out = {};
                    out.gl_Position = in.inPos;
                    out.gl_Position.z = (out.gl_Position.z + out.gl_Position.w) * 0.5;    // Adjust clip-space for Metal
                    out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal
                    if (in.inPos.w > 0.5)
                    {
                        out.gl_Position = in.inPos * 2.0;
                        out.gl_Position.z = (out.gl_Position.z + out.gl_Position.w) * 0.5;    // Adjust clip-space for Metal
                        out.gl_Position.y = -(out.gl_Position.y);    // Invert Y-axis for Metal
                    }
                    return out;
                }
                """;
        assertEquals(expected, fixed);
        assertTrue(fixed.contains("out.gl_Position.z = (out.gl_Position.z + out.gl_Position.w) * 0.5"));
    }

    @Test
    void isIdempotentForAlreadyRemappedMsl() {
        String fixed = MetalMslClipSpace.fixup(
                "out.gl_Position.z = (out.gl_Position.z + out.gl_Position.w) * 0.5;\n"
                        + "out.gl_Position.y = -(out.gl_Position.y);\n"
        );
        assertSame(fixed, MetalMslClipSpace.fixup(fixed));
    }

    @Test
    void leavesNullAndFragmentMslUntouched() {
        assertNull(MetalMslClipSpace.fixup(null));
        String fragment = "fragment float4 main0(main0_in in [[stage_in]]) { return in.color; }";
        assertSame(fragment, MetalMslClipSpace.fixup(fragment));
    }
}
