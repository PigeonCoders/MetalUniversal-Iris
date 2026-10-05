package com.metallum.client.metal.render;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the MakeUp-UltraFast "Iris pass composite is missing
 * required sampler 'gaux3'" crash.
 *
 * <p>The pack is older than the colortexN naming convention and samples the
 * legacy render-target aliases in its graph programs ({@code composite} and
 * {@code final}: {@code gaux3} for exposure; {@code deferred}: {@code gaux2})
 * and in its raster programs ({@code gbuffers_water}: {@code gaux1/2/4};
 * {@code gbuffers_armor_glint}: {@code gaux3}; the rest: {@code gaux4}). The
 * execution graph resolved sampler names through {@code colortexN} only, so an
 * active {@code gaux3} came back null and {@code bindRaster} threw before the
 * pass draw.
 *
 * <p>{@link ShaderpackSamplerCoverage} links the real pack without native
 * libraries and pins that every active sampler resolves under the port's name
 * tables, that the crash's {@code gaux3} is among the names actually
 * exercised, and that all four aliases are covered.
 */
final class MakeUpSamplerCoverageTest {
    private static final Path PACK =
            Path.of("build", "compat-packs", "MakeUp-UltraFast-9.5f.zip");
    private static final Set<String> EXPECTED_LEGACY_ACTIVE = Set.of("gaux1", "gaux2", "gaux3", "gaux4");

    @Test
    void makeUpLegacySamplersResolveForActiveSet() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(PACK), () -> "compat pack missing: " + PACK);

        ShaderpackSamplerCoverage.Report report = ShaderpackSamplerCoverage.link(PACK);
        assertFalse(report.programs().isEmpty(), "no MakeUp program was linked");
        assertTrue(report.unresolved().isEmpty(),
                "active MakeUp samplers the binding table cannot resolve: " + report.unresolved()
                        + ShaderpackSamplerCoverage.describe(report));

        // The crash was exactly this pass: composite's vertex stage samples
        // gaux3 for exposure.
        ShaderpackSamplerCoverage.ProgramCoverage composite = report.program("composite");
        assertNotNull(composite, "MakeUp 'composite' was not linked"
                + ShaderpackSamplerCoverage.describe(report));
        assertTrue(composite.active().contains("gaux3"),
                "MakeUp 'composite' must actively sample gaux3 (the crash trigger); active="
                        + composite.active());

        Set<String> activeLegacy = new LinkedHashSet<>();
        for (String name : report.active()) {
            if (!name.startsWith("colortex") && IrisMetalRenderTargets.renderTargetIndex(name) >= 0) {
                activeLegacy.add(name);
            }
        }
        assertTrue(activeLegacy.containsAll(EXPECTED_LEGACY_ACTIVE),
                "MakeUp must exercise gaux1-4; active legacy=" + activeLegacy);
        System.out.println("[makeup-sampler-coverage] linked=" + report.programs().size()
                + " active=" + report.active().size()
                + " activeLegacy=" + activeLegacy);
    }
}
