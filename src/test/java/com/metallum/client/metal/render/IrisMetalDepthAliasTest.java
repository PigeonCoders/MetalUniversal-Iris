package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic gate for the {@code depthtex0} zero-copy alias
 * ({@code -Dmetallum.iris.depthAlias=off} rolls it back). The alias may only
 * be used when the vanilla main depth has the pack-visible D32_FLOAT format,
 * the Metal ShaderRead usage bit, the Iris base extent, a live generation, and
 * the non-legacy {@code worldPass.depthVanilla} path. Each failure keeps the
 * full-frame copy, so the five conditions are tested one at a time plus the
 * all-satisfied case. No Metal device is touched.
 */
final class IrisMetalDepthAliasTest {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    /** Same bits the vanilla MainTarget/RenderTarget passes at creation (usage 15). */
    private static final int MAIN_DEPTH_USAGE = GpuTexture.USAGE_COPY_DST
            | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_RENDER_ATTACHMENT;

    private static String blocker(
            final GpuFormat format,
            final int usage,
            final int mainWidth,
            final int mainHeight,
            final boolean legacyDepthPath,
            final boolean generationValid
    ) {
        return IrisMetalRenderTargets.depthAliasBlocker(
                format, usage,
                mainWidth, mainHeight,
                WIDTH, HEIGHT,
                legacyDepthPath, generationValid
        );
    }

    @Test
    void allRequirementsSatisfiedAllowsTheAlias() {
        assertNull(blocker(GpuFormat.D32_FLOAT, MAIN_DEPTH_USAGE, WIDTH, HEIGHT, false, true));
    }

    @Test
    void nonD32FormatKeepsTheCopy() {
        assertEquals(
                "format=" + GpuFormat.D32_FLOAT_S8_UINT,
                blocker(GpuFormat.D32_FLOAT_S8_UINT, MAIN_DEPTH_USAGE, WIDTH, HEIGHT, false, true)
        );
        assertEquals(
                "format=" + GpuFormat.R32_FLOAT,
                blocker(GpuFormat.R32_FLOAT, MAIN_DEPTH_USAGE, WIDTH, HEIGHT, false, true),
                "a color-like R32 float cannot stand in for depth"
        );
    }

    @Test
    void missingTextureBindingUsageKeepsTheCopy() {
        int noShaderRead = MAIN_DEPTH_USAGE & ~GpuTexture.USAGE_TEXTURE_BINDING;
        assertTrue(
                blocker(GpuFormat.D32_FLOAT, noShaderRead, WIDTH, HEIGHT, false, true)
                        .startsWith("usage="),
                "Metal cannot sample a depth texture without the shader-read usage bit"
        );
    }

    @Test
    void extentMismatchKeepsTheCopy() {
        assertEquals(
                "extent=1280x720!=" + WIDTH + "x" + HEIGHT,
                blocker(GpuFormat.D32_FLOAT, MAIN_DEPTH_USAGE, 1280, 720, false, true)
        );
        assertEquals(
                "extent=" + WIDTH + "x" + (HEIGHT - 1) + "!=" + WIDTH + "x" + HEIGHT,
                blocker(GpuFormat.D32_FLOAT, MAIN_DEPTH_USAGE, WIDTH, HEIGHT - 1, false, true)
        );
    }

    @Test
    void legacyDepthVanillaPathKeepsTheCopy() {
        assertEquals(
                "legacy-depth",
                blocker(GpuFormat.D32_FLOAT, MAIN_DEPTH_USAGE, WIDTH, HEIGHT, true, true),
                "-Dmetallum.iris.worldPass.depthVanilla=false must keep the old Iris-depth behavior"
        );
    }

    @Test
    void deadGenerationKeepsTheCopy() {
        assertEquals(
                "generation",
                blocker(GpuFormat.D32_FLOAT, MAIN_DEPTH_USAGE, WIDTH, HEIGHT, false, false),
                "a closed generation (or closed engine depth) must never be aliased"
        );
    }

    @Test
    void legacyPathOutranksTheOtherReasons() {
        assertEquals(
                "legacy-depth",
                blocker(GpuFormat.R32_FLOAT, 0, 1, 1, true, false),
                "the probe should name the path that owns the behavior, not a downstream mismatch"
        );
    }
}
