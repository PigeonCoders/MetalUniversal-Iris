package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.GlslangBridge;
import com.mojang.blaze3d.shaders.ShaderType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the vanilla/core shader compiler choice.
 *
 * <p>Vanilla pipelines used to be compiled by MC's {@code GlslCompiler}, which
 * is a thin wrapper over LWJGL shaderc -- the last shaderc caller in the
 * process and the library that aborts inside
 * {@code glslang::TParseContext::lValueErrorCheck} on iOS while reporting an
 * ordinary shader error. They now go through the bundled glslang, with
 * {@code -Dmetallum.iris.vanillaGlslang=off} as the rollback.</p>
 */
class MetalDeviceVanillaCompilerTest {

    @Test
    void shaderTypesMapOntoGlslangStages() {
        assertEquals(GlslangBridge.Stage.VERTEX, MetalDevice.stageFor(ShaderType.VERTEX));
        assertEquals(GlslangBridge.Stage.FRAGMENT, MetalDevice.stageFor(ShaderType.FRAGMENT));
    }

    @Test
    void vanillaGlslangDefaultsOffAndAcceptsExplicitOn() {
        assertFalse(MetalDevice.vanillaGlslang(null), "absent property keeps MC's compiler");
        assertFalse(MetalDevice.vanillaGlslang(""));
        assertFalse(MetalDevice.vanillaGlslang("off"));
        assertFalse(MetalDevice.vanillaGlslang("false"));
        assertFalse(MetalDevice.vanillaGlslang("OFF"));
        assertTrue(MetalDevice.vanillaGlslang("on"), "explicit opt-in uses the bundled compiler");
        assertTrue(MetalDevice.vanillaGlslang(" ON "));
    }
}
