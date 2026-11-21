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
    void vanillaGlslangDefaultsOnAndAcceptsExplicitOff() {
        assertTrue(MetalDevice.vanillaGlslang(null), "absent property uses the bundled compiler");
        assertTrue(MetalDevice.vanillaGlslang(""));
        assertTrue(MetalDevice.vanillaGlslang("on"));
        assertTrue(MetalDevice.vanillaGlslang(" ON "));
        assertFalse(MetalDevice.vanillaGlslang("off"), "explicit opt-out restores MC's compiler");
        assertFalse(MetalDevice.vanillaGlslang("false"));
        assertFalse(MetalDevice.vanillaGlslang("OFF"));
    }
}
