package com.metallum.client.metal.render.bridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the IO-mapping step in {@link GlslangBridge}.
 *
 * <p>{@code glslang_program_link} never runs the IO mapper, so
 * {@code AUTO_MAP_LOCATIONS} on its own only silences the front-end check and
 * emits no {@code OpDecorate Location}. shaderc calls
 * {@code program.link(...) && program.mapIO()}; so must we, otherwise MC's
 * {@code IntermediaryShaderModule} reflection fails on the first varying with
 * "Couldn't find byte offset for location decoration".</p>
 */
class GlslangBridgeMapIoTest {

    @Test
    void programMapIoIsWired() throws Exception {
        Path classFile = Path.of("build/classes/java/main/com/metallum/client/metal/render/bridge/GlslangBridge.class");
        assumeTrue(Files.exists(classFile), "compileJava output not present");
        String constantPool = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
        assertTrue(constantPool.contains("glslang_program_map_io"),
                "GlslangBridge must bind and call glslang_program_map_io");
    }
}
