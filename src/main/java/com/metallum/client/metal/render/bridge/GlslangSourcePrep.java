package com.metallum.client.metal.render.bridge;

import com.metallum.client.metal.render.MetalDebugSwitches;

/**
 * Pure-Java GLSL source preparation shared by {@link GlslangBridge}.
 *
 * <p>Separated from the bridge so Linux gates can pin the exact source glslang
 * receives without triggering the native-library static initializer (which
 * throws on non-macOS hosts). Mirrors the existing behavior: a compatibility
 * preamble with Iris/Minecraft defines, the raster {@code #version} directive
 * stripped (glslang forces 460 through
 * {@code force_default_version_and_profile}), and user defines appended before
 * the source.
 */
public final class GlslangSourcePrep {
    /**
     * Compatibility macros injected before the source when compiling raw
     * shaderpack GLSL. These are normally supplied by Iris's TransformPatcher;
     * defining them here lets the glslang frontend parse shaderpack sources
     * that branch on them ({@code IS_IRIS}, {@code MC_VERSION}, ...).
     */
    private static final String COMPAT_PREAMBLE = String.join("\n",
            "#define IS_IRIS 1",
            "#define MC_VERSION 12111",
            "#define MC_GLSL_VERSION 460",
            "#define MC_GL_VERSION 460",
            "#define MC_RENDER_QUALITY 1.0",
            "#define MC_SHADOW_QUALITY 1.0",
            "#define MC_NORMAL_MAP",
            "#define MC_SPECULAR_MAP",
            "#define METALLUM_GLSLANG_FRONTEND 1",
            ""
    );

    /**
     * Vulkan-mode glslang gives an unqualified float parameter no precision
     * ({@code EpqNone}), so a pack that overloads a built-in such as
     * {@code fma} fails with "overloaded functions must have the same
     * parameter precision qualifiers"; Mellow's FABULOUS composite10 hits
     * this. Declaring the default precision makes user parameters
     * {@code highp}, matching the built-ins. Inert when
     * {@link MetalDebugSwitches#GLSLANG_PRECISION_PREAMBLE} is disabled.
     */
    private static final String PRECISION_PREAMBLE = String.join("\n",
            "precision highp float;",
            "precision highp int;",
            ""
    );

    private GlslangSourcePrep() {
    }

    /** The preamble currently in effect, including the optional precision defaults. */
    public static String compatPreamble() {
        return MetalDebugSwitches.GLSLANG_PRECISION_PREAMBLE
                ? COMPAT_PREAMBLE + PRECISION_PREAMBLE
                : COMPAT_PREAMBLE;
    }

    /**
     * The exact source glslang receives for a raw shaderpack source: version
     * stripped, compatibility preamble (plus the precision defaults) injected
     * and user defines appended.
     */
    public static String buildFullSource(final String source, final String defines) {
        return buildSourceWithDefines(compatPreamble() + stripVersionDirective(source), defines);
    }

    static String stripVersionDirective(final String source) {
        // Find the #version line in the leading run of comments / whitespace.
        String[] lines = source.split("\\R", -1);
        int versionLine = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("#version")) {
                versionLine = i;
                break;
            }
            // Allow leading comments and blank lines before #version.
            if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*")) {
                // Non-comment, non-blank, non-#version line — #version won't
                // appear after real code, stop looking.
                break;
            }
        }
        if (versionLine < 0) {
            return source;
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < lines.length; i++) {
            if (i == versionLine) continue;
            if (!first) sb.append('\n');
            sb.append(lines[i]);
            first = false;
        }
        return sb.toString();
    }

    private static String buildSourceWithDefines(final String source, final String defines) {
        if (defines == null || defines.isBlank()) {
            return source;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : defines.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                sb.append(trimmed).append('\n');
            } else {
                sb.append("#define ").append(trimmed).append('\n');
            }
        }
        sb.append("#line 1\n");
        sb.append(source);
        return sb.toString();
    }
}
