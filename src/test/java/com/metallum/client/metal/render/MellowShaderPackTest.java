package com.metallum.client.metal.render;

import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.CustomTextureData;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the Mellow v3.3 acceptance target's custom-texture declarations into
 * the CI suite. The DEFERRED stage is the one that crashed on device:
 * {@code colortex3} borrows the vanilla clouds texture, while the raw
 * worley-noise directive is rewritten by Iris's patcher into the pack-global
 * {@code customtex0} sampler and must be bound as a 64&sup3; {@code R8} 3D
 * texture.
 */
final class MellowShaderPackTest {
    private static final String FIXTURE = "/shaderpacks/Mellow Shader v3.3.zip";

    @Test
    void mellowDefersCloudsAsResourceDataAndWorleyAsRaw3D() throws Exception {
        Path zip = Files.createTempFile("mellow-custom-textures-", ".zip");
        try {
            try (var input = MellowShaderPackTest.class.getResourceAsStream(FIXTURE)) {
                assertNotNull(input, "missing Mellow fixture " + FIXTURE);
                Files.copy(input, zip, StandardCopyOption.REPLACE_EXISTING);
            }
            try (FileSystem fileSystem = FileSystems.newFileSystem(zip, Map.of())) {
                ShaderPack pack = new ShaderPack(
                        fileSystem.getPath("/shaders"),
                        StandardMacros.createStandardEnvironmentDefines(),
                        false
                );
                Map<TextureStage, ? extends Map<String, CustomTextureData>> textures =
                        pack.getCustomTextureDataMap();
                Map<String, CustomTextureData> deferred = textures.get(TextureStage.DEFERRED);
                assertNotNull(deferred, "Mellow must declare DEFERRED custom textures");

                CustomTextureData clouds = deferred.get("colortex3");
                assertInstanceOf(CustomTextureData.ResourceData.class, clouds);
                CustomTextureData.ResourceData resourceData = (CustomTextureData.ResourceData) clouds;
                assertEquals("minecraft", resourceData.getNamespace());
                assertEquals("textures/environment/clouds.png", resourceData.getLocation());
                assertInstanceOf(CustomTextureData.PngData.class, deferred.get("noisetex"));

                // Raw directives are rewritten by Iris into a pack-global
                // customtexN sampler, which the Metal pipeline must bind too.
                Map<String, CustomTextureData> irisTextures = pack.getIrisCustomTextureDataMap();
                CustomTextureData worley = irisTextures.get("customtex0");
                assertInstanceOf(CustomTextureData.RawData3D.class, worley);
                CustomTextureData.RawData3D rawData3D = (CustomTextureData.RawData3D) worley;
                assertEquals(64, rawData3D.getSizeX());
                assertEquals(64, rawData3D.getSizeY());
                assertEquals(64, rawData3D.getSizeZ());
                assertEquals("R8", rawData3D.getInternalFormat().name());
                assertEquals("RED", rawData3D.getPixelFormat().name());
                assertEquals("UNSIGNED_BYTE", rawData3D.getPixelType().name());
                assertEquals(64 * 64 * 64, rawData3D.getContent().length);

                assertDeferredPatchRewritesWorleySampler(pack);
            }
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private static void assertDeferredPatchRewritesWorleySampler(final ShaderPack pack) {
        ProgramSet programs = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
        ProgramSource deferred = Arrays.stream(programs.getComposite(ProgramArrayId.Deferred))
                .filter(source -> source != null && source.isValid())
                .findFirst()
                .orElseThrow();

        IrisMetalProgramFrontend.RasterProgram patched =
                new IrisMetalProgramFrontend(programs).patchComposite(deferred, TextureStage.DEFERRED);
        String fragmentSource = patched.fragmentSource();
        assertFalse(fragmentSource.contains("colortex6"), "raw directive must be renamed by Iris patching");
        assertTrue(fragmentSource.contains("sampler3D customtex0"), fragmentSource);
        assertTrue(fragmentSource.contains("colortex3"), "stage-local ResourceData stays bind-time patched");

        IrisMetalGlslLinker.LinkedRasterProgram linked = IrisMetalGlslLinker.linkDefault(patched);
        assertTrue(
                linked.samplers().stream()
                        .anyMatch(sampler -> sampler.name().equals("customtex0")
                                && sampler.glslType().equals("sampler3D")),
                "linked deferred program must carry the patched 3D sampler: " + linked.samplers()
        );
    }
}
