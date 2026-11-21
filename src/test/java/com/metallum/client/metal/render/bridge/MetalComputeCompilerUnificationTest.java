package com.metallum.client.metal.render.bridge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the "one GLSL compiler per process" invariant.
 *
 * <p>Compute used to compile through LWJGL's shaderc while raster went through
 * the bundled glslang. On iOS that meant two glslang builds in one process, one
 * of which aborts the app while merely reporting a shader error (BSL 10.1.8:
 * {@code glslang::TParseContext::lValueErrorCheck}). Compute now uses the same
 * {@link GlslangBridge} the raster path uses; this test keeps it that way.</p>
 */
class MetalComputeCompilerUnificationTest {

    /** Package-private upstream of this test, so only its class resource is inspected. */
    private static final String COMPUTE_PIPELINE = "com.metallum.client.metal.render.MetalComputePipeline";
    private static final String SHADERC_PACKAGE = "org/lwjgl/util/shaderc";

    @Test
    void glslangBridgeExposesComputeStageWithTheComputeStageId() {
        // glslang_stage_t: vertex 0, tess control 1, tess evaluation 2,
        // geometry 3, fragment 4, compute 5.
        assertEquals(5, GlslangBridge.Stage.COMPUTE.glslangStage);
    }

    @Test
    void computeCompilerDoesNotReferenceShaderc() throws IOException {
        String constantPool = new String(readClassBytes(COMPUTE_PIPELINE), StandardCharsets.ISO_8859_1);
        assertTrue(
                constantPool.indexOf(SHADERC_PACKAGE) < 0,
                COMPUTE_PIPELINE + " must not link against " + SHADERC_PACKAGE
                        + ": compute has to compile with the bundled glslang"
        );
    }

    private static byte[] readClassBytes(String binaryName) throws IOException {
        String resource = "/" + binaryName.replace('.', '/') + ".class";
        try (InputStream in = MetalComputeCompilerUnificationTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "class resource " + resource);
            return in.readAllBytes();
        }
    }
}
